package service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import org.hibernate.Transaction;

import dao.ConversationDao;
import dao.FileAttachmentDao;
import dao.MessageDao;
import dao.UserDao;
import jakarta.transaction.Transactional;
import model.Conversation;
import model.FileAttachment;
import model.Message;
import model.Users;
import model.Message.MessageStatus;
import protocol.P2PMessageProtocol;

public class MessageService {

    private MessageDao messageDao = new MessageDao();
    private ConversationDao conversationDao = new ConversationDao();
    private UserDao userDao = new UserDao();
    private FileAttachmentDao fileAttachmentDao = new FileAttachmentDao();
    private SessionFactory sessionFactory;

    // ===== IDEMPOTENT MESSAGE SENDING =====
    
    /**
     * Gửi tin nhắn với clientMessageId (Idempotent)
     * Nếu message với clientMessageId đã tồn tại, trả về message đó
     */
    public Message sendMessageIdempotent(Integer conversationId, Integer senderId, 
                                         String content, String imageUrl, 
                                         Message.MessageType type, String clientMessageId) {
        // Validate clientMessageId
        if (clientMessageId == null || clientMessageId.isEmpty()) {
            throw new IllegalArgumentException("clientMessageId is required for idempotent send");
        }
        
        // Kiểm tra message đã tồn tại chưa
        Message existing = messageDao.findByClientMessageId(clientMessageId);
        if (existing != null) {
            System.out.println("⚠️ Message already exists (idempotent): " + clientMessageId);
            return existing;
        }
        
        // Kiểm tra conversation tồn tại
        Conversation conv = conversationDao.getConversation(conversationId);
        if (conv == null) {
            System.out.println("❌ Conversation not found!");
            return null;
        }

        // Kiểm tra sender có nằm trong conversation không
        boolean isParticipant = conversationDao.listParticipants(conversationId)
                                               .stream()
                                               .anyMatch(u -> u.getId().equals(senderId));

        if (!isParticipant) {
            System.out.println("❌ Sender not in conversation!");
            return null;
        }

        // Kiểm tra sender có tồn tại không 
        Users sender = userDao.findById(senderId);
        if (sender == null) {
            System.out.println("❌ Sender not found!");
            return null;
        }

        // Tạo entity Message
        Message msg = new Message();
        msg.setConversation(conv);
        msg.setSender(sender);
        msg.setMessageType(type != null ? type : Message.MessageType.TEXT);
        msg.setContent(content);
        msg.setImageUrl(imageUrl);
        msg.setClientMessageId(clientMessageId);
        msg.setCreatedAt(LocalDateTime.now());

        // Lưu DB với idempotent
        Message savedMsg = messageDao.saveMessageIdempotent(msg);
        
        if (savedMsg != null && savedMsg.getId() != null) {
            // Chỉ tăng unread count nếu là message mới (không phải duplicate)
            if (savedMsg.getId().equals(msg.getId())) {
                messageDao.increaseUnreadCount(conversationId, senderId);
            }
            
            // Cập nhật updated_at của conversation
            conv.setUpdatedAt(LocalDateTime.now());
        }

        return savedMsg;
    }

    // ===== LEGACY METHODS (backwards compatibility) =====
    
    /**
     * Gửi tin nhắn TEXT (legacy - tự động generate clientMessageId)
     */
    public Message sendMessage(Integer conversationId, Integer senderId, String content, String imageUrl) {
        String clientMessageId = UUID.randomUUID().toString();
        return sendMessageIdempotent(conversationId, senderId, content, imageUrl, 
                                     Message.MessageType.TEXT, clientMessageId);
    }

    /**
     * Gửi tin nhắn với type cụ thể (legacy - tự động generate clientMessageId)
     */
    public Message sendMessageWithType(Integer conversationId, Integer senderId, 
                                      String content, String imageUrl, Message.MessageType type) {
        String clientMessageId = UUID.randomUUID().toString();
        return sendMessageIdempotent(conversationId, senderId, content, imageUrl, 
                                     type, clientMessageId);
    }
    
    
    
    public void markMessageSentByClientId(String clientMessageId) {
        messageDao.updateMessageStatusByClientId(
            clientMessageId,
            MessageStatus.SENT
        );
    }
    
    public void confirmMessageSeenAck(Integer messageId, Integer viewerId) {
        System.out.println("✅ Message " + messageId + " seen by user " + viewerId);

    }
    
    
    public void handleMessageNack(P2PMessageProtocol.Message msg) {
		String clientMessageId = (String) msg.data.get("clientMessageId");
        messageDao.updateMessageStatusByClientId(
            clientMessageId,
            MessageStatus.FAILED
        );
	}
    
    public void markMessageFailedByClientId(String clientMessageId) {
        messageDao.updateMessageStatusByClientId(
            clientMessageId,
            MessageStatus.FAILED
        );
    }

    // ===== FILE MESSAGE METHODS =====
    
    /**
     * Gửi file message với clientMessageId (Idempotent)
     */
    public Message sendFileMessageIdempotent(Integer conversationId, Integer senderId, 
                                            String fileName, String fileUrl, String clientMessageId) {
        return sendMessageIdempotent(
            conversationId, 
            senderId, 
            "[File] " + fileName, 
            fileUrl, 
            Message.MessageType.FILE,
            clientMessageId
        );
    }
    
    /**
     * Gửi file message (legacy - tự động generate clientMessageId)
     */
    public Message sendFileMessage(Integer conversationId, Integer senderId, String fileName) {
        String clientMessageId = UUID.randomUUID().toString();
        return sendFileMessageIdempotent(conversationId, senderId, fileName, null, clientMessageId);
    }

    /**
     * Gửi image message với clientMessageId (Idempotent)
     */
    public Message sendImageMessageIdempotent(Integer conversationId, Integer senderId, 
                                             String caption, String imageUrl, String clientMessageId) {
        return sendMessageIdempotent(
            conversationId, 
            senderId, 
            caption != null ? caption : "[Image]", 
            imageUrl, 
            Message.MessageType.IMAGE,
            clientMessageId
        );
    }

    /**
     * Gửi image message (legacy)
     */
    public Message sendImageMessage(Integer conversationId, Integer senderId, 
                                   String caption, String imageUrl) {
        String clientMessageId = UUID.randomUUID().toString();
        return sendImageMessageIdempotent(conversationId, senderId, caption, imageUrl, clientMessageId);
    }

    /**
     * Gửi audio message với clientMessageId (Idempotent)
     */
    public Message sendAudioMessageIdempotent(Integer conversationId, Integer senderId, 
                                             String duration, String audioUrl, String clientMessageId) {
        return sendMessageIdempotent(
            conversationId, 
            senderId, 
            "[Audio] " + duration, 
            audioUrl, 
            Message.MessageType.AUDIO,
            clientMessageId
        );
    }

    /**
     * Gửi audio message (legacy)
     */
    public Message sendAudioMessage(Integer conversationId, Integer senderId, 
                                   String duration, String audioUrl) {
        String clientMessageId = UUID.randomUUID().toString();
        return sendAudioMessageIdempotent(conversationId, senderId, duration, audioUrl, clientMessageId);
    }

    // ===== QUERY METHODS =====
    
    /**
     * Lấy lịch sử tin nhắn
     */
    public List<Message> listMessages(Integer conversationId) {
        return messageDao.listMessageInConversation(conversationId);
    }

    /**
     * Lấy chỉ file messages trong conversation
     */
    public List<Message> listFileMessages(Integer conversationId) {
        return messageDao.listFileMessagesInConversation(conversationId);
    }

    /**
     * Đánh dấu đã đọc 1 message
     */
    public void markMessageSeen(Integer messageId, Integer userId) {
        messageDao.markMessageSeen(messageId, userId);
        
        // Đồng thời reset unread_count
        Message msg = getMessageById(messageId);
        if (msg != null) {
            messageDao.resetUnread(msg.getConversation().getId(), userId);
        }
    }
    
    public Message findMessageByFileUrl(Integer conversationId, String fileUrl) {
        return messageDao.findMessageByFileUrl(conversationId, fileUrl);
    }

    /**
     * Reset unread count khi user mở cuộc chat
     */
    public void resetUnread(Integer conversationId, Integer userId) {
        messageDao.resetUnread(conversationId, userId);
    }
    
    /**
     * Helper: lấy message theo id
     */
    public Message getMessageById(Integer msgId) {
        return messageDao.getMessageById(msgId);
    }
    
    /**
     * Tìm message theo clientMessageId
     */
    public Message getMessageByClientId(String clientMessageId) {
        return messageDao.findByClientMessageId(clientMessageId);
    }

    /**
     * Xóa message (soft delete)
     */
    public boolean deleteMessage(Integer messageId, Integer userId) {
        // TODO: Implement soft delete or hard delete with permission check
        return false;
    }
    
    public Message findByClientMessageId(String clientMessageId) {
        return messageDao.findByClientMessageId(clientMessageId);
    }


    /**
     * Get message statistics
     */
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
    
    public Message createFileMessage(
            Integer conversationId,
            Integer senderId,
            String fileName,
            String fileUrl
    ) {
        Message msg = new Message();
        msg.setConversation(conversationDao.getConversation(conversationId));
        msg.setSender(userDao.findById(senderId));
        msg.setMessageType(Message.MessageType.FILE);
        msg.setContent("[File] " + fileName);
        msg.setImageUrl(fileUrl);
        msg.setStatus(Message.MessageStatus.SENT);

        messageDao.saveMessage(msg); // ⚠️ BẮT BUỘC
        return msg;
    }

    public FileAttachment createFileAttachment(
            Message msg,
            Integer senderId,
            File file,
            String fileId
    ) throws IOException {
        FileAttachment fa = new FileAttachment();
        fa.setMessage(msg);                 // ⭐ GẮN message
        fa.setSender(userDao.findById(senderId));
        fa.setFileId(fileId);               // UUID / P2P id
        fa.setFileName(file.getName());
        fa.setFilePath(file.getAbsolutePath());
        fa.setFileSize(file.length());
        fa.setMimeType(Files.probeContentType(file.toPath()));
        fa.setStatus(FileAttachment.FileStatus.UPLOADING);

        fileAttachmentDao.save(fa);
        return fa;
    }
    
    public Message createFileMessageWithAttachment(
    	    Integer conversationId,
    	    Integer senderId,
    	    File file,
    	    String fileId
    	) throws IOException {
    	    
    	    System.out.println("💾 [MessageService] Creating file message for receiver...");
    	    System.out.println("   - File: " + file.getName());
    	    System.out.println("   - Path: " + file.getAbsolutePath());
    	    
    	    // ✅ FIX: Không dùng sessionFactory, dùng DAO methods
    	    
    	    // 1️⃣ Validate
    	    if (!file.exists()) {
    	        throw new IOException("File does not exist: " + file.getAbsolutePath());
    	    }

    	    // 2️⃣ Create Message
    	    Message msg = new Message();
    	    msg.setConversation(conversationDao.getConversation(conversationId));
    	    msg.setSender(userDao.findById(senderId));
    	    msg.setMessageType(Message.MessageType.FILE);
    	    msg.setContent("[File] " + file.getName());
    	    msg.setStatus(Message.MessageStatus.SENT); // ⭐ Receiver nhận được = SENT
    	    msg.setCreatedAt(LocalDateTime.now());
    	    msg.setUpdatedAt(LocalDateTime.now());

    	    messageDao.saveMessage(msg);

    	    if (msg.getId() == null) {
    	        throw new IOException("Failed to save message");
    	    }
    	    
    	    System.out.println("✅ Receiver message saved with ID: " + msg.getId());

    	    // 3️⃣ Create FileAttachment
    	    FileAttachment fa = new FileAttachment();
    	    fa.setMessage(msg);
    	    fa.setSender(msg.getSender());
    	    fa.setFileId(fileId);
    	    fa.setFileName(file.getName());
    	    fa.setFilePath(file.getAbsolutePath());
    	    fa.setFileSize(file.length());
    	    
    	    try {
    	        String mimeType = Files.probeContentType(file.toPath());
    	        fa.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
    	    } catch (IOException e) {
    	        fa.setMimeType("application/octet-stream");
    	    }
    	    
    	    fa.setStatus(FileAttachment.FileStatus.COMPLETED); // ⭐ COMPLETED khi receiver nhận xong
    	    fa.setCreatedAt(LocalDateTime.now());
    	    fa.setUpdatedAt(LocalDateTime.now());

    	    FileAttachment saved = fileAttachmentDao.save(fa);
    	    
    	    if (saved == null) {
    	        throw new IOException("Failed to save file attachment");
    	    }
    	    
    	    System.out.println("✅ Receiver FileAttachment saved with ID: " + saved.getId());

    	    return msg;
    	}

    //chi lưu thôi ko đụng đến P2P
 // ===== FIX: saveFileMessageAndAttachment =====
    public FileAttachment saveFileMessageAndAttachment(
            Integer conversationId,
            Integer senderId,
            File file,
            String fileId,
            String clientMessageId
    ) throws Exception {

        System.out.println("💾 [MessageService] Saving file message to DB...");
        System.out.println("   - File: " + file.getName());
        System.out.println("   - FileId: " + fileId);
        System.out.println("   - ClientMessageId: " + clientMessageId);

        // ✅ FIX: Không dùng sessionFactory, dùng DAO methods
        
        // 1️⃣ Validate inputs
        if (conversationId == null || senderId == null || file == null || 
            fileId == null || clientMessageId == null) {
            throw new IllegalArgumentException("Missing required parameters");
        }
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist: " + file.getAbsolutePath());
        }

        // 2️⃣ Create Message
        Message msg = new Message();
        msg.setConversation(conversationDao.getConversation(conversationId));
        msg.setSender(userDao.findById(senderId));
        msg.setMessageType(Message.MessageType.FILE);
        msg.setContent("[File] " + file.getName());
        msg.setClientMessageId(clientMessageId);
        msg.setStatus(Message.MessageStatus.PENDING); // ⭐ Pending khi mới tạo
        msg.setCreatedAt(LocalDateTime.now());
        msg.setUpdatedAt(LocalDateTime.now());

        // ✅ Save message first
        messageDao.saveMessage(msg);

        if (msg.getId() == null) {
            throw new Exception("Failed to save message - ID is null");
        }
        
        System.out.println("✅ Message saved with ID: " + msg.getId());

        // 3️⃣ Create FileAttachment
        FileAttachment fa = new FileAttachment();
        fa.setMessage(msg);
        fa.setSender(msg.getSender());
        fa.setFileId(fileId);
        fa.setFileName(file.getName());
        fa.setFilePath(file.getAbsolutePath());
        fa.setFileSize(file.length());
        
        try {
            String mimeType = Files.probeContentType(file.toPath());
            fa.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
        } catch (IOException e) {
            fa.setMimeType("application/octet-stream");
        }
        
        fa.setStatus(FileAttachment.FileStatus.UPLOADING); // ⭐ UPLOADING khi sender bắt đầu gửi
        fa.setCreatedAt(LocalDateTime.now());
        fa.setUpdatedAt(LocalDateTime.now());

        // ✅ Save file attachment
        FileAttachment saved = fileAttachmentDao.save(fa);
        
        if (saved == null || saved.getId() == null) {
            throw new Exception("Failed to save file attachment");
        }
        
        System.out.println("✅ FileAttachment saved with ID: " + saved.getId());

        return saved;
    }
    
    public List<Message> reloadConversationMessages(Integer conversationId) {
        return messageDao.listMessagesWithAttachments(conversationId);
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