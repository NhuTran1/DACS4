package network.p2p;

import protocol.P2PMessageProtocol;
import service.ChatService;
import model.Message;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import controller.ChatController;

/**
 * P2PManager - Router with Idempotent support and simplified file transfer
 */
public class P2PManager implements PeerConnection.P2PMessageHandler {
    private final Integer localUserId;
    private final Map<Integer, PeerConnection> activeConnections = new ConcurrentHashMap<>();
    private final ChatService chatService;
    private final PeerDiscoveryService discoveryService;
    private final ChatController chatController;
    
    // Managers
    private final FileTransferManager fileTransferManager;
    private final AudioCallManager audioCallManager;
    
    // Callback cho UI
    private P2PEventListener eventListener;

    public interface P2PEventListener {
        void onChatMessageReceived(Integer conversationId, Message message);
        void onTypingReceived(Integer conversationId, Integer userId);
        
        // File transfer events - simplified
        void onFileProgress(String fileId, int progress, boolean isUpload);
        void onFileComplete(String fileId, File file, boolean isUpload);
        void onFileCanceled(String fileId, boolean isUpload);
        void onFileError(String fileId, String error);
        
        // Audio call events
//        void onAudioCallRequested(Integer fromUser, String callId);
//        void onAudioCallAccepted(Integer fromUser, String callId);
//        void onAudioCallRejected(Integer fromUser, String callId, String reason);
//        void onAudioCallStarted(String callId);
//        void onAudioCallEnded(String callId);
//        void onAudioCallError(String callId, String error);
        
        void onConnectionLost(Integer userId);
    }

    public P2PManager(Integer localUserId, ChatService chatService, ChatController chatController) {
        this.localUserId = localUserId;
        this.chatService = chatService;
        this.discoveryService = PeerDiscoveryService.getInstance();
        this.chatController = chatController;
        
        // Initialize managers
        this.fileTransferManager = new FileTransferManager(this);
        this.audioCallManager = new AudioCallManager(this);
        
        setupFileTransferListener();
        setupAudioCallListener();
    }

    // ===== CONNECTION MANAGEMENT =====

    public boolean connectToPeer(Integer userId) {
        if (activeConnections.containsKey(userId)) {
            return true;
        }

        PeerInfo peer = discoveryService.getPeer(userId);
        if (peer == null) {
            System.err.println("❌ Peer not found: " + userId);
            return false;
        }

        PeerConnection conn = new PeerConnection(peer);
        conn.setMessageHandler(this);

        if (conn.connectTcp(5000)) {
            activeConnections.put(userId, conn);
            System.out.println("✅ Connected to peer: " + userId);
            return true;
        }

        return false;
    }

    public void disconnectPeer(Integer userId) {
        PeerConnection conn = activeConnections.remove(userId);
        if (conn != null) {
            conn.closeAll();
            System.out.println("👋 Disconnected from peer: " + userId);
        }
    }

    private PeerConnection getOrCreateConnection(Integer userId) {
        PeerConnection conn = activeConnections.get(userId);
        if (conn != null && conn.isTcpConnected()) {
            return conn;
        }

        if (connectToPeer(userId)) {
            return activeConnections.get(userId);
        }

        return null;
    }

    public PeerConnection getConnection(Integer userId) {
        return activeConnections.get(userId);
    }

    public Integer getLocalUserId() {
        return localUserId;
    }

    // ===== CHAT MESSAGES - IDEMPOTENT =====

    public boolean sendChatMessage(Integer conversationId, String content, String clientMessageId) {
        var participants = chatService.listParticipants(conversationId);
        if (participants == null || participants.isEmpty()) return false;

        String json = P2PMessageProtocol.buildChatMessage(localUserId, conversationId, content, clientMessageId);
        boolean success = true;

        for (var user : participants) {
            if (user.getId().equals(localUserId)) continue;

            PeerConnection conn = getOrCreateConnection(user.getId());
            if (conn != null) {
                if (!conn.sendTcp(json)) {
                    success = false;
                    System.err.println("❌ Failed to send message to peer: " + user.getId());
                }
            } else {
                success = false;
            }
        }

        return success;
    }
    
    /**
     * Legacy wrapper (tự động generate clientMessageId)
     */
    public boolean sendChatMessage(Integer conversationId, String content) {
        String clientMessageId = UUID.randomUUID().toString();
        return sendChatMessage(conversationId, content, clientMessageId);
    }

    public void sendTypingStart(Integer conversationId) {
        var participants = chatService.listParticipants(conversationId);
        if (participants == null) return;

        String json = P2PMessageProtocol.buildTypingStart(localUserId, conversationId);

        for (var user : participants) {
            if (user.getId().equals(localUserId)) continue;
            
            PeerConnection conn = activeConnections.get(user.getId());
            if (conn != null) {
                conn.sendTcp(json);
            }
        }
    }

    public void sendTypingStop(Integer conversationId) {
        var participants = chatService.listParticipants(conversationId);
        if (participants == null) return;

        String json = P2PMessageProtocol.buildTypingStop(localUserId, conversationId);

        for (var user : participants) {
            if (user.getId().equals(localUserId)) continue;

            PeerConnection conn = activeConnections.get(user.getId());
            if (conn != null) {
                conn.sendTcp(json);
            }
        }
    }

    // ===== FILE TRANSFER API - SIMPLIFIED =====

    /**
     * Gửi file tới user (trực tiếp, không cần request/accept)
     */
    public String sendFile(Integer toUserId, File file, Integer conversationId, 
                          String clientMessageId,  String fileId) throws Exception {
        return fileTransferManager.sendFile(toUserId, file, conversationId, clientMessageId, fileId);
    }

    /**
     * Hủy việc gửi file
     */
    public void cancelFileTransfer(String fileId, Integer toUserId) {
        fileTransferManager.cancelOutgoingTransfer(fileId, toUserId);
    }

    // ===== AUDIO CALL API =====

    /**
     * Bắt đầu voice call
     */
    public String startAudioCall(Integer toUserId) throws Exception {
        return audioCallManager.startCall(toUserId);
    }

    /**
     * Accept voice call
     */
    public void acceptAudioCall(String callId) {
        audioCallManager.acceptCall(callId);
    }

    /**
     * Reject voice call
     */
    public void rejectAudioCall(String callId, String reason) {
        audioCallManager.rejectCall(callId, reason);
    }

    /**
     * End voice call
     */
    public void endAudioCall(String callId) {
        audioCallManager.endCall(callId);
    }

    // ===== MESSAGE HANDLER =====

    @Override
    public void onMessageReceived(P2PMessageProtocol.Message msg) {
        if (msg == null || msg.type == null) return;

        try {
            P2PMessageProtocol.MessageType type = P2PMessageProtocol.MessageType.valueOf(msg.type);

            switch (type) {
                case CHAT_MESSAGE -> handleChatMessage(msg);
                case TYPING_START -> handleTypingStart(msg);
                case TYPING_STOP -> handleTypingStop(msg);
                
                case MESSAGE_ACK -> handleMessageAck(msg);
                case MESSAGE_SEEN_ACK -> handleMessageSeenAck(msg);
                
                // File transfer - Simplified (XÓA FILE_ACK, FILE_NACK)
                case FILE_CHUNK -> handleFileChunk(msg);
                case FILE_COMPLETE -> handleFileComplete(msg);
                case FILE_CANCEL -> handleFileCancel(msg);
                // ❌ XÓA: case FILE_ACK -> handleFileAck(msg);
                // ❌ XÓA: case FILE_NACK -> handleFileNack(msg);
                
                // Audio call
                case AUDIO_REQUEST -> audioCallManager.handleCallRequest(msg);
                case AUDIO_ACCEPT -> audioCallManager.handleCallAccept(msg);
                case AUDIO_REJECT -> handleAudioReject(msg);
                case AUDIO_END -> audioCallManager.handleCallEnd(
                    (String) msg.data.get("callId"));
                
                case MESSAGE_SEEN -> handleMessageSeen(msg);
                
                default -> System.out.println("⚠️ Unhandled message type: " + type);
            }
        } catch (Exception e) {
            System.err.println("❌ Error handling message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onConnectionLost() {
        if (eventListener != null) {
            activeConnections.entrySet().removeIf(entry -> {
                if (!entry.getValue().isTcpConnected()) {
                    eventListener.onConnectionLost(entry.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    // ===== HANDLE INCOMING MESSAGES - WITH IDEMPOTENT =====

    private void handleChatMessage(P2PMessageProtocol.Message msg) {
        if (eventListener == null) return;

        String content = (String) msg.data.get("content");
        String clientMessageId = (String) msg.data.get("clientMessageId");
        
        // Sử dụng sendMessageIdempotent để tránh duplicate
        Message savedMsg = chatService.sendMessageIdempotent(
            msg.conversationId, 
            msg.from, 
            content, 
            null,
            Message.MessageType.TEXT,
            clientMessageId != null ? clientMessageId : UUID.randomUUID().toString()
        );

        if (savedMsg != null) {
            // ✅ gửi ACK về sender
            PeerConnection conn = getOrCreateConnection(msg.from);
            if (conn != null) {
                String ack = P2PMessageProtocol.buildMessageAck(
                    localUserId,
                    msg.from,
                    clientMessageId
                );
                conn.sendTcp(ack);
            }

            eventListener.onChatMessageReceived(msg.conversationId, savedMsg);
        }
    }
    
    private void handleMessageAck(P2PMessageProtocol.Message msg) {
        String clientMessageId = (String) msg.data.get("clientMessageId");
        if (clientMessageId != null) {
            chatService.markMessageSentByClientId(clientMessageId);
        }
    }


    private void handleMessageNack(P2PMessageProtocol.Message msg) {
        String clientMessageId = (String) msg.data.get("clientMessageId");

        if (clientMessageId != null) {
            chatService.markMessageFailedByClientId(clientMessageId);
        }
    }

    
    private void handleMessageSeenAck(P2PMessageProtocol.Message msg) {
        Number messageId = (Number) msg.data.get("messageId");
        Integer fromUser = msg.from;

        if (messageId != null && fromUser != null) {
            chatService.confirmMessageSeenAck(
                messageId.intValue(),
                fromUser
            );
        }
    }

    private void handleTypingStart(P2PMessageProtocol.Message msg) {
        if (eventListener != null) {
            eventListener.onTypingReceived(msg.conversationId, msg.from);
        }
    }

    private void handleTypingStop(P2PMessageProtocol.Message msg) {
        // UI có thể xử lý việc ẩn typing indicator
    }

    private void handleFileChunk(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        Number chunkIndex = (Number) msg.data.get("chunkIndex");
        Number totalChunks = (Number) msg.data.get("totalChunks");
        String chunkDataB64 = (String) msg.data.get("chunkData");
        
        if (fileId == null || chunkIndex == null || totalChunks == null || chunkDataB64 == null) {
            System.err.println("❌ Invalid FILE_CHUNK message");
            return;
        }
        
        // Decode chunk data
        byte[] chunkData;
        try {
            chunkData = java.util.Base64.getDecoder().decode(chunkDataB64);
        } catch (IllegalArgumentException e) {
            System.err.println("❌ Failed to decode chunk data");
            return;
        }
        
        // Extract metadata từ chunk đầu
        String fileName = null;
        Long fileSize = null;
        Integer conversationId = null;
        String clientMessageId = null;
        
        if (chunkIndex.intValue() == 0) {
            fileName = (String) msg.data.get("fileName");
            Number fileSizeNum = (Number) msg.data.get("fileSize");
            fileSize = fileSizeNum != null ? fileSizeNum.longValue() : null;
            Number convId = (Number) msg.data.get("conversationId");
            conversationId = convId != null ? convId.intValue() : null;
            clientMessageId = (String) msg.data.get("clientMessageId");
            
            if (fileName == null || fileSize == null || conversationId == null) {
                System.err.println("❌ Invalid first chunk metadata");
                return;
            }
            
            // Lưu metadata
            storeFileMetadata(fileId, fileName, fileSize, conversationId, 
                             clientMessageId, msg.from);
            
            System.out.println("📥 Receiving: " + fileName + " (" + totalChunks + " chunks)");
        } else {
            // Lấy metadata đã lưu
            FileChunkMetadata metadata = getFileMetadata(fileId);
            if (metadata == null) {
                System.err.println("❌ Missing metadata for chunk " + chunkIndex);
                return;
            }
            fileName = metadata.fileName;
            fileSize = metadata.fileSize;
            conversationId = metadata.conversationId;
            clientMessageId = metadata.clientMessageId;
        }
        
        // Forward tới FileTransferController
        if (chatController != null && chatController.getFileTransferController() != null) {
            chatController.getFileTransferController().handleFileChunk(
                msg.from, 
                fileId, 
                chunkIndex.intValue(), 
                chunkData,
                totalChunks.intValue(), 
                fileName, 
                fileSize, 
                conversationId, 
                clientMessageId
            );
        }
    }

    private void handleFileComplete(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        
        if (chatController != null && chatController.getFileTransferController() != null) {
            chatController.getFileTransferController().handleFileComplete(fileId);
        }
       
    }

    private void handleFileCancel(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        
        if (eventListener != null) {
            eventListener.onFileCanceled(fileId, false);
        }
    }

    private void handleAudioReject(P2PMessageProtocol.Message msg) {
        String callId = (String) msg.data.get("callId");
        String reason = (String) msg.data.get("reason");
        
//        if (eventListener != null) {
//            eventListener.onAudioCallRejected(msg.from, callId, reason);
//        }
    }

    private void handleMessageSeen(P2PMessageProtocol.Message msg) {
        Number messageId = (Number) msg.data.get("messageId");
        if (messageId != null) {
            chatService.markMessageSeen(messageId.intValue(), msg.from);
        }
    }

    // ✅ Storage for file chunks metadata - used by FileTransferController
    private final Map<String, FileChunkMetadata> fileChunkMetadata = new ConcurrentHashMap<>();
    
    public static class FileChunkMetadata {
        public String fileName;
        public Long fileSize;
        public Integer conversationId;
        public String clientMessageId;
        public Integer fromUserId;

        public FileChunkMetadata() {}

        public FileChunkMetadata(String fileName, Long fileSize,
                                 Integer conversationId,
                                 String clientMessageId,
                                 Integer fromUserId) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.conversationId = conversationId;
            this.clientMessageId = clientMessageId;
            this.fromUserId = fromUserId;
        }
    }

    
    /**
     * ✅ Store file metadata for chunks (called from handleFileChunk)
     */
    public void storeFileMetadata(String fileId, String fileName, Long fileSize, 
                                  Integer conversationId, String clientMessageId, 
                                   Integer fromUserId) {
        FileChunkMetadata metadata = new FileChunkMetadata(
            fileName, fileSize, conversationId, clientMessageId, fromUserId
        );
        fileChunkMetadata.put(fileId, metadata);
        System.out.println("📦 [P2PManager] Stored metadata for fileId=" + fileId + 
                          ", fileName=" + fileName + ", fromUserId=" + fromUserId);
    }
    
    /**
     * ✅ Get file metadata (used by FileTransferController)
     */
    public FileChunkMetadata getFileMetadata(String fileId) {
        return fileChunkMetadata.get(fileId);
    }
    
    /**
     * ✅ Remove file metadata after transfer completes
     */
    public void removeFileMetadata(String fileId) {
        FileChunkMetadata removed = fileChunkMetadata.remove(fileId);
        if (removed != null) {
            System.out.println("🗑️ [P2PManager] Removed metadata for fileId=" + fileId);
        }
    }
    
    /**
     * ✅ Handle FILE_ACK from receiver
     */
//    private void handleFileAck(P2PMessageProtocol.Message msg) {
//        String fileId = (String) msg.data.get("fileId");
//        if (fileId == null) return;
//
//        System.out.println("✅ Receiver confirmed file: " + fileId);
//
//        // Notify UI (sender side)
//        if (eventListener != null) {
//            eventListener.onFileComplete(fileId, null, true);
//        }
//    }
//
//
//    /**
//     * ✅ Handle FILE_NACK from receiver
//     */
//    private void handleFileNack(P2PMessageProtocol.Message msg) {
//        String fileId = (String) msg.data.get("fileId");
//        String reason = (String) msg.data.get("reason");
//        
//        if (fileId != null) {
//            System.err.println("❌ Received FILE_NACK for: " + fileId + ", reason: " + reason);
//            
//            if (eventListener != null) {
//                eventListener.onFileError(fileId, reason);
//            }
//        }
//    }


    // ===== SETUP LISTENERS =====

    private void setupFileTransferListener() {
        fileTransferManager.setListener(new FileTransferManager.FileTransferListener() {
            @Override
            public void onFileProgress(String fileId, int progress, boolean isUpload) {
                if (eventListener != null) {
                    eventListener.onFileProgress(fileId, progress, isUpload);
                }
            }

            @Override
            public void onFileComplete(String fileId, File file, boolean isUpload) {
                if (eventListener != null) {
                    eventListener.onFileComplete(fileId, file, isUpload);
                }
            }

            @Override
            public void onFileCanceled(String fileId, boolean isUpload) {
                if (eventListener != null) {
                    eventListener.onFileCanceled(fileId, isUpload);
                }
            }

            @Override
            public void onFileError(String fileId, String error) {
                if (eventListener != null) {
                    eventListener.onFileError(fileId, error);
                }
            }
            
            /**
             * ✅ Handle FILE_NACK from receiver
             */
            private void handleFileNack(P2PMessageProtocol.Message msg) {
                String fileId = (String) msg.data.get("fileId");
                String reason = (String) msg.data.get("reason");
                
                if (fileId != null) {
                    System.err.println("❌ Received FILE_NACK for: " + fileId + ", reason: " + reason);
                    
                    if (eventListener != null) {
                        eventListener.onFileError(fileId, reason);
                    }
                }
            }
            
        });
    }

    private void setupAudioCallListener() {
        audioCallManager.setListener(new AudioCallManager.AudioCallListener() {
            @Override
            public void onCallRequested(Integer fromUser, String callId) {
                if (eventListener != null) {
                    //eventListener.onAudioCallRequested(fromUser, callId);
                }
            }

            @Override
            public void onCallAccepted(Integer fromUser, String callId) {
                if (eventListener != null) {
                    //eventListener.onAudioCallAccepted(fromUser, callId);
                }
            }

            @Override
            public void onCallRejected(Integer fromUser, String callId, String reason) {
                if (eventListener != null) {
                    //eventListener.onAudioCallRejected(fromUser, callId, reason);
                }
            }

            @Override
            public void onCallStarted(String callId) {
                if (eventListener != null) {
                    //eventListener.onAudioCallStarted(callId);
                }
            }

            @Override
            public void onCallEnded(String callId) {
                if (eventListener != null) {
                    //eventListener.onAudioCallEnded(callId);
                }
            }

            @Override
            public void onCallError(String callId, String error) {
                if (eventListener != null) {
                    //eventListener.onAudioCallError(callId, error);
                }
            }
            
            
        });
    }

    // ===== SETTERS =====

    public void setEventListener(P2PEventListener listener) {
        this.eventListener = listener;
    }
    
    /**
     * ✅ Trigger onChatMessageReceived event manually (for local messages like file messages)
     */
    public void triggerChatMessageReceived(Integer conversationId, Message message) {
        if (eventListener != null) {
            eventListener.onChatMessageReceived(conversationId, message);
        }
    }
    
    // ✅ Methods already defined above

    // ===== CLEANUP =====

    public void shutdown() {
        fileTransferManager.shutdown();
        audioCallManager.shutdown();
        activeConnections.values().forEach(PeerConnection::closeAll);
        activeConnections.clear();
        fileChunkMetadata.clear();
    }
}