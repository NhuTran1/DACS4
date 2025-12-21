package network.p2p;

import protocol.P2PMessageProtocol;
import service.ChatService;
import model.Message;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2PManager - Router with Idempotent support
 */
public class P2PManager implements PeerConnection.P2PMessageHandler {
    private final Integer localUserId;
    private final Map<Integer, PeerConnection> activeConnections = new ConcurrentHashMap<>();
    private final ChatService chatService;
    private final PeerDiscoveryService discoveryService;
    
    // Managers
    private final FileTransferManager fileTransferManager;
    private final AudioCallManager audioCallManager;
    
    // Callback cho UI
    private P2PEventListener eventListener;

    public interface P2PEventListener {
        void onChatMessageReceived(Integer conversationId, Message message);
        void onTypingReceived(Integer conversationId, Integer userId);
        
        // File transfer events
        void onFileRequested(Integer fromUser, String fileId, String fileName, Long fileSize);
        void onFileAccepted(Integer fromUser, String fileId);
        void onFileRejected(Integer fromUser, String fileId, String reason);
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

    public P2PManager(Integer localUserId, ChatService chatService) {
        this.localUserId = localUserId;
        this.chatService = chatService;
        this.discoveryService = PeerDiscoveryService.getInstance();
        
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

    /**
     * Gửi chat message với clientMessageId (Idempotent)
     */
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

    // ===== FILE TRANSFER API =====

    /**
     * Gửi file tới user
     */
    public String sendFile(Integer toUserId, File file) throws Exception {
        return fileTransferManager.sendFileRequest(toUserId, file);
    }

    /**
     * Accept nhận file
     */
    public void acceptFile(String fileId) {
        fileTransferManager.acceptFile(fileId);
    }

    /**
     * Reject nhận file
     */
    public void rejectFile(String fileId, String reason) {
        fileTransferManager.rejectFile(fileId, reason);
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
                
                // File transfer
                case FILE_REQUEST -> fileTransferManager.handleFileRequest(msg);
                case FILE_ACCEPT -> fileTransferManager.handleFileAccept(
                    (String) msg.data.get("fileId"), msg.from);
                case FILE_REJECT -> handleFileReject(msg);
                case FILE_CHUNK -> fileTransferManager.handleFileChunk(msg);
                case FILE_COMPLETE -> fileTransferManager.handleFileComplete(
                    (String) msg.data.get("fileId"));
                case FILE_CANCEL -> fileTransferManager.handleFileCancel(
                    (String) msg.data.get("fileId"));
                
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

        // Gọi callback UI để cập nhật giao diện 
        if (savedMsg != null) {
            eventListener.onChatMessageReceived(msg.conversationId, savedMsg);
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

    private void handleFileReject(P2PMessageProtocol.Message msg) {
        String fileId = (String) msg.data.get("fileId");
        String reason = (String) msg.data.get("reason");
        
        if (eventListener != null) {
            eventListener.onFileRejected(msg.from, fileId, reason);
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

    // ===== SETUP LISTENERS =====

    private void setupFileTransferListener() {
        fileTransferManager.setListener(new FileTransferManager.FileTransferListener() {
            @Override
            public void onFileRequested(Integer fromUser, String fileId, String fileName, Long fileSize) {
                if (eventListener != null) {
                    eventListener.onFileRequested(fromUser, fileId, fileName, fileSize);
                }
            }

            @Override
            public void onFileAccepted(Integer fromUser, String fileId) {
                if (eventListener != null) {
                    eventListener.onFileAccepted(fromUser, fileId);
                }
            }

            @Override
            public void onFileRejected(Integer fromUser, String fileId, String reason) {
                if (eventListener != null) {
                    eventListener.onFileRejected(fromUser, fileId, reason);
                }
            }

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

    // ===== CLEANUP =====

    public void shutdown() {
        fileTransferManager.shutdown();
        audioCallManager.shutdown();
        activeConnections.values().forEach(PeerConnection::closeAll);
        activeConnections.clear();
    }
}