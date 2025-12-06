package network.p2p;

import protocol.P2PMessageProtocol;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AudioRecorder - Ghi âm và gửi audio qua P2P
 */
public class AudioRecorder {
    private static final int SAMPLE_RATE = 16000; // 16kHz
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final int CHANNELS = 1; // Mono
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;
    private static final int CHUNK_SIZE = 32 * 1024; // 32KB
    private static final String AUDIO_DIR = "audio_messages";
    
    private TargetDataLine microphone;
    private boolean isRecording = false;
    private ByteArrayOutputStream recordingBuffer;
    
    // Map: audioId -> AudioTransferSession
    private final Map<String, AudioTransferSession> activeSessions = new ConcurrentHashMap<>();
    
    private AudioTransferListener listener;
    
    public interface AudioTransferListener {
        void onAudioRequest(Integer fromUser, String audioId, Long duration);
        void onAudioAccepted(String audioId);
        void onAudioRejected(String audioId);
        void onAudioProgress(String audioId, int progress);
        void onAudioCompleted(String audioId, Path savedPath);
        void onAudioError(String audioId, String error);
        void onRecordingStarted();
        void onRecordingStopped(long duration);
    }
    
    public AudioRecorder() {
        try {
            Files.createDirectories(Paths.get(AUDIO_DIR));
        } catch (IOException e) {
            System.err.println("Failed to create audio directory: " + e.getMessage());
        }
    }
    
    // ===== RECORDING =====
    
    /**
     * Bắt đầu ghi âm
     */
    public void startRecording() throws LineUnavailableException {
        if (isRecording) {
            System.err.println("Already recording");
            return;
        }
        
        AudioFormat format = new AudioFormat(
            SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN
        );
        
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Line not supported");
        }
        
        microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(format);
        microphone.start();
        
        recordingBuffer = new ByteArrayOutputStream();
        isRecording = true;
        
        // Start recording thread
        new Thread(() -> {
            byte[] buffer = new byte[4096];
            while (isRecording) {
                int bytesRead = microphone.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    recordingBuffer.write(buffer, 0, bytesRead);
                }
            }
        }, "audio-recorder").start();
        
        if (listener != null) {
            listener.onRecordingStarted();
        }
        
        System.out.println("🎤 Recording started");
    }
    
    /**
     * Dừng ghi âm và lưu file
     */
    public File stopRecording() throws IOException {
        if (!isRecording) {
            System.err.println("Not recording");
            return null;
        }
        
        isRecording = false;
        
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        
        byte[] audioData = recordingBuffer.toByteArray();
        
        // Calculate duration (in milliseconds)
        long duration = (audioData.length * 1000L) / 
                       (SAMPLE_RATE * (SAMPLE_SIZE_IN_BITS / 8) * CHANNELS);
        
        // Save to WAV file
        String fileName = "audio_" + System.currentTimeMillis() + ".wav";
        Path audioPath = Paths.get(AUDIO_DIR, fileName);
        File audioFile = audioPath.toFile();
        
        AudioFormat format = new AudioFormat(
            SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN
        );
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
             AudioInputStream audioInputStream = new AudioInputStream(bais, format, audioData.length / format.getFrameSize())) {
            
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, audioFile);
        }
        
        if (listener != null) {
            listener.onRecordingStopped(duration);
        }
        
        System.out.println("🎤 Recording stopped: " + duration + "ms");
        
        return audioFile;
    }
    
    /**
     * Gửi audio request
     */
    public String requestAudioSend(File audioFile, Integer toUserId, Integer conversationId,
                                   P2PManager p2pManager) {
        if (!audioFile.exists()) {
            throw new IllegalArgumentException("Audio file does not exist");
        }
        
        // Calculate duration
        long duration = calculateAudioDuration(audioFile);
        
        String audioId = UUID.randomUUID().toString();
        AudioTransferSession session = new AudioTransferSession(
            audioId, audioFile, toUserId, conversationId, duration, true
        );
        activeSessions.put(audioId, session);
        
        // Send request
        String json = P2PMessageProtocol.buildAudioRequest(
            null,
            toUserId,
            conversationId,
            duration,
            audioId
        );
        
        PeerConnection conn = p2pManager.getOrCreateConnection(toUserId);
        if (conn != null) {
            conn.sendTcp(json);
            System.out.println("📤 Sent audio request: " + duration + "ms");
        }
        
        return audioId;
    }
    
    /**
     * Bắt đầu gửi audio sau khi được accept
     */
    public void startAudioSend(String audioId, P2PManager p2pManager) {
        AudioTransferSession session = activeSessions.get(audioId);
        if (session == null || !session.isSending) {
            System.err.println("Invalid audio session: " + audioId);
            return;
        }
        
        new Thread(() -> {
            try {
                sendAudioChunks(session, p2pManager);
            } catch (Exception e) {
                System.err.println("Error sending audio: " + e.getMessage());
                if (listener != null) {
                    listener.onAudioError(audioId, e.getMessage());
                }
            } finally {
                activeSessions.remove(audioId);
            }
        }, "audio-sender-" + audioId).start();
    }
    
    private void sendAudioChunks(AudioTransferSession session, P2PManager p2pManager) throws IOException {
        File audioFile = session.audioFile;
        byte[] fileData = Files.readAllBytes(audioFile.toPath());
        
        int totalChunks = (int) Math.ceil((double) fileData.length / CHUNK_SIZE);
        
        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            int offset = chunkIndex * CHUNK_SIZE;
            int length = Math.min(CHUNK_SIZE, fileData.length - offset);
            
            byte[] chunkData = new byte[length];
            System.arraycopy(fileData, offset, chunkData, 0, length);
            
            // Send chunk
            String json = P2PMessageProtocol.buildAudioChunk(
                null,
                session.peerId,
                session.audioId,
                chunkIndex,
                totalChunks,
                chunkData
            );
            
            PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
            if (conn != null) {
                conn.sendTcp(json);
            }
            
            // Update progress
            int progress = (int) (((chunkIndex + 1) * 100.0) / totalChunks);
            if (listener != null) {
                listener.onAudioProgress(session.audioId, progress);
            }
            
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        
        // Send complete
        String completeJson = P2PMessageProtocol.buildAudioComplete(
            null,
            session.peerId,
            session.audioId
        );
        
        PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
        if (conn != null) {
            conn.sendTcp(completeJson);
        }
        
        System.out.println("✅ Audio sent successfully");
    }
    
    // ===== RECEIVING AUDIO =====
    
    public void handleAudioRequest(P2PMessageProtocol.Message msg) {
        String audioId = (String) msg.data.get("audioId");
        Number duration = (Number) msg.data.get("duration");
        
        AudioTransferSession session = new AudioTransferSession(
            audioId, null, msg.from, msg.conversationId, duration.longValue(), false
        );
        activeSessions.put(audioId, session);
        
        if (listener != null) {
            listener.onAudioRequest(msg.from, audioId, duration.longValue());
        }
    }
    
    public void acceptAudio(String audioId, P2PManager p2pManager) {
        AudioTransferSession session = activeSessions.get(audioId);
        if (session == null) return;
        
        String json = P2PMessageProtocol.buildAudioAccept(null, session.peerId, audioId);
        PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
        if (conn != null) {
            conn.sendTcp(json);
        }
        
        session.receivedChunks = new ConcurrentHashMap<>();
    }
    
    public void rejectAudio(String audioId, P2PManager p2pManager) {
        AudioTransferSession session = activeSessions.get(audioId);
        if (session == null) return;
        
        String json = P2PMessageProtocol.buildAudioReject(null, session.peerId, audioId);
        PeerConnection conn = p2pManager.getOrCreateConnection(session.peerId);
        if (conn != null) {
            conn.sendTcp(json);
        }
        
        activeSessions.remove(audioId);
    }
    
    public void handleAudioChunk(P2PMessageProtocol.Message msg) {
        String audioId = (String) msg.data.get("audioId");
        Number chunkIndex = (Number) msg.data.get("chunkIndex");
        Number totalChunks = (Number) msg.data.get("totalChunks");
        String audioDataBase64 = (String) msg.data.get("audioData");
        
        AudioTransferSession session = activeSessions.get(audioId);
        if (session == null || session.isSending) return;
        
        byte[] chunkData = java.util.Base64.getDecoder().decode(audioDataBase64);
        session.receivedChunks.put(chunkIndex.intValue(), chunkData);
        
        int progress = (int) ((session.receivedChunks.size() * 100.0) / totalChunks.intValue());
        if (listener != null) {
            listener.onAudioProgress(audioId, progress);
        }
    }
    
    public void handleAudioComplete(P2PMessageProtocol.Message msg) {
        String audioId = (String) msg.data.get("audioId");
        AudioTransferSession session = activeSessions.get(audioId);
        
        if (session == null || session.isSending) return;
        
        try {
            // Reconstruct audio file
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int i = 0; i < session.receivedChunks.size(); i++) {
                byte[] chunk = session.receivedChunks.get(i);
                if (chunk != null) {
                    baos.write(chunk);
                }
            }
            
            String fileName = "received_audio_" + System.currentTimeMillis() + ".wav";
            Path savePath = Paths.get(AUDIO_DIR, fileName);
            
            Files.write(savePath, baos.toByteArray());
            
            System.out.println("✅ Audio received: " + savePath);
            
            if (listener != null) {
                listener.onAudioCompleted(audioId, savePath);
            }
            
        } catch (IOException e) {
            System.err.println("Error saving audio: " + e.getMessage());
            if (listener != null) {
                listener.onAudioError(audioId, e.getMessage());
            }
        } finally {
            activeSessions.remove(audioId);
        }
    }
    
    public void handleAudioAccept(P2PMessageProtocol.Message msg, P2PManager p2pManager) {
        String audioId = (String) msg.data.get("audioId");
        
        if (listener != null) {
            listener.onAudioAccepted(audioId);
        }
        
        startAudioSend(audioId, p2pManager);
    }
    
    public void handleAudioReject(P2PMessageProtocol.Message msg) {
        String audioId = (String) msg.data.get("audioId");
        
        if (listener != null) {
            listener.onAudioRejected(audioId);
        }
        
        activeSessions.remove(audioId);
    }
    
    // ===== HELPERS =====
    
    private long calculateAudioDuration(File audioFile) {
        try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile)) {
            AudioFormat format = audioStream.getFormat();
            long frames = audioStream.getFrameLength();
            double durationInSeconds = (frames + 0.0) / format.getFrameRate();
            return (long) (durationInSeconds * 1000);
        } catch (Exception e) {
            return 0;
        }
    }
    
    public void setListener(AudioTransferListener listener) {
        this.listener = listener;
    }
    
    public boolean isRecording() {
        return isRecording;
    }
    
    // ===== AUDIO TRANSFER SESSION =====
    
    public static class AudioTransferSession {
        public final String audioId;
        public final File audioFile;
        public final Integer peerId;
        public final Integer conversationId;
        public final Long duration;
        public final boolean isSending;
        
        public Map<Integer, byte[]> receivedChunks;
        
        public AudioTransferSession(String audioId, File audioFile, Integer peerId,
                                   Integer conversationId, Long duration, boolean isSending) {
            this.audioId = audioId;
            this.audioFile = audioFile;
            this.peerId = peerId;
            this.conversationId = conversationId;
            this.duration = duration;
            this.isSending = isSending;
        }
    }
}
