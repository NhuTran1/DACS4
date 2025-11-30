package network.p2p;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PeerManager {
    private Map<Long, PeerConnection> connections = new ConcurrentHashMap<>();
    
    private PeerEventHandler eventHandler;
    
    public PeerManager(PeerEventHandler handler) {
        this.eventHandler = handler;
    }
    
    public PeerConnection connectToPeer(PeerInfo info) throws Exception {
        if (connections.containsKey(info.getUserId()))
            return connections.get(info.getUserId());

        PeerConnection conn = new PeerConnection(info, eventHandler);
        conn.connect();
        connections.put(info.getUserId(), conn);

        return conn;
    }

    public void sendToPeer(Long userId, String message) throws Exception {
        PeerConnection conn = connections.get(userId);
        if (conn != null) conn.send(message);
    }
    
    public void closePeer(Long userId) {
        PeerConnection conn = connections.remove(userId);
        if (conn != null) conn.close();
    }

    public void closeAll() {
        for (PeerConnection c : connections.values()) c.close();
        connections.clear();
    }
    
    
}
