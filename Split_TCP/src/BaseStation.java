import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.Queue;


public class BaseStation {

	public static int BS_TO_FH_PORT = 9090;
	public static int BS_TO_FH_ACK_PORT = 9091;
	public static int BS_TO_MH_PORT = 9092;
	private final int ACK_LEN = 1 << 10;

	private Queue<byte[]> q;

	private Thread fhAgent;
	private Thread mhAgent;
	
	private int pktCount;
	private int fhSocketPort;
	private int fhE2eAckSocketPort;
	private int mhSocketPort;
	private int rto;
	private double fhPlp;
	private double mhPlp;
	
	private DatagramSocket fhSocket;
	private DatagramSocket fhE2eAckSocket;
	private DatagramSocket mhSocket;
	private InetSocketAddress fhSocketAddress;
	private InetSocketAddress mhSocketAddress;

	public BaseStation(int rto, double fhPlp, double mhPlp){
		this.rto = rto;
		this.fhPlp = fhPlp;
		this.mhPlp = mhPlp;
		this.pktCount = 0;
		this.q = new LinkedList<byte[]>();

		this.fhSocketPort = FixedHost.FH_TO_BS_PORT;
		this.mhSocketPort = MobileHost.MH_TO_BS_PORT;
		this.fhE2eAckSocketPort = FixedHost.FH_E2E_PORT;

		try {
			fhSocketAddress = new InetSocketAddress(InetAddress.getLocalHost(), fhSocketPort);
			fhSocket = new DatagramSocket(BS_TO_FH_PORT);
//			fhSocket.setSoTimeout(this.rto);
			fhE2eAckSocket = new DatagramSocket(BS_TO_FH_ACK_PORT);
//			fhE2eAckSocket.setSoTimeout(this.rto);
			mhSocketAddress = new InetSocketAddress(InetAddress.getLocalHost(), mhSocketPort);
			mhSocket = new DatagramSocket(BS_TO_MH_PORT);
			mhSocket.setSoTimeout(this.rto);
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}

	private synchronized void qAdd(byte[] data){
		q.add(data);
	}
	
	private synchronized byte[] qPoll(){
		return q.poll();
	}
	
	private synchronized byte[] qPeek(){
		return q.peek();
	}
	
	private synchronized boolean qIsEmpty(){
		return q.isEmpty();
	}
	
	private boolean receiveDataFromFH(){
		System.err.println("BS: Waiting for a new packet to arrive");
		int maxSize = (1<<10) * FixedHost.MSS; //XXX assumption!!
		byte[] buf = new byte[maxSize];
		DatagramPacket pkt = new DatagramPacket(buf, buf.length);
		try {
			fhSocket.receive(pkt);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		
		System.err.println("BS: Received packet #" + pktCount++);
		qAdd(pkt.getData());
		return true;
	}
	
	private boolean canSend(double plp) {
		double p = Math.random();
		return p > plp;
	}
	
	private void sendLACK(){
		DatagramPacket pkt = null;
		try {
			pkt = new DatagramPacket(new byte[0], 0, fhSocketAddress);
			System.err.println("BS: Sending LACK");
			if(canSend(this.fhPlp)){
				fhSocket.send(pkt);
			} else{
				System.err.println("BS: LACK packet dropped");
			}
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}
	
	private boolean waitForLACK() {
		System.err.println("BS: Waiting for LACK");
		byte[] buf = new byte[ACK_LEN];
		// XXX No need for an actual message, a dummy message would be
		// sufficient!
		DatagramPacket dummyPkt = new DatagramPacket(buf, buf.length);
		try {
			mhSocket.receive(dummyPkt);
			System.err.println("BS: Received LACK");
		} catch (SocketTimeoutException e) { // timeout occured
			System.err.println("BS: Timeout. Retransmission required");
			return false;
		} catch (IOException e) {
			e.printStackTrace();
		}

		return true;
	}
	
	private void fhHandler(){
		while(true){
			boolean success = receiveDataFromFH();
			if(success)
				sendLACK();
		}
	}
	
	private void mhHandler(){
		boolean success = true;
		byte[] data = null;
		
		while(true){
			if(qIsEmpty()){
				try {
					this.wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}			
			
			DatagramPacket pkt = null;
			if(success){
				data = qPoll();
			}else{
				//leave data[] as is for retransmission
			}

			
			try {
				pkt = new DatagramPacket(data, data.length, mhSocketAddress);
			} catch (SocketException e) {
				e.printStackTrace();
			}

			if(canSend(mhPlp)){
				try {
					mhSocket.send(pkt);
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else{
				//packet dropped
			}

			success = waitForLACK();
		}
	}
	
	public void run(){
		fhAgent = new Thread(new Runnable() {
			@Override
			public void run() {
				fhHandler();
			}
		});
		
		mhAgent = new Thread(new Runnable() {
			@Override
			public void run() {
				mhHandler();
			}
		});
		
		fhAgent.start();
//		mhAgent.start();
	}
	
	public static void main(String[] args) {
		double plp = 0.1;
		int rto = 5; //seconds
		new BaseStation(rto, plp, plp).run();
	}
}
