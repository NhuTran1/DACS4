package network.p2p;

import protocol.P2PMessageProtocol;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PeerConnection - Quản lý kết nối P2P với 1 peer
 * - TCP: Chat message, typing, file transfer signaling
 * - UDP: Voice call (RTP)
 */
public class PeerConnection {
    private final PeerInfo remotePeer;
    
    // TCP components
    private Socket tcpSocket;
    private BufferedReader tcpReader;
    private PrintWriter tcpWriter;
    private final AtomicBoolean tcpConnected = new AtomicBoolean(false);
    private Thread tcpReaderThread;
    
    // UDP components (cho voice call)
    private DatagramSocket udpSocket;
    private final AtomicBoolean udpOpen = new AtomicBoolean(false);
    
    // Message handler
    private P2PMessageHandler messageHandler;
    
    // Thread pool
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public interface P2PMessageHandler {
        void onMessageReceived(P2PMessageProtocol.Message message);
        void onConnectionLost();
    }

    

    public PeerConnection(PeerInfo remotePeer) {
        this.remotePeer = remotePeer;
    }
    
    /**
     * Kết nối TCP tới peer
     */
    public boolean connectTcp(int timeoutMs) {
        if (tcpConnected.get()) return true;
        
        try {
            tcpSocket = new Socket();
            tcpSocket.connect(
                new InetSocketAddress(remotePeer.getIp(), remotePeer.getPort()), 
                timeoutMs
            );
            
            tcpReader = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            tcpWriter = new PrintWriter(tcpSocket.getOutputStream(), true);
            
            tcpConnected.set(true);
            startTcpReader();
            
            System.out.println("✅ TCP connected to peer: " + remotePeer.getUserId());
            return true;
            
        } catch (IOException e) {
            System.err.println("❌ Failed to connect TCP to peer " + remotePeer.getUserId() + ": " + e.getMessage());
            closeTcp();
            return false;
        }
    }

    /**
     * Khởi động thread đọc TCP messages
     */
    private void startTcpReader() {
        tcpReaderThread = new Thread(() -> {
            try {
                String line;
                while (tcpConnected.get() && (line = tcpReader.readLine()) != null) {
                    handleIncomingMessage(line);
                }
            } catch (IOException e) {
                if (tcpConnected.get()) {
                    System.err.println("⚠️ TCP connection lost to peer " + remotePeer.getUserId());
                }
            } finally {
                closeTcp();
                if (messageHandler != null) {
                    messageHandler.onConnectionLost();
                }
            }
        }, "tcp-reader-" + remotePeer.getUserId());
        
        tcpReaderThread.setDaemon(true);
        tcpReaderThread.start();
    }

    /**
     * Xử lý message nhận được
     */
    private void handleIncomingMessage(String json) {
        if (messageHandler == null) return;
        
        P2PMessageProtocol.Message msg = P2PMessageProtocol.parse(json);
        if (P2PMessageProtocol.isValid(msg)) {
            executor.submit(() -> messageHandler.onMessageReceived(msg));
        }
    }

    /**
     * Gửi message qua TCP
     */
    public boolean sendTcp(String json) {
        if (!tcpConnected.get() || tcpWriter == null) {
            System.err.println("❌ TCP not connected to peer " + remotePeer.getUserId());
            return false;
        }
        
        try {
            synchronized (tcpWriter) {
                tcpWriter.println(json);
                tcpWriter.flush();
            }
            return true;
        } catch (Exception e) {
            System.err.println("❌ Failed to send TCP message: " + e.getMessage());
            return false;
        }
    }

    /**
     * Đóng kết nối TCP
     */
    public void closeTcp() {
        tcpConnected.set(false);
        
        try { if (tcpReader != null) tcpReader.close(); } catch (IOException ignored) {}
        try { if (tcpWriter != null) tcpWriter.close(); } catch (Exception ignored) {}
        try { if (tcpSocket != null) tcpSocket.close(); } catch (IOException ignored) {}
        
        if (tcpReaderThread != null) {
            tcpReaderThread.interrupt();
        }
    }

    // ===== UDP CONNECTION (for voice call) =====
    
    /**
     * Mở UDP socket cho voice call
     */
    public boolean openUdp(int localPort) {
        try {
            if (udpSocket == null || udpSocket.isClosed()) {
                udpSocket = new DatagramSocket(localPort);
                udpOpen.set(true);
                System.out.println("✅ UDP opened on port " + localPort);
                return true;
            }
            return true;
        } catch (SocketException e) {
            System.err.println("❌ Failed to open UDP: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gửi voice data qua UDP
     */
    public boolean sendUdpData(byte[] data) {
        if (!udpOpen.get() || udpSocket == null) return false;
        
        try {
            InetAddress address = InetAddress.getByName(remotePeer.getIp());
            DatagramPacket packet = new DatagramPacket(
                data, 
                data.length, 
                address, 
                remotePeer.getPort() + 1  // Voice port = P2P port + 1
            );
            udpSocket.send(packet);
            return true;
        } catch (IOException e) {
            System.err.println("❌ Failed to send UDP data: " + e.getMessage());
            return false;
        }
    }

    /**
     * Nhận voice data từ UDP (blocking)
     */
    public byte[] receiveUdpData(int bufferSize) throws IOException {
        if (!udpOpen.get() || udpSocket == null) {
            throw new IOException("UDP socket not open");
        }
        
        byte[] buffer = new byte[bufferSize];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        udpSocket.receive(packet);
        
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
        return data;
    }

    /**
     * Đóng UDP socket
     */
    public void closeUdp() {
        udpOpen.set(false);
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
    }

    // ===== GETTERS/SETTERS =====
    
    public void setMessageHandler(P2PMessageHandler handler) {
        this.messageHandler = handler;
    }

    public boolean isTcpConnected() {
        return tcpConnected.get();
    }

    public boolean isUdpOpen() {
        return udpOpen.get();
    }

    public PeerInfo getRemotePeer() {
        return remotePeer;
    }

    /**
     * Đóng toàn bộ kết nối
     */
    public void closeAll() {
        closeTcp();
        closeUdp();
        executor.shutdownNow();
    }
}