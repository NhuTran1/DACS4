package controller;

import model.Conversation;
import model.Message;
import model.Users;
import service.ChatService;
import network.p2p.P2PManager;
import network.p2p.PeerInfo;
import network.p2p.PeerDiscoveryService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * ChatController - Quản lý logic chat với Idempotent support
 * 
 * Trách nhiệm:
 * - Quản lý conversations
 * - Gửi/nhận chat messages với idempotent
 * - Typing indicators
 * - Message seen
 * - Friend list management
 * 
 * Phụ thuộc:
 * - ChatService (DB operations)
 * - P2PManager (P2P messaging)
 * 
 * KHÔNG phụ thuộc UI
 */
public class ChatController {
    private final ChatService chatService;
    private final P2PManager p2pManager;
    private final Integer currentUserId;
    private final FileTransferController fileTransferController;
    //private final AudioController audioController;
    
    // Callbacks for UI
    private MessageReceivedCallback messageReceivedCallback;
    private TypingCallback typingCallback;
    private ConnectionLostCallback connectionLostCallback;

    public interface MessageReceivedCallback {
        void onMessageReceived(Integer conversationId, Message message);
    }

    public interface TypingCallback {
        void onTyping(Integer conversationId, Integer userId, String userName);
    }

    public interface ConnectionLostCallback {
        void onConnectionLost(Integer userId);
    }

    public interface MessageCallback {
        void onSuccess(Message message);
        void onError(String error);
    }

    public ChatController(ChatService chatService, P2PManager p2pManager, Integer currentUserId) {
        this.chatService = chatService;
        this.p2pManager = p2pManager;
        this.currentUserId = currentUserId;
        
        // Initialize sub-controllers
        this.fileTransferController = new FileTransferController(p2pManager, chatService, currentUserId);
        //this.audioController = new AudioController(p2pManager, currentUserId);
        
        setupP2PListeners();
    }

    
    public FileTransferController getFileTransferController() {
        return fileTransferController;
    }

//    public AudioController getAudioController() {
//        return audioController;
//    }

    
    public Users getCurrentUser() {
        return chatService.getUserById(currentUserId);
    }

    public Users getUser(Integer userId) {
        return chatService.getUserById(userId);
    }

    public boolean isUserOnline(Integer userId) {
        PeerInfo peer = PeerDiscoveryService.getInstance().getPeer(userId);
        return peer != null;
    }

    // ===== CONVERSATION MANAGEMENT =====
    
    /**
     * Mở conversation với friend (tạo mới nếu chưa có)
     */
    public Conversation openConversation(Integer friendUserId) {
        Conversation existing = chatService.getDirectConversation(currentUserId, friendUserId);
        if (existing != null) {
            return existing;
        }
        
        return chatService.createDirectConversation(currentUserId, friendUserId);
    }

    /**
     * Lấy danh sách conversations
     */
    public List<Conversation> getConversations() {
        try {
            return chatService.listConversationsByUser(currentUserId);
        } catch (Exception e) {
            System.err.println("❌ Error loading conversations: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Lấy thông tin conversation
     */
    public Conversation getConversationInfo(Integer conversationId) {
        return chatService.getConversationById(conversationId);
    }

    /**
     * Lấy danh sách participants
     */
    public List<Users> getParticipants(Integer conversationId) {
        try {
            return chatService.listParticipants(conversationId);
        } catch (Exception e) {
            System.err.println("❌ Error getting participants: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ===== MESSAGE MANAGEMENT - IDEMPOTENT =====
    
    /**
     * Gửi tin nhắn text với clientMessageId (Idempotent)
     * Client tự generate clientMessageId và gửi kèm
     */
    public void sendMessageWithClientId(Integer conversationId, String content, 
                                       String clientMessageId, MessageCallback callback) {
        if (content == null || content.trim().isEmpty()) {
            if (callback != null) callback.onError("Cannot send empty message");
            return;
        }
        
        if (clientMessageId == null || clientMessageId.trim().isEmpty()) {
            if (callback != null) callback.onError("clientMessageId is required");
            return;
        }

        try {
            // 1. Validate conversation
            Conversation conversation = chatService.getConversationById(conversationId);
            if (conversation == null) {
                if (callback != null) callback.onError("Conversation not found");
                return;
            }

            // 2. Check if user is participant
            List<Users> participants = chatService.listParticipants(conversationId);
            boolean isParticipant = participants.stream()
                .anyMatch(u -> u.getId().equals(currentUserId));

            if (!isParticipant) {
                if (callback != null) callback.onError("You are not in this conversation");
                return;
            }

            // 3. Save to DB with idempotent (kiểm tra duplicate)
            Message savedMessage = chatService.sendMessageIdempotent(
                conversationId, 
                currentUserId, 
                content.trim(), 
                null,
                Message.MessageType.TEXT,
                clientMessageId
            );

            if (savedMessage == null) {
                if (callback != null) callback.onError("Failed to save message");
                return;
            }

            // 4. Send via P2P (P2P cũng sẽ include clientMessageId)
            boolean p2pSuccess = p2pManager.sendChatMessage(conversationId, content.trim(), clientMessageId);

            if (!p2pSuccess) {
                System.err.println("⚠️ Failed to send message to some peers");
            }

            // 5. Reset unread count
            chatService.resetUnread(conversationId, currentUserId);

            // 6. Notify success
            if (callback != null) callback.onSuccess(savedMessage);

        } catch (Exception e) {
            if (callback != null) callback.onError("Error: " + e.getMessage());
        }
    }
    
    /**
     * Gửi tin nhắn text (tự động generate clientMessageId)
     * Wrapper method cho backwards compatibility
     */
    public void sendMessage(Integer conversationId, String content, MessageCallback callback) {
        String clientMessageId = UUID.randomUUID().toString();
        sendMessageWithClientId(conversationId, content, clientMessageId, callback);
    }

    /**
     * Lấy danh sách messages
     */
    public List<Message> getMessages(Integer conversationId) {
        try {
            List<Message> messages = chatService.listMessages(conversationId);
            
            // Reset unread count
            chatService.resetUnread(conversationId, currentUserId);
            
            return messages;
        } catch (Exception e) {
            System.err.println("❌ Error loading messages: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Lưu file message vào DB (được gọi khi file transfer hoàn tất)
     * Idempotent với clientMessageId
     */
    public void saveFileMessageWithClientId(Integer conversationId, Integer senderId, 
                                           String fileName, String fileUrl, String clientMessageId,
                                           Consumer<Message> callback) {
        try {
            Message fileMsg = chatService.sendFileMessageIdempotent(
                conversationId,
                senderId,
                fileName,
                fileUrl,
                clientMessageId
            );
            
            if (callback != null) {
                callback.accept(fileMsg);
            }
        } catch (Exception e) {
            System.err.println("❌ Error saving file message: " + e.getMessage());
            if (callback != null) {
                callback.accept(null);
            }
        }
    }
    
    /**
     * Legacy wrapper
     */
    public void saveFileMessage(Integer conversationId, Integer senderId, 
                                String fileName, String fileUrl, 
                                Consumer<Message> callback) {
        String clientMessageId = UUID.randomUUID().toString();
        saveFileMessageWithClientId(conversationId, senderId, fileName, fileUrl, 
                                   clientMessageId, callback);
    }

    /**
     * Đánh dấu message đã đọc
     */
    public void markMessageAsSeen(Integer messageId) {
        try {
            Message message = chatService.getMessageById(messageId);
            if (message == null) return;

            chatService.markMessageSeen(messageId, currentUserId);

            // TODO: Send seen notification via P2P
            System.out.println("✅ Marked message " + messageId + " as seen");

        } catch (Exception e) {
            System.err.println("❌ Error marking message as seen: " + e.getMessage());
        }
    }

    // ===== TYPING INDICATORS =====
    
    public void sendTypingStart(Integer conversationId) {
        try {
            p2pManager.sendTypingStart(conversationId);
        } catch (Exception e) {
            System.err.println("❌ Error sending typing start: " + e.getMessage());
        }
    }

    public void sendTypingStop(Integer conversationId) {
        try {
            p2pManager.sendTypingStop(conversationId);
        } catch (Exception e) {
            System.err.println("❌ Error sending typing stop: " + e.getMessage());
        }
    }

    // ===== FRIEND MANAGEMENT =====
    
    /**
     * Lấy danh sách friends
     */
    public List<Users> getFriends() {
        return chatService.listFriends(currentUserId);
    }

    /**
     * Tìm kiếm friends
     */
    public void filterFriends(String keyword, Consumer<List<Users>> callback) {
        new Thread(() -> {
            try {
                if (keyword == null || keyword.isEmpty()) {
                    callback.accept(getFriends());
                    return;
                }
                
                List<Users> allFriends = getFriends();
                List<Users> filtered = allFriends.stream()
                    .filter(f -> f.getDisplayName().toLowerCase().contains(keyword.toLowerCase()) ||
                                f.getUsername().toLowerCase().contains(keyword.toLowerCase()))
                    .toList();
                
                callback.accept(filtered);
            } catch (Exception e) {
                System.err.println("❌ Error filtering friends: " + e.getMessage());
                callback.accept(new ArrayList<>());
            }
        }).start();
    }

    /**
     * Tìm kiếm users
     */
    public List<Users> searchUsers(String keyword) {
        try {
            return chatService.searchUser(keyword);
        } catch (Exception e) {
            System.err.println("❌ Error searching users: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ===== GROUP MANAGEMENT =====
    
    /**
     * Tạo group conversation
     */
    public void createGroupConversation(String groupName, String[] memberUsernames, 
                                       Consumer<OperationResult> callback) {
        new Thread(() -> {
            try {
                if (groupName == null || groupName.trim().isEmpty()) {
                    callback.accept(new OperationResult(false, "Group name is required"));
                    return;
                }

                if (memberUsernames == null || memberUsernames.length == 0) {
                    callback.accept(new OperationResult(false, "At least one member is required"));
                    return;
                }

                List<Integer> memberIds = new ArrayList<>();
                memberIds.add(currentUserId);

                for (String username : memberUsernames) {
                    String trimmed = username.trim();
                    if (trimmed.isEmpty()) continue;

                    Users user = chatService.getUserByUsername(trimmed);
                    if (user == null) {
                        callback.accept(new OperationResult(false, "User not found: " + trimmed));
                        return;
                    }
                    
                    if (!memberIds.contains(user.getId())) {
                        memberIds.add(user.getId());
                    }
                }

                if (memberIds.size() < 2) {
                    callback.accept(new OperationResult(false, "Group needs at least 2 members"));
                    return;
                }

                // TODO: Create group in database
                // Conversation group = chatService.createGroupConversation(groupName, memberIds);
                
                callback.accept(new OperationResult(false, "Group creation - Not implemented yet"));

            } catch (Exception e) {
                callback.accept(new OperationResult(false, "Error: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * Đổi tên group
     */
    public void updateGroupName(Integer conversationId, String newName, 
                               Consumer<OperationResult> callback) {
        new Thread(() -> {
            try {
                boolean success = chatService.updateConversationName(
                    conversationId, newName, currentUserId
                );
                
                if (success) {
                    callback.accept(new OperationResult(true, "Group name updated"));
                } else {
                    callback.accept(new OperationResult(false, "Failed to update group name"));
                }
            } catch (Exception e) {
                callback.accept(new OperationResult(false, "Error: " + e.getMessage()));
            }
        }).start();
    }

    // ===== CALLBACK SETTERS =====
    
    public void setMessageReceivedCallback(MessageReceivedCallback callback) {
        this.messageReceivedCallback = callback;
    }

    public void setTypingCallback(TypingCallback callback) {
        this.typingCallback = callback;
    }

    public void setConnectionLostCallback(ConnectionLostCallback callback) {
        this.connectionLostCallback = callback;
    }

    // ===== P2P LISTENERS =====
    
    private void setupP2PListeners() {
        p2pManager.setEventListener(new P2PManager.P2PEventListener() {
            @Override
            public void onChatMessageReceived(Integer conversationId, Message message) {
                if (messageReceivedCallback != null) {
                    messageReceivedCallback.onMessageReceived(conversationId, message);
                }
            }

            @Override
            public void onTypingReceived(Integer conversationId, Integer userId) {
                if (typingCallback != null) {
                    Users user = chatService.getUserById(userId);
                    String userName = user != null ? user.getDisplayName() : "User" + userId;
                    typingCallback.onTyping(conversationId, userId, userName);
                }
            }

            @Override
            public void onConnectionLost(Integer userId) {
                if (connectionLostCallback != null) {
                    connectionLostCallback.onConnectionLost(userId);
                }
            }

            // ===== FILE TRANSFER EVENTS - Delegate to FileTransferController =====
            
            @Override
            public void onFileRequested(Integer fromUser, String fileId, String fileName, Long fileSize) {
                fileTransferController.handleFileRequest(fromUser, fileId, fileName, fileSize);
            }

            @Override
            public void onFileAccepted(Integer fromUser, String fileId) {
                fileTransferController.handleFileAccepted(fromUser, fileId);
            }

            @Override
            public void onFileRejected(Integer fromUser, String fileId, String reason) {
                fileTransferController.handleFileRejected(fromUser, fileId, reason);
            }

            @Override
            public void onFileProgress(String fileId, int progress, boolean isUpload) {
                fileTransferController.handleFileProgress(fileId, progress, isUpload);
            }

            @Override
            public void onFileComplete(String fileId, java.io.File file, boolean isUpload) {
                fileTransferController.handleFileComplete(fileId, file, isUpload);
            }

            @Override
            public void onFileCanceled(String fileId, boolean isUpload) {
                fileTransferController.handleFileCanceled(fileId, isUpload);
            }

            @Override
            public void onFileError(String fileId, String error) {
                fileTransferController.handleFileError(fileId, error);
            }

            // ===== AUDIO CALL EVENTS - Delegate to AudioController =====
            
//            @Override
//            public void onAudioCallRequested(Integer fromUser, String callId) {
//                audioController.handleCallRequest(fromUser, callId);
//            }
//
//            @Override
//            public void onAudioCallAccepted(Integer fromUser, String callId) {
//                audioController.handleCallAccepted(fromUser, callId);
//            }
//
//            @Override
//            public void onAudioCallRejected(Integer fromUser, String callId, String reason) {
//                audioController.handleCallRejected(fromUser, callId, reason);
//            }
//
//            @Override
//            public void onAudioCallStarted(String callId) {
//                audioController.handleCallStarted(callId);
//            }
//
//            @Override
//            public void onAudioCallEnded(String callId) {
//                audioController.handleCallEnded(callId);
//            }
//
//            @Override
//            public void onAudioCallError(String callId, String error) {
//                audioController.handleCallError(callId, error);
//            }
        });
    }

    // ===== RESULT CLASS =====
    
    public static class OperationResult {
        public boolean success;
        public String message;
        public Object data;

        public OperationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public OperationResult(boolean success, String message, Object data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }
    }

    // ===== CLEANUP =====
    
    public void shutdown() {
        try {
            fileTransferController.shutdown();
            //audioController.shutdown();
            p2pManager.shutdown();
            System.out.println("✅ ChatController shutdown complete");
        } catch (Exception e) {
            System.err.println("❌ Error during shutdown: " + e.getMessage());
        }
    }
}