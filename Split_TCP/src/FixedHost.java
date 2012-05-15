import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.LinkedList;
import java.util.Queue;

public class FixedHost {

	public static int FH_TO_BS_PORT = 7070;
	public static int FH_E2E_PORT = 7071;
	
	private int cwnd;
	private int e2ewnd;
	private int slowStartThreshold;
	private int mss;
	private int rto;
	private Queue<DatagramPacket> q; 
	private double plp;

	private int dataSocketPort;
	private int e2eAckSocketPort;
	private DatagramSocket dataSocket;
	private DatagramSocket e2eAckSocket;
	private InetSocketAddress bsSocketAddress;

	// TODO: set the defaults for the following constants
	public static final int MSS = 1 << 10; // 1 KB
	private static final int RTO = 5; // in seconds
	private static final int SLOW_START_THRESHOLD = 8;
	private final int ACK_LEN = 1 << 10;

	public FixedHost(int e2ewnd, double plp, int dataSocketPort,
			int e2eAckSocketPort, InetSocketAddress bsSocketAddress) {
		this(e2ewnd, SLOW_START_THRESHOLD, RTO, plp, dataSocketPort,
				e2eAckSocketPort, bsSocketAddress);
	}

	public FixedHost(int e2ewnd, int slowStartThreshold, 
			int rto, double plp, int dataSocketPort, int e2eAckSocketPort,
			InetSocketAddress bsSocketAddress) {
		this.cwnd = 1;
		this.e2ewnd = e2ewnd;
		this.slowStartThreshold = slowStartThreshold;
		this.mss = MSS;
		this.rto = rto;
		this.plp = plp;

		q = new LinkedList<DatagramPacket>();

		this.dataSocketPort = dataSocketPort;
		this.e2eAckSocketPort = e2eAckSocketPort;
		this.bsSocketAddress = bsSocketAddress;
		try {
			dataSocket = new DatagramSocket(this.dataSocketPort);
			dataSocket.setSoTimeout(this.rto);
			e2eAckSocket = new DatagramSocket(this.e2eAckSocketPort);
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	public void setPLP(double plp){
		this.plp = plp;
	}
	
	public double getPLP(){
		return this.plp;
	}
	
	public int getMSS(){
		return this.mss;
	}
	
	private boolean canSend() {
		double p = Math.random();
		return p > plp;
	}

	private void controlCongestionWindow(boolean success) {
		if (success) {
			if (cwnd >= slowStartThreshold) //congestion avoidance
				cwnd++;
			else //slow start
				cwnd *= 2;
		} else { //congestion control
			if(cwnd == 1)
				slowStartThreshold = 1;
			else
				slowStartThreshold = cwnd / 2;
			cwnd = 1;
		}
		
		System.err.println("FH: cwnd = " + cwnd + ", slow start threshould = " + slowStartThreshold);
	}

	private boolean waitForLACK() {
		System.err.println("FH: Waiting for LACK");
		byte[] buf = new byte[ACK_LEN];
		// XXX No need for an actual message, a dummy message would be
		// sufficient!
		DatagramPacket dummyPkt = new DatagramPacket(buf, buf.length);
		try {
			dataSocket.receive(dummyPkt);
			System.err.println("FH: Received LACK");
		} catch (SocketTimeoutException e) { // timeout occured
			System.err.println("FH: Timeout. Retransmission required");
			return false;
		} catch (IOException e) {
			e.printStackTrace();
		}

		return true;
	}

	private void waitForE2EAck() {
		System.err.println("FH: Waiting for end-to-end ACK");
		byte[] buf = new byte[ACK_LEN];
		// XXX No need for an actual message, a dummy message would be
		// sufficient!
		DatagramPacket dummyPkt = new DatagramPacket(buf, buf.length);
		try {
			e2eAckSocket.receive(dummyPkt);
			System.err.println("FH: Received end-to-end ACK");
			q.poll();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private boolean isBufferFull() {
		return q.size() == e2ewnd;
	}

	public void sendFile(String fileName) throws IOException {
		File file = new File(fileName);
		if (!file.exists()) {
			System.err.println("File " + fileName + " not found!");
			return;
		}

		//XXX: 
		/*
		 * what about:
		 * establishing the connection (should i receive a request? or send a request or??)
		 * closing the connection so that the MH wouldn't wait for further data
		 * sending the file name
		 */
		DataInputStream dis = new DataInputStream(new FileInputStream(file));
		boolean success = true;
		byte[] chunk;
		DatagramPacket pkt = null;
		
		int pktId = 0;
		while (true) {
		
			if (success) {
				if (isBufferFull()) {
					System.err.println("FH: buffer is full");
					//XXX uncomment that line
//					waitForE2EAck();
				}

				chunk = new byte[cwnd * mss];
				int pktSize = dis.read(chunk);
				if (pktSize <= 0){
					System.err.println("FH: File transmission completed");
					break;
				}

				pkt = new DatagramPacket(chunk, pktSize, bsSocketAddress);
				q.add(pkt);
				
				System.err.println("-------------------------------------------\n");
				pktId++;
				System.err.println("FH: Sending packet #" + pktId + ", packet size = " + cwnd + " * " + mss + " Bytes");
			} else {
				// leave chunk and pkt as is for retransmission
				System.err.println("FH: Re-transmitting packet #" + pktId + ", packet size = " + cwnd + " * " + mss + " Bytes");
			}
			
			if (canSend()){
				dataSocket.send(pkt);
			} else{
				System.err.println("FH: Packet #" + pktId + " dropped");
			}

			success = waitForLACK();
			controlCongestionWindow(success);
		}
	}
	
	public static void main(String[] args) throws Exception {
		String fileName = null;
//		fileName = "TRON Legacy-Derezzed.flv";
		fileName = "AdvancedNetworksProject.pdf";
		InetSocketAddress bsAddress = new InetSocketAddress(InetAddress.getLocalHost(), BaseStation.BS_TO_FH_PORT);
		FixedHost fh = new FixedHost(MobileHost.E2E_WND, 0.1, 7070, 7071, bsAddress);
		fh.sendFile(fileName);
	}
}
