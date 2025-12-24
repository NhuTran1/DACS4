package service;

import dao.MessageDao;
import model.Message;
import model.Message.MessageStatus;
import network.p2p.P2PManager;

import java.util.List;
import java.util.concurrent.*;

/**
 * MessageRetryService - Tự động retry gửi lại pending/failed messages
 */
public class MessageRetryService {
    
    private final MessageDao messageDao;
    private final P2PManager p2pManager;
    private final Integer userId;
    private final ScheduledExecutorService scheduler;
    private final ChatService chatService;
    
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_INTERVAL_SECONDS = 30;
    
    public MessageRetryService(MessageDao messageDao, P2PManager p2pManager, 
                              Integer userId, ChatService chatService) {
        this.messageDao = messageDao;
        this.p2pManager = p2pManager;
        this.userId = userId;
        this.chatService = chatService;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    /**
     * Start automatic retry service
     */
    public void start() {
        // Initial retry on startup
        retryPendingMessages();
        
        // Schedule periodic retry
        scheduler.scheduleWithFixedDelay(
            this::retryPendingMessages,
            RETRY_INTERVAL_SECONDS,
            RETRY_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        
        System.out.println("✅ Message Retry Service started");
    }
    
    /**
     * Stop retry service
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        System.out.println("✅ Message Retry Service shutdown");
    }
    
    /**
     * Retry all pending messages
     */
    public void retryPendingMessages() {
        try {
            // Get pending messages
            List<Message> pending = messageDao.getPendingMessages(userId);
            
            if (!pending.isEmpty()) {
                System.out.println("🔄 Retrying " + pending.size() + " pending messages");
            }
            
            for (Message message : pending) {
                retryMessage(message);
            }
            
            // Also retry failed messages that haven't exceeded max retries
            List<Message> failed = messageDao.getRetryableFailedMessages(userId);
            
            if (!failed.isEmpty()) {
                System.out.println("🔄 Retrying " + failed.size() + " failed messages");
            }
            
            for (Message message : failed) {
                retryMessage(message);
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error during message retry: " + e.getMessage());
        }
    }
    
    /**
     * Retry specific message
     */
    private void retryMessage(Message message) {
        if (message.getRetryCount() >= MAX_RETRIES) {
            System.out.println("⚠️ Message " + message.getId() + 
                             " exceeded max retries, marking as FAILED");
            messageDao.updateMessageStatus(message.getId(), MessageStatus.FAILED);
            return;
        }
        
        try {
            // Get conversation participants
            List<model.Users> participants = chatService.listParticipants(
                message.getConversation().getId()
            );
            
            if (participants == null || participants.isEmpty()) {
                System.err.println("❌ No participants found for conversation " + 
                                 message.getConversation().getId());
                return;
            }
            
            // Send to all participants except sender
            boolean success = true;
            for (model.Users user : participants) {
                if (user.getId().equals(userId)) continue;
                
                // Check if peer is online
                if (!isPeerOnline(user.getId())) {
                    success = false;
                    System.out.println("⚠️ Peer " + user.getId() + " is offline, will retry later");
                    continue;
                }
                
                // Send P2P message
                boolean sent = sendP2PMessage(message);
                if (!sent) {
                    success = false;
                }
            }
            
            // Update status
            if (success) {
                messageDao.updateMessageStatus(message.getId(), MessageStatus.SENT);
                System.out.println("✅ Message " + message.getId() + " sent successfully");
            } else {
                messageDao.incrementRetryCount(message.getId());
                System.out.println("⚠️ Message " + message.getId() + 
                                 " retry failed (attempt " + (message.getRetryCount() + 1) + ")");
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error retrying message " + message.getId() + ": " + e.getMessage());
            messageDao.incrementRetryCount(message.getId());
        }
    }
    
    /**
     * Send message via P2P
     */
    private boolean sendP2PMessage(Message message) {
        try {
            String content = message.getContent();
            String clientMessageId = message.getClientMessageId();
            Integer conversationId = message.getConversation().getId();
            
            return p2pManager.sendChatMessage(conversationId, content, clientMessageId);
            
        } catch (Exception e) {
            System.err.println("❌ P2P send failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if peer is online
     */
    private boolean isPeerOnline(Integer peerId) {
        return network.p2p.PeerDiscoveryService.getInstance().getPeer(peerId) != null;
    }
    
    /**
     * Manual retry for specific message
     */
    public boolean retryMessageManually(Integer messageId) {
        Message message = messageDao.getMessageById(messageId);
        if (message == null) {
            System.err.println("❌ Message not found: " + messageId);
            return false;
        }
        
        if (!message.getSender().getId().equals(userId)) {
            System.err.println("❌ Cannot retry message from other user");
            return false;
        }
        
        retryMessage(message);
        return true;
    }
}