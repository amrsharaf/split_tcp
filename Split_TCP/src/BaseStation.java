import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
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
	private static final int RTO = 1*1000; // in melliseconds
	
	private Queue<Packet> q;

	private Thread fhAgent;
	private Thread mhAgent;
	
	private int fhSocketPort;
	private int fhEndAckSocketPort;
	private int mhSocketPort;
	private int rto;
	private int seqNo;
	private double fhPlp;
	private double mhPlp;
	
	private DatagramSocket fhSenderSocket;
	private DatagramSocket fhEndAckSenderSocket;
	private DatagramSocket mhSocket;
	private InetSocketAddress fhSocketAddress;
	private InetSocketAddress mhSocketAddress;
	private InetSocketAddress fhEndAckSocketAddress;
	
	public BaseStation(int rto, double fhPlp, double mhPlp){
		this.rto = rto;
		this.fhPlp = fhPlp;
		this.mhPlp = mhPlp;
		this.q = new LinkedList<Packet>();
		this.seqNo = -1;
		
		this.fhSocketPort = FixedHost.FH_TO_BS_PORT;
		this.mhSocketPort = MobileHost.MH_TO_BS_PORT;
		this.fhEndAckSocketPort = FixedHost.FH_E2E_PORT;

		try {
			fhSocketAddress = new InetSocketAddress(InetAddress.getLocalHost(), fhSocketPort);
			fhSenderSocket = new DatagramSocket(BS_TO_FH_PORT);

			fhEndAckSocketAddress = new InetSocketAddress(InetAddress.getLocalHost(), fhEndAckSocketPort);
			fhEndAckSenderSocket = new DatagramSocket(BS_TO_FH_ACK_PORT);

			mhSocketAddress = new InetSocketAddress(InetAddress.getLocalHost(), mhSocketPort);
			mhSocket = new DatagramSocket(BS_TO_MH_PORT);
			mhSocket.setSoTimeout(this.rto);
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}

	private synchronized void qAdd(Packet pkt){
		q.add(pkt);
		if(q.size()==1)
			notify();
	}
	
	private synchronized Packet qPoll(){
		if(q.isEmpty()){
			try {
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		return q.poll();
	}
	
	private synchronized Packet qPeek(){
		if(q.isEmpty()){
			try {
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		return q.peek();
	}
	
	
	private Packet extractPacket(DatagramPacket dgPkt) throws IOException{
		ByteArrayInputStream bais = new ByteArrayInputStream(dgPkt.getData());
		ObjectInputStream ois = new ObjectInputStream(bais);
		Packet pkt = null;
		try {
			pkt = (Packet) ois.readObject();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} finally{
			ois.close();
		}
		
		return pkt;
	}
	
	private boolean receiveDataFromFH(){
		System.err.println("BS: Waiting for a new packet to arrive");
		int maxSize = (MobileHost.E2E_WND+1) * FixedHost.MSS; //XXX upper limit assumption!!
		byte[] buf = new byte[maxSize];
		DatagramPacket dgPkt = new DatagramPacket(buf, buf.length);
		try {
			fhSenderSocket.receive(dgPkt);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		
		Packet pkt;
		try {
			pkt = extractPacket(dgPkt);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		System.err.println("BS: Received packet #" + pkt.getId());

		if(pkt.getId() == seqNo){
			System.err.println("BS: Duplicated packet, ignore it but send a LACK for it");
		} else if(pkt.getId() == 0 || pkt.getId()>seqNo){ //if id == 0, this means new file
			seqNo = pkt.getId();
			byte[] data = new byte[dgPkt.getLength()];
			System.arraycopy(dgPkt.getData(), 0, data, 0, dgPkt.getLength());
//			qAdd(new Packet(pkt.getId(), dgPkt.getData()));
			qAdd(new Packet(pkt.getId(), data));
		}
		
		return true;
	}
	
	private boolean canSend(double plp) {
		double p = Math.random();
		return p > plp;
	}

	private void sendAck(DatagramSocket senderSocket, InetSocketAddress address, String ackName){
		DatagramPacket dgPkt = null;
		try {
			dgPkt = new DatagramPacket(new byte[0], 0, address);
			System.err.println("BS: Sending " + ackName);
//			if(canSend(this.fhPlp)){
				senderSocket.send(dgPkt);
//			} else{
//				System.err.println("BS: " + ackName + " packet dropped");
//			}
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}
	
	private void sendLACK(){
		sendAck(fhSenderSocket, fhSocketAddress, "LACK");
	}
	
	private boolean waitForLACK() {
		System.err.println("BS: Waiting for LACK");
		// XXX No need for an actual message, a dummy message would be
		// sufficient!
		DatagramPacket dummyPkt = new DatagramPacket(new byte[0], 0);
		try {
			mhSocket.receive(dummyPkt);
			System.err.println("BS: Received LACK");
			qPoll();
		} catch (SocketTimeoutException e) { // timeout occured
			System.err.println("BS: Timeout. Retransmission required");
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
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
	
	private void sendEndAck(){
		sendAck(fhEndAckSenderSocket, fhEndAckSocketAddress, "end-to-end ACK");
	}
	
	private void send(Packet pkt, InetSocketAddress address){
		DatagramPacket dgPkt = null;
		boolean success = false;
		byte[] data = pkt.getData();

		while(!success){
			try {
				dgPkt = new DatagramPacket(data, data.length, address);
				System.err.println("BS: Sending packet #" + pkt.getId());
				if(canSend(mhPlp)){
					mhSocket.send(dgPkt);
				} else{
					System.err.println("BS: Packet #" + pkt.getId() + " dropped");
				}
			} catch (SocketException e) {
				e.printStackTrace();
			} catch (IOException e){
				e.printStackTrace();
			}

			success = waitForLACK();
		}
		
		if((pkt.getId() % MobileHost.E2E_WND) == 0)
			sendEndAck();
	}
	
	private void mhHandler(){
		Packet pkt = null;
		
		while(true){	
			pkt = qPeek();
			send(pkt, mhSocketAddress);
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
		mhAgent.start();
	}
	
	public static void main(String[] args) {
		double plp = 0.1;
		new BaseStation(BaseStation.RTO, plp, plp).run();
	}
}
