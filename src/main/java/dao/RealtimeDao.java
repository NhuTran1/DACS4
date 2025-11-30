package dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import model.ClientConnection;

//quan li trang thai realtime cua nguoi dung
public class RealtimeDao {
	/** userId → danh sách connection của user */
	 private final Map<Long, ClientConnection> onlineUsers = new ConcurrentHashMap<>();
    
	//  Map user → conversation tham gia
	    private final Map<Long, List<Long>> userToConversations = new ConcurrentHashMap<>();
	
	 // Singleton (tùy chọn)
	    private static final RealtimeDao instance = new RealtimeDao();
	    private RealtimeDao() {}
	    public static RealtimeDao getInstance() {
	        return instance;
	    }
	    
	    
    /** Thêm kết nối mới cho user */
	    public void addOnlineUser(Long userId, ClientConnection conn) {
	        onlineUsers.put(userId, conn);
	    }
    
    /** Xóa 1 connection của user */
	    public void removeOnlineUser(Long userId) {
	        onlineUsers.remove(userId);
	        userToConversations.remove(userId);
	    }
    
	    public boolean isOnline(Long userId) {
	        return onlineUsers.containsKey(userId);
	    }

	    public ClientConnection getConnection(Long userId) {
	        return onlineUsers.get(userId);
	    }

	    public Map<Long, ClientConnection> getAllOnlineUsers() {
	        return onlineUsers;
	    }
    
	    // ===== CONVERSATION MAPPING =====
	    
	    //Lưu danh sách conversation mà user tham gia.
	    public void setUserConversations(Long userId, List<Long> conversationIds) {
	        userToConversations.put(userId, conversationIds);
	    }

	    public List<Long> getUserConversations(Long userId) {
	        return userToConversations.getOrDefault(userId, List.of());
	    }

	    public void removeUserConversations(Long userId) {
	        userToConversations.remove(userId);
	    }
}
