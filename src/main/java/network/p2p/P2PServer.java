package network.p2p;

import protocol.P2PMessageProtocol;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import network.p2p.PeerConnection.P2PMessageHandler;

/**
 * P2PServer - Lắng nghe incoming P2P connections từ peers khác
 */
public class P2PServer {

	private final int port;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptorThread;
    private final Set<IncomingPeerHandler> handlers = new CopyOnWriteArraySet<>();
    
 // Handler cho incoming messages
    private P2PMessageHandler messageHandler;
    
    public interface P2PMessageHandler {
        void onMessageReceived(Integer fromUserId, P2PMessageProtocol.Message message);
    }

    public P2PServer(int port) {
        this.port = port;
    }
    
    /**
     * Khởi động server
     */
    public void start() {
        if (running) return;
        
        acceptorThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                running = true;
                System.out.println("✅ P2P Server started on port " + port);
                
                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    IncomingPeerHandler handler = new IncomingPeerHandler(clientSocket);
                    handlers.add(handler);
                    new Thread(handler, "p2p-incoming-" + clientSocket.getInetAddress()).start();
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("❌ P2P Server error: " + e.getMessage());
                }
            }
        }, "p2p-acceptor");
        
        acceptorThread.setDaemon(true);
        acceptorThread.start();
    }
    
    /**
     * Dừng server
     */
    public void stop() {
        running = false;
        
        handlers.forEach(IncomingPeerHandler::close);
        handlers.clear();
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        
        if (acceptorThread != null) {
            acceptorThread.interrupt();
        }
    }
    
    public void setMessageHandler(P2PMessageHandler handler) {
        this.messageHandler = handler;
    }
    
// ===== INCOMING PEER HANDLER =====
    
    private class IncomingPeerHandler implements Runnable {
        private final Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;
        private volatile boolean active = true;
        private Integer remotePeerId;

        public IncomingPeerHandler(Socket socket) {
            this.socket = socket;
            try {
                this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                this.writer = new PrintWriter(socket.getOutputStream(), true);
            } catch (IOException e) {
                close();
            }
        }
     
        @Override
        public void run() {
            System.out.println("✅ New P2P connection from " + socket.getInetAddress());
            
            try {
                String line;
                while (active && (line = reader.readLine()) != null) {
                    handleMessage(line);
                }
            } catch (IOException e) {
                if (active) {
                    System.err.println("⚠️ P2P connection lost: " + socket.getInetAddress());
                }
            } finally {
                close();
            }
        }
        
        private void handleMessage(String json) {
            P2PMessageProtocol.Message msg = P2PMessageProtocol.parse(json);
            
            if (!P2PMessageProtocol.isValid(msg)) {
                System.err.println("❌ Invalid P2P message received");
                return;
            }

            // Lưu lại peer ID từ message đầu tiên
            if (remotePeerId == null) {
                remotePeerId = msg.from;
                System.out.println("✅ Identified peer: " + remotePeerId);
            }

            // Forward tới message handler
            if (messageHandler != null) {
                messageHandler.onMessageReceived(remotePeerId, msg);
            }
        }
        
        public void send(String json) {
            if (writer != null && !socket.isClosed()) {
                synchronized (writer) {
                    writer.println(json);
                    writer.flush();
                }
            }
        }

        public void close() {
            active = false;
            
            try { if (reader != null) reader.close(); } catch (IOException ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            
            handlers.remove(this);
        }
    
    }
}
