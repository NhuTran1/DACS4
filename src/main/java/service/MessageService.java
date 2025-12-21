package service;

import java.time.LocalDateTime;
import java.util.List;

import dao.ConversationDao;
import dao.MessageDao;
import dao.UserDao;
import model.Conversation;
import model.Message;
import model.Users;

public class MessageService {

    private MessageDao messageDao = new MessageDao();
    private ConversationDao conversationDao = new ConversationDao();
    private UserDao userDao = new UserDao();

    // 1. Gửi tin nhắn TEXT (existing)
    public Message sendMessage(Integer conversationId, Integer senderId, String content, String imageUrl) {
        return sendMessageWithType(conversationId, senderId, content, imageUrl, Message.MessageType.TEXT);
    }

    // 2. Gửi tin nhắn với type cụ thể (NEW)
    public Message sendMessageWithType(Integer conversationId, Integer senderId, 
                                      String content, String imageUrl, Message.MessageType type) {
        // Kiểm tra conversation tồn tại
        Conversation conv = conversationDao.getConversation(conversationId);
        if (conv == null) {
            System.out.println("Conversation not found!");
            return null;
        }

        // Kiểm tra sender có nằm trong conversation không
        boolean isParticipant = conversationDao.listParticipants(conversationId)
                                               .stream()
                                               .anyMatch(u -> u.getId().equals(senderId));

        if (!isParticipant) {
            System.out.println("Sender not in conversation!");
            return null;
        }

        // Check sender có tồn tại không 
        Users sender = userDao.findById(senderId);
        if (sender == null) {
            System.out.println("Sender not found!");
            return null;
        }

        // Tạo entity Message
        Message msg = new Message();
        msg.setConversation(conv);
        msg.setSender(sender);
        msg.setMessageType(type);
        msg.setContent(content);
        msg.setImageUrl(imageUrl);
        msg.setCreatedAt(LocalDateTime.now());

        // Lưu DB
        messageDao.saveMessage(msg);

        // Tăng unread_count cho user khác
        messageDao.increaseUnreadCount(conversationId, senderId);

        // Cập nhật updated_at của conversation
        conv.setUpdatedAt(LocalDateTime.now());

        return msg;
    }

    // 3. Gửi file message (NEW)
    public Message sendFileMessage(Integer conversationId, Integer senderId, String fileName) {
        return sendMessageWithType(
            conversationId, 
            senderId, 
            "[File] " + fileName, 
            null, 
            Message.MessageType.FILE
        );
    }

    // 4. Gửi image message (NEW)
    public Message sendImageMessage(Integer conversationId, Integer senderId, 
                                   String caption, String imageUrl) {
        return sendMessageWithType(
            conversationId, 
            senderId, 
            caption != null ? caption : "[Image]", 
            imageUrl, 
            Message.MessageType.IMAGE
        );
    }

    // 5. Gửi audio message (NEW)
    public Message sendAudioMessage(Integer conversationId, Integer senderId, 
                                   String duration, String audioUrl) {
        return sendMessageWithType(
            conversationId, 
            senderId, 
            "[Audio] " + duration, 
            audioUrl, 
            Message.MessageType.AUDIO
        );
    }

    // 6. Lấy lịch sử tin nhắn
    public List<Message> listMessages(Integer conversationId) {
        return messageDao.listMessageInConversation(conversationId);
    }

    // 7. Lấy chỉ file messages trong conversation (NEW)
    public List<Message> listFileMessages(Integer conversationId) {
        return messageDao.listFileMessagesInConversation(conversationId);
    }

    // 8. Đánh dấu đã đọc 1 message
    public void markMessageSeen(Integer messageId, Integer userId) {
        messageDao.markMessageSeen(messageId, userId);
        // Đồng thời reset unread_count
        Message msg = getMessageById(messageId);
        if (msg != null) {
            messageDao.resetUnread(msg.getConversation().getId(), userId);
        }
    }

    // 9. Reset unread count khi user mở cuộc chat
    public void resetUnread(Integer conversationId, Integer userId) {
        messageDao.resetUnread(conversationId, userId);
    }
    
    // 10. Helper: lấy message theo id
    public Message getMessageById(Integer msgId) {
        return messageDao.getMessageById(msgId);
    }

    // 11. Xóa message (soft delete - NEW)
    public boolean deleteMessage(Integer messageId, Integer userId) {
        // TODO: Implement soft delete or hard delete with permission check
        return false;
    }

    // 12. Get message statistics (NEW)
    public MessageStats getMessageStats(Integer conversationId) {
        List<Message> messages = listMessages(conversationId);
        
        long textCount = messages.stream()
            .filter(m -> m.getMessageType() == Message.MessageType.TEXT)
            .count();
        
        long fileCount = messages.stream()
            .filter(m -> m.getMessageType() == Message.MessageType.FILE)
            .count();
        
        long imageCount = messages.stream()
            .filter(m -> m.getMessageType() == Message.MessageType.IMAGE)
            .count();
        
        long audioCount = messages.stream()
            .filter(m -> m.getMessageType() == Message.MessageType.AUDIO)
            .count();
        
        return new MessageStats(textCount, fileCount, imageCount, audioCount);
    }

    // Stats class
    public static class MessageStats {
        public long textCount;
        public long fileCount;
        public long imageCount;
        public long audioCount;

        public MessageStats(long textCount, long fileCount, long imageCount, long audioCount) {
            this.textCount = textCount;
            this.fileCount = fileCount;
            this.imageCount = imageCount;
            this.audioCount = audioCount;
        }

        public long getTotalCount() {
            return textCount + fileCount + imageCount + audioCount;
        }
    }
}