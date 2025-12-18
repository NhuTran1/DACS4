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

    // 1. Gửi tin nhắn
    public Message sendMessage(Integer conversationId, Integer senderId, String content, String imageUrl) {

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

        if (!isParticipant) return null; 

        //Check sender co ton tại ko 
        Users sender = userDao.findById(senderId);
        if (sender == null) {
            System.out.println("Sender not found!");
            return null;
        }

        // Tạo entity Message
        Message msg = new Message();
        msg.setConversation(conv);
        msg.setSender(sender);
        msg.setContent(content);
        msg.setImageUrl(imageUrl);
        msg.setCreatedAt(LocalDateTime.now());

        // Lưu DB
        messageDao.saveMessage(msg);

     // Tăng unread_count cho user khác
        messageDao.increaseUnreadCount(conversationId, senderId);

        //cập nhật updated_at của conversation
        conv.setUpdatedAt(LocalDateTime.now());

        return msg;
    }

    // 2. Lấy lịch sử tin nhắn
    public List<Message> listMessages(Integer conversationId) {
        return messageDao.listMessageInConversation(conversationId);
    }

    // 3. Đánh dấu đã đọc 1 message
    public void markMessageSeen(Integer messageId, Integer userId) {
        messageDao.markMessageSeen(messageId, userId);
        // đồng thời reset unread_count
        Message msg = getMessageById(messageId);
        if (msg != null) {
            messageDao.resetUnread(msg.getConversation().getId(), userId);
        }
    }

    // 4. Reset unread count khi user mở cuộc chat
    public void resetUnread(Integer conversationId, Integer userId) {
        messageDao.resetUnread(conversationId, userId);
    }
    
    // 5. Helper: lấy message theo id
    public Message getMessageById(Integer msgId) {
        // Vì bạn không có MessageDao.findById(), nên dùng session tạm
        try (var session = config.HibernateUtil.getSessionFactory().openSession()) {
            return session.get(Message.class, msgId);
        }
    }
}
