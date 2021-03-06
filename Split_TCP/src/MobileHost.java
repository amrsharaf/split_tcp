import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;


public class MobileHost {
	
	public static int E2E_WND = 50;
	public static int MH_TO_BS_PORT = 6060;
	
	private InetSocketAddress bsSocketAddress;
	private DatagramSocket MHSocket;
	private double bsPlp;
	private int seqNo;
	private int bsSocketPort;
	private DataOutputStream dos = null;
	private long sessionStartTime = 0;
	
	public MobileHost(double bsPlp){
		this.bsPlp = bsPlp;
		this.seqNo = -1;
		
		this.bsSocketPort = BaseStation.BS_TO_MH_PORT;

		try {
			bsSocketAddress = new InetSocketAddress(InetAddress.getLocalHost(), bsSocketPort);
			MHSocket = new DatagramSocket(MH_TO_BS_PORT);
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}
	
	private boolean canSend(double plp) {
		double p = Math.random();
		return p > plp;
	}

	private void sendLACK(){
		DatagramPacket dgPkt = null;
		try {
			dgPkt = new DatagramPacket(new byte[0], 0, bsSocketAddress);
			System.err.println("MH: Sending LACK");
//			if(canSend(this.bsPlp)){
				MHSocket.send(dgPkt);
//			} else{
//				System.err.println("MH: LACK packet dropped");
//			}
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}		
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
	
	private boolean receiveDataFromBS() throws IOException{
		System.err.println("MH: Waiting for a new packet to arrive");
		int maxSize = (MobileHost.E2E_WND+1) * FixedHost.MSS; //upper limit for the packet size..
		byte[] buf = new byte[maxSize];
		DatagramPacket dgPkt = new DatagramPacket(buf, buf.length);
		try {
			MHSocket.receive(dgPkt);
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
		System.err.println("MH: Received packet #" + pkt.getId());

		boolean duplicated = false;
		if(pkt.getId() == seqNo){
			System.err.println("MH: Duplicated packet, ignore it but send a LACK for it");
			duplicated = true;
		} else if(pkt.getId() == 0 ){ //if id == 0, this means new file
			seqNo = pkt.getId();
			this.sessionStartTime = System.currentTimeMillis();
			System.err.println("New MH session starts at System time = " + sessionStartTime);
			dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream("output")));
		} else if(pkt.getId()>seqNo){
			seqNo = pkt.getId();
		}

		if(!duplicated){
			if(pkt.getData().length == 0){ //end of file
				System.err.println("MH: Received EOF packet");
				long sessionEnd = System.currentTimeMillis();
				System.err.println("File transmission ended at System Time = " + sessionEnd);
				double sessionDuration = (sessionEnd - sessionStartTime) /1000.0;
				System.err.println("MH session duration = " + sessionDuration + " seconds");
				dos.close();
			} else{
				dos.write(pkt.getData());
			}
		}
		
		return true;
	}

	public void run(){
		while(true){
			boolean success = false;
			try {
				success = receiveDataFromBS();
			} catch (IOException e) {
				e.printStackTrace();
			}
			if(success)
				sendLACK();
		}
	}

	public static void main(String[] args) {
		double plp = 0.1;
		new MobileHost(plp).run();
	}
}
