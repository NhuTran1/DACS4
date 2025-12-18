package network.p2p;

import protocol.P2PMessageProtocol;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioCallManager - Quản lý voice call qua UDP
 * - Capture audio từ microphone
 * - Gửi qua UDP tới peer
 * - Nhận và play audio từ peer
 */
public class AudioCallManager {
    
    // Audio format config
    private static final float SAMPLE_RATE = 16000; // 16kHz
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1; // Mono
    private static final int FRAME_SIZE = 640; // 20ms at 16kHz
    private static final int UDP_PORT_BASE = 17000;
    
    private final P2PManager p2pManager;
    private final Map<String, ActiveCall> activeCalls = new ConcurrentHashMap<>();
    private AudioCallListener listener;
    
    // Audio components
    private TargetDataLine microphone;
    private SourceDataLine speaker;
    private DatagramSocket udpSocket;
    
    public interface AudioCallListener {
        void onCallRequested(Integer fromUser, String callId);
        void onCallAccepted(Integer fromUser, String callId);
        void onCallRejected(Integer fromUser, String callId, String reason);
        void onCallStarted(String callId);
        void onCallEnded(String callId);
        void onCallError(String callId, String error);
    }

    public AudioCallManager(P2PManager p2pManager) {
        this.p2pManager = p2pManager;
    }

    public void setListener(AudioCallListener listener) {
        this.listener = listener;
    }

    // ===== OUTGOING CALL =====
    
    /**
     * Bắt đầu voice call với peer
     */
    public String startCall(Integer toUserId) throws Exception {
        String callId = UUID.randomUUID().toString();
        
        ActiveCall call = new ActiveCall(callId, toUserId, true);
        activeCalls.put(callId, call);

        // Gửi audio request
        String json = P2PMessageProtocol.buildAudioRequest(
            p2pManager.getLocalUserId(),
            toUserId,
            callId
        );
        
        PeerConnection conn = p2pManager.getConnection(toUserId);
        if (conn == null || !conn.isTcpConnected()) {
            throw new Exception("Not connected to peer: " + toUserId);
        }
        
        conn.sendTcp(json);
        System.out.println("📞 Sent call request to user: " + toUserId);
        
        return callId;
    }

    /**
     * Xử lý call accept từ peer
     */
    public void handleCallAccept(P2PMessageProtocol.Message msg) {
        String callId = (String) msg.data.get("callId");
        Number udpPort = (Number) msg.data.get("udpPort");
        
        ActiveCall call = activeCalls.get(callId);
        if (call == null) {
            System.err.println("❌ Unknown call: " + callId);
            return;
        }

        call.peerUdpPort = udpPort.intValue();
        call.status = CallStatus.ACTIVE;
        
        if (listener != null) {
            listener.onCallAccepted(msg.from, callId);
        }

        // Bắt đầu audio streaming
        try {
            PeerInfo peer = network.p2p.PeerDiscoveryService.getInstance().getPeer(msg.from);
            if (peer != null) {
                call.peerAddress = InetAddress.getByName(peer.getIp());
                startAudioStreaming(call);
            }
        } catch (Exception e) {
            System.err.println("❌ Error starting audio: " + e.getMessage());
            endCall(callId);
        }
    }

    // ===== INCOMING CALL =====
    
    /**
     * Xử lý incoming call request
     */
    public void handleCallRequest(P2PMessageProtocol.Message msg) {
        String callId = (String) msg.data.get("callId");
        
        ActiveCall call = new ActiveCall(callId, msg.from, false);
        activeCalls.put(callId, call);

        if (listener != null) {
            listener.onCallRequested(msg.from, callId);
        }
    }

    /**
     * Accept incoming call
     */
    public void acceptCall(String callId) {
        ActiveCall call = activeCalls.get(callId);
        if (call == null) {
            System.err.println("❌ Unknown call: " + callId);
            return;
        }

        try {
            // Mở UDP socket
            int udpPort = UDP_PORT_BASE + p2pManager.getLocalUserId();
            udpSocket = new DatagramSocket(udpPort);
            call.localUdpPort = udpPort;
            call.status = CallStatus.ACTIVE;

            // Gửi accept với UDP port
            String json = P2PMessageProtocol.buildAudioAccept(
                p2pManager.getLocalUserId(),
                call.peerId,
                callId,
                udpPort
            );
            
            PeerConnection conn = p2pManager.getConnection(call.peerId);
            if (conn != null) {
                conn.sendTcp(json);
            }

            // Lấy peer address
            PeerInfo peer = network.p2p.PeerDiscoveryService.getInstance().getPeer(call.peerId);
            if (peer != null) {
                call.peerAddress = InetAddress.getByName(peer.getIp());
                call.peerUdpPort = UDP_PORT_BASE + call.peerId; // Giả sử peer dùng cùng port scheme
            }

            // Bắt đầu audio streaming
            startAudioStreaming(call);
            
            if (listener != null) {
                listener.onCallStarted(callId);
            }

        } catch (Exception e) {
            System.err.println("❌ Error accepting call: " + e.getMessage());
            
            if (listener != null) {
                listener.onCallError(callId, e.getMessage());
            }
            
            activeCalls.remove(callId);
        }
    }

    /**
     * Reject incoming call
     */
    public void rejectCall(String callId, String reason) {
        ActiveCall call = activeCalls.get(callId);
        if (call == null) return;

        call.status = CallStatus.ENDED;
        
        String json = P2PMessageProtocol.buildAudioReject(
            p2pManager.getLocalUserId(),
            call.peerId,
            callId,
            reason
        );
        
        PeerConnection conn = p2pManager.getConnection(call.peerId);
        if (conn != null) {
            conn.sendTcp(json);
        }
        
        if (listener != null) {
            listener.onCallRejected(call.peerId, callId, reason);
        }
        
        activeCalls.remove(callId);
    }

    // ===== AUDIO STREAMING =====
    
    /**
     * Bắt đầu capture và stream audio
     */
    private void startAudioStreaming(ActiveCall call) throws Exception {
        // Khởi tạo audio format
        AudioFormat format = new AudioFormat(
            SAMPLE_RATE,
            SAMPLE_SIZE_BITS,
            CHANNELS,
            true, // signed
            false // little endian
        );

        // Mở microphone
        DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(micInfo)) {
            throw new Exception("Microphone not supported");
        }
        
        microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
        microphone.open(format);
        microphone.start();

        // Mở speaker
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(speakerInfo)) {
            throw new Exception("Speaker not supported");
        }
        
        speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        speaker.open(format);
        speaker.start();

        // Mở UDP socket nếu chưa có
        if (udpSocket == null || udpSocket.isClosed()) {
            int udpPort = UDP_PORT_BASE + p2pManager.getLocalUserId();
            udpSocket = new DatagramSocket(udpPort);
            call.localUdpPort = udpPort;
        }

        call.running.set(true);

        // Thread gửi audio
        call.senderThread = new Thread(() -> audioSendLoop(call), "audio-sender-" + call.callId);
        call.senderThread.start();

        // Thread nhận audio
        call.receiverThread = new Thread(() -> audioReceiveLoop(call), "audio-receiver-" + call.callId);
        call.receiverThread.start();

        System.out.println("✅ Audio streaming started");
        
        if (listener != null) {
            listener.onCallStarted(call.callId);
        }
    }

    /**
     * Loop gửi audio data
     */
    private void audioSendLoop(ActiveCall call) {
        byte[] buffer = new byte[FRAME_SIZE];
        
        try {
            while (call.running.get() && !Thread.currentThread().isInterrupted()) {
                int bytesRead = microphone.read(buffer, 0, buffer.length);
                
                if (bytesRead > 0 && call.peerAddress != null && call.peerUdpPort > 0) {
                    DatagramPacket packet = new DatagramPacket(
                        buffer,
                        bytesRead,
                        call.peerAddress,
                        call.peerUdpPort
                    );
                    
                    udpSocket.send(packet);
                }
            }
        } catch (Exception e) {
            if (call.running.get()) {
                System.err.println("❌ Error sending audio: " + e.getMessage());
            }
        }
        
        System.out.println("👋 Audio sender stopped");
    }

    /**
     * Loop nhận audio data
     */
    private void audioReceiveLoop(ActiveCall call) {
        byte[] buffer = new byte[FRAME_SIZE * 2];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        
        try {
            while (call.running.get() && !Thread.currentThread().isInterrupted()) {
                udpSocket.receive(packet);
                
                // Play audio
                speaker.write(packet.getData(), 0, packet.getLength());
            }
        } catch (Exception e) {
            if (call.running.get()) {
                System.err.println("❌ Error receiving audio: " + e.getMessage());
            }
        }
        
        System.out.println("👋 Audio receiver stopped");
    }

    // ===== END CALL =====
    
    /**
     * Kết thúc call
     */
    public void endCall(String callId) {
        ActiveCall call = activeCalls.get(callId);
        if (call == null) return;

        call.running.set(false);
        call.status = CallStatus.ENDED;

        // Gửi end message
        String json = P2PMessageProtocol.buildAudioEnd(
            p2pManager.getLocalUserId(),
            call.peerId,
            callId
        );
        
        PeerConnection conn = p2pManager.getConnection(call.peerId);
        if (conn != null) {
            conn.sendTcp(json);
        }

        // Cleanup audio resources
        cleanup();

        if (listener != null) {
            listener.onCallEnded(callId);
        }

        activeCalls.remove(callId);
        System.out.println("📞 Call ended: " + callId);
    }

    /**
     * Xử lý end call từ peer
     */
    public void handleCallEnd(String callId) {
        ActiveCall call = activeCalls.get(callId);
        if (call == null) return;

        call.running.set(false);
        call.status = CallStatus.ENDED;

        cleanup();

        if (listener != null) {
            listener.onCallEnded(callId);
        }

        activeCalls.remove(callId);
    }

    // ===== CLEANUP =====
    
    private void cleanup() {
        try {
            if (microphone != null) {
                microphone.stop();
                microphone.close();
                microphone = null;
            }
        } catch (Exception ignored) {}

        try {
            if (speaker != null) {
                speaker.stop();
                speaker.close();
                speaker = null;
            }
        } catch (Exception ignored) {}

        // Note: không đóng udpSocket ở đây vì có thể có nhiều calls
    }

    public void shutdown() {
        // End all active calls
        activeCalls.keySet().forEach(this::endCall);
        
        // Close UDP socket
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }

    // ===== INNER CLASSES =====
    
    private enum CallStatus {
        PENDING, ACTIVE, ENDED
    }

    private static class ActiveCall {
        String callId;
        Integer peerId;
        boolean isOutgoing;
        CallStatus status = CallStatus.PENDING;
        
        InetAddress peerAddress;
        int peerUdpPort;
        int localUdpPort;
        
        AtomicBoolean running = new AtomicBoolean(false);
        Thread senderThread;
        Thread receiverThread;

        ActiveCall(String callId, Integer peerId, boolean isOutgoing) {
            this.callId = callId;
            this.peerId = peerId;
            this.isOutgoing = isOutgoing;
        }
    }
}