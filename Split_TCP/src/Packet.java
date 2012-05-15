import java.io.Serializable;

public class Packet implements Serializable{

	private static final long serialVersionUID = -2484749144652251063L;
	private int id;
	private byte[] data;
	
	public Packet(int id, byte[] data){
		this.id = id;
		this.data = data;
	}
	
	public int getId(){
		return this.id;
	}
	
	public byte[] getData(){
		return this.data;
	}
}
