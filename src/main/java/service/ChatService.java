package service;

import java.util.List;

import dao.FriendDao;
import model.Conversation;
import model.FriendRequest;
import model.Message;
import model.Users;
import protocol.P2PMessageProtocol;

/**
 * ChatService - Facade pattern
 * Đóng vai trò trung tâm, tổng hợp tất cả các service
 * Controller chỉ cần giao tiếp với ChatService
 */
public class ChatService {
    private final ConversationService conversationService = new ConversationService();
    private final MessageService messageService = new MessageService();
    private final UserService userService = new UserService();
    private final FriendService friendService = new FriendService();
    private final FriendDao friendDao = new FriendDao();
    
    // ===== USER SERVICE =====
    
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
    
    // ===== FRIEND SERVICE =====
    
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
    
    public List<FriendRequest> listFriendRequest(Integer userId) {
        return friendDao.listFriendRequest(userId);
    }
    
    // ===== CONVERSATION SERVICE =====
    
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

    public List<Users> listParticipants(Integer conversationId) {
        return conversationService.listParticipants(conversationId);
    }

    public boolean updateConversationName(Integer conversationId, String newName, Integer requestUserId) {
        return conversationService.updateConversationName(conversationId, newName, requestUserId);
    }
    
    /**
     * Gửi tin nhắn với clientMessageId (Idempotent)
     * Đây là method chính để gửi tin nhắn
     */
    public Message sendMessageIdempotent(Integer conversationId, Integer senderId, 
                                        String content, String imageUrl, 
                                        Message.MessageType type, String clientMessageId) {
        return messageService.sendMessageIdempotent(conversationId, senderId, content, 
                                                    imageUrl, type, clientMessageId);
    }
    
    /**
     * Gửi file message với clientMessageId (Idempotent)
     */
    public Message sendFileMessageIdempotent(Integer conversationId, Integer senderId, 
                                            String fileName, String fileUrl, String clientMessageId) {
        return messageService.sendFileMessageIdempotent(conversationId, senderId, 
                                                       fileName, fileUrl, clientMessageId);
    }
    
    /**
     * Gửi image message với clientMessageId (Idempotent)
     */
    public Message sendImageMessageIdempotent(Integer conversationId, Integer senderId, 
                                             String caption, String imageUrl, String clientMessageId) {
        return messageService.sendImageMessageIdempotent(conversationId, senderId, 
                                                        caption, imageUrl, clientMessageId);
    }
    
    /**
     * Gửi audio message với clientMessageId (Idempotent)
     */
    public Message sendAudioMessageIdempotent(Integer conversationId, Integer senderId, 
                                             String duration, String audioUrl, String clientMessageId) {
        return messageService.sendAudioMessageIdempotent(conversationId, senderId, 
                                                        duration, audioUrl, clientMessageId);
    }
    
    // ===== MESSAGE SERVICE - LEGACY METHODS (backwards compatibility) =====
    
    /**
     * Gửi tin nhắn TEXT (legacy - tự động generate clientMessageId)
     */
    public Message sendMessage(Integer conversationId, Integer senderId, String content, String imageUrl) {
        return messageService.sendMessage(conversationId, senderId, content, imageUrl);
    }
    
    /**
     * Gửi tin nhắn với type cụ thể (legacy)
     */
    public Message sendMessageWithType(Integer conversationId, Integer senderId, 
                                      String content, String imageUrl, Message.MessageType type) {
        return messageService.sendMessageWithType(conversationId, senderId, content, imageUrl, type);
    }
    
    
    
    /**
     * Gửi file message (legacy)
     */
    public Message sendFileMessage(Integer conversationId, Integer senderId, String fileName) {
        return messageService.sendFileMessage(conversationId, senderId, fileName);
    }
    
    /**
     * Gửi image message (legacy)
     */
    public Message sendImageMessage(Integer conversationId, Integer senderId, 
                                   String caption, String imageUrl) {
        return messageService.sendImageMessage(conversationId, senderId, caption, imageUrl);
    }
    
    /**
     * Gửi audio message (legacy)
     */
    public Message sendAudioMessage(Integer conversationId, Integer senderId, 
                                   String duration, String audioUrl) {
        return messageService.sendAudioMessage(conversationId, senderId, duration, audioUrl);
    }
    
    // ===== MESSAGE SERVICE - QUERY METHODS =====

    public List<Message> listMessages(Integer conversationId) {
        return messageService.listMessages(conversationId);
    }
    
    public List<Message> listFileMessages(Integer conversationId) {
        return messageService.listFileMessages(conversationId);
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
    
    public Message getMessageByClientId(String clientMessageId) {
        return messageService.getMessageByClientId(clientMessageId);
    }
    
    public MessageService.MessageStats getMessageStats(Integer conversationId) {
        return messageService.getMessageStats(conversationId);
    }
    
    public void markMessageSentByClientId(String clientMessageId) {
    	messageService.markMessageSentByClientId(clientMessageId);
    }
    
    public void handleMessageNack(P2PMessageProtocol.Message msg) {
    	messageService.handleMessageNack(msg);
    }
}