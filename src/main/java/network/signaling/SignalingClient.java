package network.signaling;

import com.google.gson.Gson;
import network.p2p.PeerDiscoveryService;

import java.io.*;
import java.net.Socket;
import java.util.function.Consumer;

public class SignalingClient {
	private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread readerThread;
    private volatile boolean connected = false;
    private final Gson gson = new Gson();
    private final PeerDiscoveryService discoveryService = PeerDiscoveryService.getInstance();

    private Consumer<PeerDiscoveryService.PeerUpdateResult> peerUpdateListener;

    public SignalingClient(String host, int port) {
        this.host = host;
        this.port = port;
    }
    
    /**
     * Kết nối tới signaling server
     */
    public boolean connect(int timeoutMs) {
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
            
            connected = true;
            startReader();
            
            System.out.println("✅ Connected to Signaling Server: " + host + ":" + port);
            
            return true;
        } catch (IOException e) {
            System.err.println("❌ Failed to connect to signaling server: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Gửi lệnh LOGIN
     */
    public boolean login(String username, int p2pPort) {
    	if (!connected) {
            System.err.println("❌ Not connected to signaling server");
            return false;
        }
        
        String cmd = "LOGIN," + username + "," + p2pPort;
        writer.println(cmd);
        System.out.println("📤 Sent LOGIN command");
        return true;
    }

    /**
     * Gửi lệnh LOGOUT
     */
    public void logout() {
        if (connected) {
            writer.println("LOGOUT");
            System.out.println("📤 Sent LOGOUT command");
        }
    }
    
    /**
     * Ngắt kết nối
     */
    public void disconnect() {
        connected = false;
        
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        
        if (readerThread != null) {
            readerThread.interrupt();
        }
        System.out.println("👋 Disconnected from Signaling Server");
    }
    
    /**
     * Khởi động thread đọc messages từ server
     */
    private void startReader() {
        readerThread = new Thread(() -> {
            try {
                String line;
                while (connected && (line = reader.readLine()) != null) {
                    handleServerMessage(line);
                }
            } catch (IOException e) {
                if (connected) {
                    System.err.println("⚠️ Connection lost to signaling server");
                }
            } finally {
                connected = false;
            }
        }, "signaling-reader");
        
        readerThread.setDaemon(true);
        readerThread.start();
    }
    
    /**
    * Xử lý message từ server (peer list updates)
    */
   private void handleServerMessage(String json) {
       if (json == null || json.trim().isEmpty()) return;
       
       if (json.contains("\"error\"")) {
           System.err.println("❌ Server error: " + json);
           return;
       }
       
       // Parse và update peer list
       PeerDiscoveryService.PeerUpdateResult result = discoveryService.processServerMessage(json, null);
       
       if (!result.isEmpty() && peerUpdateListener != null) {
           peerUpdateListener.accept(result);
       }
   }
   
   /**
    * Đăng ký listener cho peer updates
    */
   public void setPeerUpdateListener(Consumer<PeerDiscoveryService.PeerUpdateResult> listener) {
       this.peerUpdateListener = listener;
   }

   public boolean isConnected() {
       return connected && socket != null && socket.isConnected();
   }
}
