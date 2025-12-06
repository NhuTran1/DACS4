package protocol;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class P2PMessageProtocol {
    private static final Gson gson = new Gson();

    public enum MessageType {
        CHAT_MESSAGE,
        TYPING_START,
        TYPING_STOP,
        FILE_REQUEST,      // Yêu cầu gửi file
        FILE_ACCEPT,       // Chấp nhận nhận file
        FILE_REJECT,       // Từ chối nhận file
        FILE_CHUNK,        // Chunk của file
        FILE_COMPLETE,     // File đã gửi xong
        FILE_CANCEL,       // Hủy transfer
        AUDIO_REQUEST,     // Yêu cầu gửi audio
        AUDIO_ACCEPT,      // Chấp nhận nhận audio
        AUDIO_REJECT,      // Từ chối nhận audio
        AUDIO_CHUNK,       // Chunk của audio
        AUDIO_COMPLETE,    // Audio đã gửi xong
        CALL_OFFER,
        CALL_ANSWER,
        CALL_ICE,
        CALL_HANGUP,
        MESSAGE_SEEN,
        PING,
        PONG
    }

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

    // ===== FILE TRANSFER METHODS =====
    
    public static String buildFileRequest(Integer from, Integer to, Integer conversationId,
                                         String fileName, Long fileSize, String fileId) {
        Message msg = new Message();
        msg.type = MessageType.FILE_REQUEST.name();
        msg.from = from;
        msg.to = to;
        msg.conversationId = conversationId;
        msg.data.put("fileName", fileName);
        msg.data.put("fileSize", fileSize);
        msg.data.put("fileId", fileId);
        return gson.toJson(msg);
    }

    public static String buildFileAccept(Integer from, Integer to, String fileId) {
        Message msg = new Message();
        msg.type = MessageType.FILE_ACCEPT.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        return gson.toJson(msg);
    }

    public static String buildFileReject(Integer from, Integer to, String fileId, String reason) {
        Message msg = new Message();
        msg.type = MessageType.FILE_REJECT.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        msg.data.put("reason", reason);
        return gson.toJson(msg);
    }

    public static String buildFileChunk(Integer from, Integer to, String fileId, 
                                       int chunkIndex, int totalChunks, byte[] chunkData) {
        Message msg = new Message();
        msg.type = MessageType.FILE_CHUNK.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        msg.data.put("chunkIndex", chunkIndex);
        msg.data.put("totalChunks", totalChunks);
        msg.data.put("chunkData", Base64.getEncoder().encodeToString(chunkData));
        return gson.toJson(msg);
    }

    public static String buildFileComplete(Integer from, Integer to, String fileId) {
        Message msg = new Message();
        msg.type = MessageType.FILE_COMPLETE.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        return gson.toJson(msg);
    }

    public static String buildFileCancel(Integer from, Integer to, String fileId, String reason) {
        Message msg = new Message();
        msg.type = MessageType.FILE_CANCEL.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("fileId", fileId);
        msg.data.put("reason", reason);
        return gson.toJson(msg);
    }

    // ===== AUDIO TRANSFER METHODS =====
    
    public static String buildAudioRequest(Integer from, Integer to, Integer conversationId,
                                          Long duration, String audioId) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_REQUEST.name();
        msg.from = from;
        msg.to = to;
        msg.conversationId = conversationId;
        msg.data.put("duration", duration);
        msg.data.put("audioId", audioId);
        return gson.toJson(msg);
    }

    public static String buildAudioAccept(Integer from, Integer to, String audioId) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_ACCEPT.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("audioId", audioId);
        return gson.toJson(msg);
    }

    public static String buildAudioReject(Integer from, Integer to, String audioId) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_REJECT.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("audioId", audioId);
        return gson.toJson(msg);
    }

    public static String buildAudioChunk(Integer from, Integer to, String audioId,
                                        int chunkIndex, int totalChunks, byte[] audioData) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_CHUNK.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("audioId", audioId);
        msg.data.put("chunkIndex", chunkIndex);
        msg.data.put("totalChunks", totalChunks);
        msg.data.put("audioData", Base64.getEncoder().encodeToString(audioData));
        return gson.toJson(msg);
    }

    public static String buildAudioComplete(Integer from, Integer to, String audioId) {
        Message msg = new Message();
        msg.type = MessageType.AUDIO_COMPLETE.name();
        msg.from = from;
        msg.to = to;
        msg.data.put("audioId", audioId);
        return gson.toJson(msg);
    }

    // ===== EXISTING METHODS =====
    
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

    public static String buildMessageSeen(Integer from, Integer conversationId, Integer messageId) {
        Message msg = new Message();
        msg.type = MessageType.MESSAGE_SEEN.name();
        msg.from = from;
        msg.conversationId = conversationId;
        msg.data.put("messageId", messageId);
        return gson.toJson(msg);
    }

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