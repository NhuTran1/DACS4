package network.p2p;

import protocol.P2PMessageProtocol;
import service.ChatService;
import model.Message;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2PManager - Quản lý tất cả kết nối P2P
 * - Tạo/đóng connection tới peers
 * - Routing messages
 * - Lắng nghe incoming connections
 */
public class P2PManager implements PeerConnection.P2PMessageHandler {
    private final Integer localUserId;
    private final Map<Integer, PeerConnection> activeConnections = new ConcurrentHashMap<>();
    private final ChatService chatService;
    private final PeerDiscoveryService discoveryService;
    
    // Callback cho UI
    private P2PEventListener eventListener;

    public interface P2PEventListener {
        void onChatMessageReceived(Integer conversationId, Message message);
        void onTypingReceived(Integer conversationId, Integer userId);
        void onFileRequestReceived(Integer fromUser, String fileName, Integer fileSize);
        void onCallOfferReceived(Integer fromUser, String sdp);
        void onConnectionLost(Integer userId);

        // New events for chat request flow
        void onChatRequestReceived(Integer fromUser, String fromDisplayName);
        void onChatRequestResponse(Integer fromUser, boolean accepted);
    }

    public P2PManager(Integer localUserId, ChatService chatService) {
        this.localUserId = localUserId;
        this.chatService = chatService;
        this.discoveryService = PeerDiscoveryService.getInstance();
    }

    // ===== CONNECTION MANAGEMENT =====

    /**
     * Kết nối tới một peer
     */
    public boolean connectToPeer(Integer userId) {
        if (activeConnections.containsKey(userId)) {
            return true; // Đã kết nối rồi
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

    /**
     * Ngắt kết nối với peer
     */
    public void disconnectPeer(Integer userId) {
        PeerConnection conn = activeConnections.remove(userId);
        if (conn != null) {
            conn.closeAll();
            System.out.println("👋 Disconnected from peer: " + userId);
        }
    }

    /**
     * Lấy hoặc tạo connection tới peer
     */
    private PeerConnection getOrCreateConnection(Integer userId) {
        PeerConnection conn = activeConnections.get(userId);
        if (conn != null && conn.isTcpConnected()) {
            return conn;
        }

        // Nếu chưa có hoặc bị disconnect → tạo mới
        if (connectToPeer(userId)) {
            return activeConnections.get(userId);
        }

        return null;
    }

    // ===== SEND METHODS =====

    /**
     * Gửi chat message tới peer trong conversation
     */
    public boolean sendChatMessage(Integer conversationId, String content) {
        // Lấy danh sách participants
        var participants = chatService.listParticipants(conversationId);
        if (participants == null || participants.isEmpty()) return false;

        String json = P2PMessageProtocol.buildChatMessage(localUserId, conversationId, content);
        boolean success = true;

        for (var user : participants) {
            if (user.getId().equals(localUserId)) continue; // Không gửi cho chính mình

            PeerConnection conn = getOrCreateConnection(user.getId());
            if (conn != null) {
                if (!conn.sendTcp(json)) {
                    success = false;
                    System.err.println("❌ Failed to send message to peer: " + user.getId());
                }
            } else {
                success = false;
                System.err.println("❌ Cannot connect to peer: " + user.getId());
            }
        }

        return success;
    }

    /**
     * Gửi typing indicator
     */
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

    /**
     * Gửi file request tới peer
     */
    public boolean sendFileRequest(Integer toUserId, String fileName, Integer fileSize) {
        PeerConnection conn = getOrCreateConnection(toUserId);
        if (conn == null) return false;

        String json = P2PMessageProtocol.buildFileRequest(localUserId, toUserId, fileName, fileSize);
        return conn.sendTcp(json);
    }

    /**
     * Gửi call offer (WebRTC)
     */
    public boolean sendCallOffer(Integer toUserId, String sdp) {
        PeerConnection conn = getOrCreateConnection(toUserId);
        if (conn == null) return false;

        String json = P2PMessageProtocol.buildCallOffer(localUserId, toUserId, sdp);
        return conn.sendTcp(json);
    }

    /**
     * Gửi call answer
     */
    public boolean sendCallAnswer(Integer toUserId, String sdp) {
        PeerConnection conn = getOrCreateConnection(toUserId);
        if (conn == null) return false;

        String json = P2PMessageProtocol.buildCallAnswer(localUserId, toUserId, sdp);
        return conn.sendTcp(json);
    }

    /**
     * Gửi call hangup
     */
    public boolean sendCallHangup(Integer toUserId) {
        PeerConnection conn = activeConnections.get(toUserId);
        if (conn == null) return false;

        String json = P2PMessageProtocol.buildCallHangup(localUserId, toUserId);
        return conn.sendTcp(json);
    }

    // ===== MESSAGE HANDLER (từ PeerConnection) =====

    @Override
    public void onMessageReceived(P2PMessageProtocol.Message msg) {
        if (msg == null || msg.type == null) return;

        try {
            P2PMessageProtocol.MessageType type = P2PMessageProtocol.MessageType.valueOf(msg.type);

            switch (type) {
                case CHAT_MESSAGE -> handleChatMessage(msg);
                
                case TYPING_START -> handleTypingStart(msg);
                case TYPING_STOP -> handleTypingStop(msg);
                case FILE_REQUEST -> handleFileRequest(msg);
                case CALL_OFFER -> handleCallOffer(msg);
                case CALL_ANSWER -> handleCallAnswer(msg);
                case CALL_HANGUP -> handleCallHangup(msg);
                case MESSAGE_SEEN -> handleMessageSeen(msg);
                default -> System.out.println("⚠️ Unhandled message type: " + type);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("❌ Invalid message type: " + msg.type);
        }
    }

    @Override
    public void onConnectionLost() {
        if (eventListener != null) {
            // Tìm peer nào bị mất kết nối
            activeConnections.entrySet().removeIf(entry -> {
                if (!entry.getValue().isTcpConnected()) {
                    eventListener.onConnectionLost(entry.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    // ===== HANDLE INCOMING MESSAGES =====

    private void handleChatMessage(P2PMessageProtocol.Message msg) {
        if (eventListener == null) return;

        // Lưu message vào DB
        String content = (String) msg.data.get("content");
        Message savedMsg = chatService.sendMessage(
            msg.conversationId, 
            msg.from, 
            content, 
            null
        );

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
        // UI có thể xử lý việc ẩn "typing..." indicator
    }

    private void handleFileRequest(P2PMessageProtocol.Message msg) {
        if (eventListener == null) return;

        String fileName = (String) msg.data.get("fileName");
        Number fileSize = (Number) msg.data.get("fileSize");

        eventListener.onFileRequestReceived(msg.from, fileName, fileSize.intValue());
    }

    private void handleCallOffer(P2PMessageProtocol.Message msg) {
        if (eventListener == null) return;

        String sdp = (String) msg.data.get("sdp");
        eventListener.onCallOfferReceived(msg.from, sdp);
    }

    private void handleCallAnswer(P2PMessageProtocol.Message msg) {
        // Xử lý WebRTC answer
        System.out.println("📞 Received call answer from " + msg.from);
    }

    private void handleCallHangup(P2PMessageProtocol.Message msg) {
        System.out.println("📞 Call ended by " + msg.from);
    }

    private void handleMessageSeen(P2PMessageProtocol.Message msg) {
        Number messageId = (Number) msg.data.get("messageId");
        if (messageId != null) {
            chatService.markMessageSeen(messageId.intValue(), msg.from);
        }
    }

    // ===== SETTERS =====

    public void setEventListener(P2PEventListener listener) {
        this.eventListener = listener;
    }

    // ===== CLEANUP =====

    public void shutdown() {
        activeConnections.values().forEach(PeerConnection::closeAll);
        activeConnections.clear();
    }
}