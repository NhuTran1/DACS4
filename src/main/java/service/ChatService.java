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
	    
	 // 1. USER
	    public Users getUserById(Integer id) {
	        return userService.getUserById(id);
	    }

	    public Users getUserByUsername(String username) {
	        return userService.getUserByUserName(username);
	    }

	    public boolean updateUserDisplayName(Integer id, String newName) {
	        return userService.updateUserDisplayName(id, newName);
	    }

	    public List<Users> searchUser(String keyword) {
	        return userService.searchUser(keyword);
	    }
	    
	    // 2. FRIEND
	    public void sendFriendRequest(Integer fromUserId, Integer toUserId) {
	        friendService.sendFriendRequest(fromUserId, toUserId);
	    }

	    public void acceptFriendRequest(Integer requestId) {
	        friendService.acceptFriendRequest(requestId);
	    }

	    public void denyFriendRequest(Integer requestId) {
	        friendService.denyFriendRequest(requestId);
	    }

	    public List<Users> listFriends(Integer userId) {
	        return friendService.listFriends(userId);
	    }
	    
	    public List<FriendRequest> listFriendRequest(Integer userId){
			return friendDao.listFriendRequest(userId);
		}
	    
	    // 3. CONVERSATION
	    public Conversation getConversationById(Integer conversationId) {
	        return conversationService.getConversationById(conversationId);
	    }

	    public List<Conversation> listConversationsByUser(Integer userId) {
	        return conversationService.listConversationsByUser(userId);
	    }

	    public Conversation getDirectConversation(Integer userAId, Integer userBId) {
	        return conversationService.getDirectConversation(userAId, userBId);
	    }

	    public Conversation createDirectConversation(Integer userAId, Integer userBId) {
	        return conversationService.createDirectConversation(userAId, userBId);
	    }

		// NEW: wrapper for group creation
		// public Conversation createGroupConversation(String name, List<Integer> memberIds) {
		// 	return conversationService.createGroupConversation(name, memberIds);
		// }

		public List<Users> listParticipants(Integer conversationId) {
			return conversationService.listParticipants(conversationId);
		}

		public boolean updateConversationName(Integer conversationId, String newName, Integer requestUserId) {
			return conversationService.updateConversationName(conversationId, newName, requestUserId);
		}
	    
	 // 4. MESSAGE
	    public Message sendMessage(Integer conversationId, Integer senderId, String content, String imageUrl) {
	        return messageService.sendMessage(conversationId, senderId, content, imageUrl);
	    }

	    public List<Message> listMessages(Integer conversationId) {
	        return messageService.listMessages(conversationId);
	    }

	    public void markMessageSeen(Integer messageId, Integer userId) {
	        messageService.markMessageSeen(messageId, userId);
	    }

	    public void resetUnread(Integer conversationId, Integer userId) {
	        messageService.resetUnread(conversationId, userId);
	    }

	    public Message getMessageById(Integer msgId) {
	        return messageService.getMessageById(msgId);
	    }
	    

}
