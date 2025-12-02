package protocol;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Giao thức P2P - định dạng JSON
 * 
 * Format:
 * {
 *   "type": "CHAT_MESSAGE" | "TYPING" | "FILE_REQUEST" | "FILE_CHUNK" | "CALL_SIGNAL",
 *   "from": userId,
 *   "to": userId,
 *   "conversationId": 123,
 *   "data": { ... }
 * }
 */
public class P2PMessageProtocol {
    private static final Gson gson = new Gson();

    // Message types
    public enum MessageType {
        CHAT_MESSAGE,      // Tin nhắn chat thông thường
        TYPING_START,      // Bắt đầu typing
        TYPING_STOP,       // Dừng typing
        FILE_REQUEST,      // Yêu cầu gửi file
        FILE_ACCEPT,       // Chấp nhận nhận file
        FILE_REJECT,       // Từ chối nhận file
        FILE_CHUNK,        // Chunk của file
        FILE_COMPLETE,     // File đã gửi xong
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
        public String type;              // MessageType as string
        public Integer from;                // userId người gửi
        public Integer to;                  // userId người nhận (optional nếu dùng conversationId)
        public Integer conversationId;      // ID cuộc hội thoại
        public Map<String, Object> data; // Payload tùy thuộc type
        public Integer timestamp;           // Unix timestamp

        public Message() {
            this.timestamp = (int) System.currentTimeMillis();
            this.data = new HashMap<>();
        }
    }

    // ===== BUILDER METHODS =====

    public static String buildChatMessage(Integer from, Integer conversationId, String content) {
        Message msg = new Message();
        msg.type = MessageType.CHAT_MESSAGE.name();
        msg.from = from;
        msg.conversationId = conversationId;
        msg.data.put("content", content);
        return gson.toJson(msg);
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

    public static String buildFileRequest(Integer from, Integer to, String fileName, Integer fileSize) {
        Message msg = new Message();
        msg.type = MessageType.FILE_REQUEST.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileName", fileName);
        msg.data.put("fileSize", fileSize);
        return gson.toJson(msg);
    }

    public static String buildFileChunk(Integer from, Integer to, int chunkIndex, byte[] chunkData) {
        Message msg = new Message();
        msg.type = MessageType.FILE_CHUNK.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("chunkIndex", chunkIndex);
        msg.data.put("chunkData", Base64.getEncoder().encodeToString(chunkData));
        return gson.toJson(msg);
    }

    public static String buildCallOffer(Integer localUserId, Integer toUserId, String sdp) {
        Message msg = new Message();
        msg.type = MessageType.CALL_OFFER.name();
        msg.from = localUserId;
        msg.to = toUserId;
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

    public static String buildMessageSeen(Integer from, Integer conversationId, Integer messageId) {
        Message msg = new Message();
        msg.type = MessageType.MESSAGE_SEEN.name();
        msg.from = from;
        msg.conversationId = conversationId;
        msg.data.put("messageId", messageId);
        return gson.toJson(msg);
    }

    // ===== PARSE METHOD =====
    public static Message parse(String json) {
        try {
            return gson.fromJson(json, Message.class);
        } catch (JsonSyntaxException e) {
            System.err.println("❌ Invalid P2P message format: " + json);
            return null;
        }
    }

    // ===== HELPER =====
    public static boolean isValid(Message msg) {
        return msg != null 
            && msg.type != null 
            && msg.from != null 
            && msg.timestamp != null;
    }
}