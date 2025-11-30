package network.p2p;


public interface PeerEventHandler {
    void onMessage(Long fromUserId, String msg);
    void onDisconnect(Long userId);
}

