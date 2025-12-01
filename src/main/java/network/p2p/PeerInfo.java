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

    public void setUserId(Long userId) { this.userId = userId; }
    public void setIp(String ip) { this.ip = ip; }
    public void setPort(int port) { this.port = port; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerInfo)) return false;
        PeerInfo p = (PeerInfo) o;
        return port == p.port && (userId != null ? userId.equals(p.userId) : p.userId == null)
                && (ip != null ? ip.equals(p.ip) : p.ip == null);
    }

    @Override
    public int hashCode() {
        int result = (userId != null ? userId.hashCode() : 0);
        result = 31 * result + (ip != null ? ip.hashCode() : 0);
        result = 31 * result + port;
        return result;
    }
    
}
