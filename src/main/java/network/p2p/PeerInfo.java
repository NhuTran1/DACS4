package network.p2p;

public class PeerInfo {
	 private Long userId;
    private String ip;
    private int port;

    public PeerInfo(Long userId, String ip, int port) {
        this.userId = userId;
        this.ip = ip;
        this.port = port;
    }

    public Long getUserId() { 
    	return userId; 
    }
    
    public String getIp() { 
    	return ip; 
    }
    
    public int getPort() { 
    	return port; 
    }
}
