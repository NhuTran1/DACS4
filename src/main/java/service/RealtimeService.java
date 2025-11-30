package service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import dao.ConversationDao;
import dao.RealtimeDao;
import model.ClientConnection;
import model.Message;
import model.Users;

public class RealtimeService {
	
	private final RealtimeDao realtimeDao = RealtimeDao.getInstance();
    private final FriendService friendService = new FriendService();
    private final ConversationService conversationService = new ConversationService();
    private final ObjectMapper mapper = new ObjectMapper();
    
    //user online
    public void onUserOnline(Long userId, ClientConnection conn) {
    	try {
    		realtimeDao.addOnlineUser(userId, conn);
    		
//    		//  Lấy danh sách conversation từ DB
            List<Long> convIds = conversationService.listConversationsByUser(userId)
            											.stream()
            											.map(c -> c.getId())
            											.toList();
            
            if (convIds != null) {
                realtimeDao.setUserConversations(userId, convIds);
                // cũng cập nhật vào ClientConnection local copy
                convIds.forEach(conn::addConversation);
            }
            
    		// payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("event", "userOnline");
            payload.put("userId", userId);
            
            String json = mapper.writeValueAsString(payload);
            
            // broadcast tới danh sách bạn bè
            broadcastToFriends(userId, json, null);
    	}catch (Exception e) {
            System.err.println("[RealtimeService] onUserOnline error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    	
    	//user offline
    	public void onUserOffline(Long userId) {
    		try {
    			realtimeDao.removeOnlineUser(userId);
    			
    			Map<String, Object> payload = new HashMap<>();
                payload.put("event", "userOffline");
                payload.put("userId", userId);
                
                String json = mapper.writeValueAsString(payload);

                // broadcast tới bạn bè
                broadcastToFriends(userId, json, null);
    		}catch (Exception e) {
                System.err.println("[RealtimeService] onUserOffline error: " + e.getMessage());
                e.printStackTrace();
            }
    	}
    	
    	//typing
    	public void sendTyping(Long conversationId, Long userId) {
    		try {
    			Map<String, Object> payload = new HashMap<>();
                payload.put("event", "typing");
                payload.put("conversationId", conversationId);
                payload.put("userId", userId);
                
                String json = mapper.writeValueAsString(payload);
                
                // lấy participants (từ ConversationService)
                List<Users> participants = conversationService.listParticipants(conversationId);
                
                if(participants == null) return;
                
                for(Users u: participants) {
                	if(u.getId().equals(userId)) continue;
                	
                	safeSendToUser(u.getId(), json);
                }
    		} catch (Exception e) {
                System.err.println("[RealtimeService] sendTyping error: " + e.getMessage());
                e.printStackTrace();
            }
    	}
    	
    	//message realtime
    	public void sendMessageRealtime(Long conversationId, Message msg) {
    		try {
    			Map<String, Object> payload = new HashMap<>();
    			payload.put("event", "newMessage");
    			payload.put("conversationId", conversationId);
    			payload.put("message", msg);
    			
    			String json = mapper.writeValueAsString(payload);
    			
    			List<Users> participants = conversationService.listParticipants(conversationId);
    			
    			if(participants == null ) return;
    			
    			for(Users u : participants) {
    				safeSendToUser(u.getId(), json);
    			}
    		}catch (Exception e) {
                System.err.println("[RealtimeService] sendMessageRealtime error: " + e.getMessage());
                e.printStackTrace();
            }
    	}
    	
    	//call signal
    	public void sendCallSignal(Long toUserId, Long fromUserId, String signalType, Object signalPayload) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("event", "callSignal");
                payload.put("fromUserId", fromUserId);
                payload.put("signalType", signalType);
                payload.put("signal", signalPayload);

                String json = mapper.writeValueAsString(payload);

                safeSendToUser(toUserId, json);

            } catch (Exception e) {
                System.err.println("[RealtimeService] sendCallSignal error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    	
    	//gui toi friend tru chinh user gui(excludeUserId)
    	public void broadcastToFriends(Long userId, String jsonPayload, Long excludeUserId) {
    		List<Users> friends = friendService.listFriends(userId);
    		if(friends == null || friends.isEmpty()) return;
    		
    		List<Long> friendIds = friends.stream()
    										.map(Users::getId)
    										.toList();
    		
    		for(Long f : friendIds) {
    			if(excludeUserId != null && excludeUserId.equals(f)) continue;
    			safeSendToUser(f, jsonPayload);
    		}
    	}
    	
    	//send
    	private void safeSendToUser(Long userId, String json) {
    		ClientConnection conn = realtimeDao.getConnection(userId);
    		if(conn == null) return;
    		
    		conn.getTcpConnection().send(json);
    	}
}
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	

//    private final RealtimeDao realtimeDao = new RealtimeDao();
//    private final ConversationDao conversationDao = new ConversationDao();
//
//    /** Khi user có 1 connection mới → thêm vào DAO và broadcast presence */
//    public void onUserOnline(Long userId, ClientConnection conn) {
//        realtimeDao.addConnection(userId, conn);
//        broadcastPresence(userId, true);
//    }
//
//    /** Khi user ngắt 1 connection → nếu không còn connection nào => offline */
//    public void onUserOffline(Long userId, ClientConnection conn) {
//        realtimeDao.removeConnection(userId, conn);
//        if (!realtimeDao.isUserOnline(userId)) {
//            broadcastPresence(userId, false);
//        }
//    }
//
//    /** Broadcast trạng thái online/offline cho toàn bộ user cần nhận */
//    public void broadcastPresence(Long userId, boolean online) {
//        for (Long uid : realtimeDao.getOnlineUsers()) {
//            if (!uid.equals(userId)) {
//                sendToUser(uid, "presence", new PresenceDTO(userId, online));
//            }
//        }
//    }
//
//    /** Gửi sự kiện "typing" tới những người trong conversation */
//    public void sendTyping(Long conversationId, Long userId) {
//        var participants = conversationDao.listParticipants(conversationId);
//        for (var u : participants) {
//            if (!u.getId().equals(userId)) {
//                sendToUser((long)u.getId(), "typing", new TypingDTO(conversationId, userId));
//            }
//        }
//    }
//
//    /** Gửi tin nhắn realtime tới tất cả thành viên conversation */
//    public void sendMessageRealtime(Long conversationId, Message msg) {
//        var participants = conversationDao.listParticipants(conversationId);
//        
//        for (var u : participants) {
//            sendToUser((long)u.getId(), "new_message", msg);
//        }
//    }
//
//    /** Gửi tín hiệu cuộc gọi (WebRTC signaling) */
//    public void sendCallSignal(Long toUser, String signalData) {
//        sendToUser(toUser, "call_signal", signalData);
//    }
//
//    /** Gửi event trực tiếp đến 1 user qua toàn bộ connection của họ */
//    public void sendToUser(Long userId, String event, Object data) {
//        List<ClientConnection> conns = realtimeDao.getConnections(userId);
//        
//        for (var conn : conns) {
//            conn.sendEvent(event, data);
//        }
//    }
//
//    /* ==== DTO CLASSES ==== */
//    public static class PresenceDTO {
//        public Long userId;
//        public boolean online;
//        public PresenceDTO(Long userId, boolean online) { this.userId = userId; this.online = online; }
//    }
//    public static class TypingDTO {
//        public Long conversationId;
//        public Long userId;
//        public TypingDTO(Long cid, Long uid) { this.conversationId = cid; this.userId = uid; }
//    }

