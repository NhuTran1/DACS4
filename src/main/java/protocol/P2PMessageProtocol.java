package protocol;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * P2P Message Protocol with Idempotent support (clientMessageId)
 */
public class P2PMessageProtocol {
    private static final Gson gson = new Gson();

    // Message types
    public enum MessageType {
        CHAT_MESSAGE,      // Tin nhắn chat thông thường
        TYPING_START,      // Bắt đầu typing
        TYPING_STOP,       // Dừng typing
        
        // File transfer - Simplified (no request/accept/reject)
        FILE_CHUNK,        // Chunk của file
        FILE_COMPLETE,     // File đã gửi xong
        FILE_CANCEL,       // Hủy việc gửi file
        FILE_ACK,        // ✅ NEW: ACK từ receiver
        FILE_NACK,       // ✅ NEW: NACK từ receiver (failed)
        
        // Audio/Voice call
        AUDIO_REQUEST,     // Yêu cầu bắt đầu voice call
        AUDIO_ACCEPT,      // Chấp nhận voice call
        AUDIO_REJECT,      // Từ chối voice call
        AUDIO_DATA,        // Audio data chunk (streaming)
        AUDIO_END,         // Kết thúc voice call
        
        // WebRTC signaling (video call)
        CALL_OFFER,        // WebRTC offer
        CALL_ANSWER,       // WebRTC answer
        CALL_ICE,          // ICE candidate
        CALL_HANGUP,       // Kết thúc cuộc gọi
        
        MESSAGE_SEEN,      // Đánh dấu đã đọc
        PING,              // Kiểm tra kết nối
        PONG               // Phản hồi ping
    }

    // ===== MAIN PROTOCOL CLASS =====
    public static class Message {
        public String type;              
        public Integer from;             
        public Integer to;               
        public Integer conversationId;   
        public Map<String, Object> data; 
        public Long timestamp;           

        public Message() {
            this.timestamp = System.currentTimeMillis();
            this.data = new HashMap<>();
        }
    }

    // ===== CHAT MESSAGES - WITH IDEMPOTENT =====
    
    /**
     * Build chat message với clientMessageId (Idempotent)
     */
    public static String buildChatMessage(Integer from, Integer conversationId, String content, String clientMessageId) {
        Message msg = new Message();
        msg.type = MessageType.CHAT_MESSAGE.name();
        msg.from = from;
        msg.conversationId = conversationId;
        msg.data.put("content", content);
        msg.data.put("clientMessageId", clientMessageId);
        return gson.toJson(msg);
    }
    
    /**
     * Legacy wrapper (không có clientMessageId)
     */
    public static String buildChatMessage(Integer from, Integer conversationId, String content) {
        return buildChatMessage(from, conversationId, content, java.util.UUID.randomUUID().toString());
    }

    public static String buildTypingStart(Integer from, Integer conversationId) {
        Message msg = new Message();
        msg.type = MessageType.TYPING_START.name();
        msg.from = from;
        msg.conversationId = conversationId;
        return gson.toJson(msg);
    }

    public static String buildTypingStop(Integer from, Integer conversationId) {
        Message msg = new Message();
        msg.type = MessageType.TYPING_STOP.name();
        msg.from = from;
        msg.conversationId = conversationId;
        return gson.toJson(msg);
    }

    // ===== FILE TRANSFER MESSAGES - SIMPLIFIED WITH IDEMPOTENT =====
    
    /**
     * Gửi chunk của file với metadata trong chunk đầu tiên
     */
    public static String buildFileChunk(Integer from, Integer to, String fileId, 
                                       int chunkIndex, byte[] chunkData, int totalChunks,
                                       String fileName, Long fileSize, Integer conversationId,
                                       String clientMessageId) {
        Message msg = new Message();
        msg.type = MessageType.FILE_CHUNK.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        msg.data.put("chunkIndex", chunkIndex);
        msg.data.put("totalChunks", totalChunks);
        msg.data.put("chunkData", Base64.getEncoder().encodeToString(chunkData));
        
        // Metadata chỉ gửi trong chunk đầu tiên
        if (chunkIndex == 0) {
            msg.data.put("fileName", fileName);
            msg.data.put("fileSize", fileSize);
            msg.data.put("conversationId", conversationId);
            msg.data.put("clientMessageId", clientMessageId);
        }
        
        return gson.toJson(msg);
    }

    /**
     * File đã gửi xong
     */
    public static String buildFileComplete(Integer from, Integer to, String fileId) {
        Message msg = new Message();
        msg.type = MessageType.FILE_COMPLETE.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        return gson.toJson(msg);
    }

    /**
     * Hủy việc gửi file
     */
    public static String buildFileCancel(Integer from, Integer to, String fileId) {
        Message msg = new Message();
        msg.type = MessageType.FILE_CANCEL.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        return gson.toJson(msg);
    }
    
    /**
     * ✅ Build FILE_ACK - xác nhận đã nhận file thành công
     */
    public static String buildFileAck(Integer from, Integer to, String fileId) {
        Message msg = new Message();
        msg.type = MessageType.FILE_ACK.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        return gson.toJson(msg);
    }

    /**
     * ✅ Build FILE_NACK - báo lỗi khi nhận file
     */
    public static String buildFileNack(Integer from, Integer to, String fileId, String reason) {
        Message msg = new Message();
        msg.type = MessageType.FILE_NACK.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        msg.data.put("reason", reason);
        return gson.toJson(msg);
    }

    // ===== AUDIO/VOICE CALL MESSAGES =====
    
    /**
     * Yêu cầu bắt đầu voice call
     */
    public static String buildAudioRequest(Integer from, Integer to, String callId) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_REQUEST.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("callId", callId);
        return gson.toJson(msg);
    }

    /**
     * Chấp nhận voice call
     */
    public static String buildAudioAccept(Integer from, Integer to, String callId, int udpPort) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_ACCEPT.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("callId", callId);
        msg.data.put("udpPort", udpPort);
        return gson.toJson(msg);
    }

    /**
     * Từ chối voice call
     */
    public static String buildAudioReject(Integer from, Integer to, String callId, String reason) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_REJECT.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("callId", callId);
        msg.data.put("reason", reason);
        return gson.toJson(msg);
    }

    /**
     * Kết thúc voice call
     */
    public static String buildAudioEnd(Integer from, Integer to, String callId) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_END.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("callId", callId);
        return gson.toJson(msg);
    }

    // ===== WEBRTC SIGNALING =====
    
    public static String buildCallOffer(Integer from, Integer to, String sdp) {
        Message msg = new Message();
        msg.type = MessageType.CALL_OFFER.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("sdp", sdp);
        return gson.toJson(msg);
    }

    public static String buildCallAnswer(Integer from, Integer to, String sdp) {
        Message msg = new Message();
        msg.type = MessageType.CALL_ANSWER.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("sdp", sdp);
        return gson.toJson(msg);
    }

    public static String buildCallHangup(Integer from, Integer to) {
        Message msg = new Message();
        msg.type = MessageType.CALL_HANGUP.name();
        msg.from = from;
        msg.to = to;
        return gson.toJson(msg);
    }

    // ===== MESSAGE SEEN =====
    
    public static String buildMessageSeen(Integer from, Integer conversationId, Integer messageId) {
        Message msg = new Message();
        msg.type = MessageType.MESSAGE_SEEN.name();
        msg.from = from;
        msg.conversationId = conversationId;
        msg.data.put("messageId", messageId);
        return gson.toJson(msg);
    }

    // ===== PARSE & VALIDATION =====
    
    public static Message parse(String json) {
        try {
            return gson.fromJson(json, Message.class);
        } catch (JsonSyntaxException e) {
            System.err.println("❌ Invalid P2P message format: " + json);
            return null;
        }
    }

    public static boolean isValid(Message msg) {
        return msg != null 
            && msg.type != null 
            && msg.from != null 
            && msg.timestamp != null;
    }
}