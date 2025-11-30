package network.tcp;

public interface TcpEventHandler {
    void onMessageReceived(String message);
    
    void onUserOnline(String userId);
    
    void onUserOffline(String userId);
    
    void onTyping(String userId);
    
    void onError(Exception e);
}
