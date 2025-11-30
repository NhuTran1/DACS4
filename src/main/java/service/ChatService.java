package service;

import java.util.List;

import dao.FriendDao;
import model.ClientConnection;
import model.Conversation;
import model.FriendRequest;
import model.Message;
import model.Users;

//Đóng vai trof là 1 facade -> giúp controller chỉ cần giao tiếp vs 1 class
public class ChatService {
	  	private final ConversationService conversationService = new ConversationService();
	    private final MessageService messageService = new MessageService();
	    private final UserService userService = new UserService();
	    private final FriendService friendService = new FriendService();
	    private final FriendDao friendDao = new FriendDao();
	    private final RealtimeService realtimeService = new RealtimeService();
	    
	 // 1. USER
	    public Users getUserById(Long id) {
	        return userService.getUserById(id);
	    }

	    public Users getUserByUsername(String username) {
	        return userService.getUserByUserName(username);
	    }

	    public boolean updateUserDisplayName(Long id, String newName) {
	        return userService.updateUserDisplayName(id, newName);
	    }

	    public List<Users> searchUser(String keyword) {
	        return userService.searchUser(keyword);
	    }
	    
	    // 2. FRIEND
	    public void sendFriendRequest(Long fromUserId, Long toUserId) {
	        friendService.sendFriendRequest(fromUserId, toUserId);
	    }

	    public void acceptFriendRequest(Long requestId) {
	        friendService.acceptFriendRequest(requestId);
	    }

	    public void denyFriendRequest(Long requestId) {
	        friendService.denyFriendRequest(requestId);
	    }

	    public List<Users> listFriends(Long userId) {
	        return friendService.listFriends(userId);
	    }
	    
	    public List<FriendRequest> listFriendRequest(Long userId){
			return friendDao.listFriendRequest(userId);
		}
	    
	    // 3. CONVERSATION
	    public Conversation getConversationById(Long conversationId) {
	        return conversationService.getConversationById(conversationId);
	    }

	    public List<Conversation> listConversationsByUser(Long userId) {
	        return conversationService.listConversationsByUser(userId);
	    }

	    public Conversation getDirectConversation(Long userAId, Long userBId) {
	        return conversationService.getDirectConversation(userAId, userBId);
	    }

	    public Conversation createDirectConversation(Long userAId, Long userBId) {
	        return conversationService.createDirectConversation(userAId, userBId);
	    }

	    public List<Users> listParticipants(Long conversationId) {
	        return conversationService.listParticipants(conversationId);
	    }

	    public boolean updateConversationName(Long conversationId, String newName, Long requestUserId) {
	        return conversationService.updateConversationName(conversationId, newName, requestUserId);
	    }
	    
	 // 4. MESSAGE
	    public Message sendMessage(Long conversationId, Long senderId, String content, String imageUrl) {
	        return messageService.sendMessage(conversationId, senderId, content, imageUrl);
	    }

	    public List<Message> listMessages(Long conversationId) {
	        return messageService.listMessages(conversationId);
	    }

	    public void markMessageSeen(Long messageId, Long userId) {
	        messageService.markMessageSeen(messageId, userId);
	    }

	    public void resetUnread(Long conversationId, Long userId) {
	        messageService.resetUnread(conversationId, userId);
	    }

	    public Message getMessageById(Long msgId) {
	        return messageService.getMessageById(msgId);
	    }
	    
	    //5. Realtime
	    public void onUserOnline(Long userId, ClientConnection conn) {
	        realtimeService.onUserOnline(userId, conn);
	    }

	    public void onUserOffline(Long userId, ClientConnection conn) {
	        realtimeService.onUserOffline(userId);
	    }

	    public void sendTyping(Long conversationId, Long userId) {
	        realtimeService.sendTyping(conversationId, userId);
	    }

	    public void sendMessageRealtime(Long conversationId, Message msg) {
	        realtimeService.sendMessageRealtime(conversationId, msg);
	    }

	    public void callSignal(Long toUserId, Long fromUserId, String type, Object payload) {
	        realtimeService.sendCallSignal(toUserId, fromUserId, type, payload);
	    }
}
