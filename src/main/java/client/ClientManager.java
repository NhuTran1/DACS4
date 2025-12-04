package client;
import network.signaling.SignalingClient;
import network.p2p.P2PManager;
import network.p2p.P2PServer;
import network.p2p.PeerDiscoveryService;
import network.p2p.PeerInfo;
import service.ChatService;
import dao.UserDao;
import model.Users;

import java.util.Random;
import java.util.List;
import java.util.stream.Collectors;

public class ClientManager {
	private final Integer userId;
    private final String username;
    private final int p2pPort;
    
    private SignalingClient signalingClient;
    private P2PServer p2pServer;
    private P2PManager p2pManager;
    private final ChatService chatService;
    private final UserDao userDao;
    private final PeerDiscoveryService discoveryService;
    
    private volatile boolean running = false;
    
 // Config
    private static final String SIGNALING_HOST = "localhost";
    private static final int SIGNALING_PORT = 7002;
    private static final int P2P_PORT_BASE = 7010;

    public ClientManager(Integer userId, String username) {
        this.userId = userId;
        this.username = username;
        this.p2pPort = generateP2PPort();
        this.chatService = new ChatService();
        this.userDao = new UserDao();
        this.discoveryService = PeerDiscoveryService.getInstance();
    }
    
    /**
     * Khởi động toàn bộ P2P infrastructure
     */
    public boolean start() {
        if (running) {
            System.out.println("⚠️ ClientManager already running");
            return true;
        }

        System.out.println("🚀 Starting P2P Infrastructure...");
        System.out.println("   User: " + username + " (ID: " + userId + ")");
        System.out.println("   P2P Port: " + p2pPort);
        
        try {
            // 1. Khởi động P2P Server (lắng nghe incoming connections)
            if (!startP2PServer()) {
                System.err.println("❌ Failed to start P2P Server");
                return false;
            }

            // 2. Khởi tạo P2P Manager
            initializeP2PManager();

            // 3. Kết nối tới Signaling Server
            if (!connectToSignalingServer()) {
                System.err.println("❌ Failed to connect to Signaling Server");
                stopP2PServer();
                return false;
            }

            // 4. Load friend list và subscribe để chỉ nhận updates từ friends
            loadFriendSubscriptions();

            running = true;
            System.out.println("✅ P2P Infrastructure started successfully\n");
            return true;

        } catch (Exception e) {
            System.err.println("❌ Error starting ClientManager: " + e.getMessage());
            e.printStackTrace();
            shutdown();
            return false;
        }
    }

	private boolean startP2PServer() {
		 try {
		        p2pServer = new P2PServer(p2pPort);
		        p2pServer.start();
		        
		      
		        Thread.sleep(500);
		        
		        System.out.println("✅ P2P Server started on port " + p2pPort);
		        return true;
		    } catch (Exception e) {
		        System.err.println("❌ Failed to start P2P Server: " + e.getMessage());
		        return false;
		    }
	}
	
	private void initializeP2PManager() {
		p2pManager = new P2PManager(userId, chatService);
	        
        // Connect P2PServer với P2PManager để forward incoming messages
        p2pServer.setMessageHandler((fromUserId, message) -> {
            p2pManager.onMessageReceived(message);
        });
        
        System.out.println("✅ P2P Manager initialized");
	}
    
	private boolean connectToSignalingServer() {
        signalingClient = new SignalingClient(SIGNALING_HOST, SIGNALING_PORT);
        
        // Đăng ký listener cho peer updates
        signalingClient.setPeerUpdateListener(this::handlePeerUpdate);
        
        // Kết nối
        if (!signalingClient.connect(3000)) {
            System.err.println("❌ Cannot connect to Signaling Server at " + SIGNALING_HOST + ":" + SIGNALING_PORT);
            return false;
        }

        // Login
        if (!signalingClient.login(username, p2pPort)) {
            System.err.println("❌ Login to Signaling Server failed");
            signalingClient.disconnect();
            return false;
        }

        System.out.println("✅ Connected to Signaling Server");
        return true;
    }
	
	/**
     * Load danh sách friends và subscribe vào PeerDiscoveryService
     * Chỉ nhận updates từ friends
     */
    private void loadFriendSubscriptions() {
        try {
            List<Users> friends = chatService.listFriends(userId);
            List<Integer> friendIds = friends.stream()
                .map(Users::getId)
                .collect(Collectors.toList());
            
            // Subscribe để chỉ nhận updates từ friends
            discoveryService.setSubscriptions(userId, friendIds);
            
            System.out.println("✅ Subscribed to " + friendIds.size() + " friends");
        } catch (Exception e) {
            System.err.println("⚠️ Failed to load friend subscriptions: " + e.getMessage());
        }
    }

    /**
     * Xử lý peer updates từ Signaling Server
     */
    private void handlePeerUpdate(PeerDiscoveryService.PeerUpdateResult result) {
        // Peers online mới
        result.added.forEach(peer -> {
            System.out.println("  ✅ " + getUserName(peer.getUserId()) + " online");
            
            // Tự động kết nối P2P tới peer mới
            if (!peer.getUserId().equals(userId)) {
                connectToPeerAsync(peer.getUserId());
            }
        });

        // Peers offline
        result.removed.forEach(peer -> {
            System.out.println("  ❌ " + getUserName(peer.getUserId()) + " offline");
            
            // Ngắt kết nối P2P
            if (p2pManager != null) {
                p2pManager.disconnectPeer(peer.getUserId());
            }
        });

        // Peers updated (thay đổi IP/port)
        result.updated.forEach(peer -> {
            System.out.println("  🔄 " + getUserName(peer.getUserId()) + " updated connection info");
            
            // Reconnect với thông tin mới
            if (p2pManager != null && !peer.getUserId().equals(userId)) {
                p2pManager.disconnectPeer(peer.getUserId());
                connectToPeerAsync(peer.getUserId());
            }
        });
    }
    
    /**
    * Kết nối P2P tới peer (async để không block)
    */
   private void connectToPeerAsync(Integer peerId) {
       new Thread(() -> {
           try {
               // Chờ một chút để peer khởi động P2P server của họ
               Thread.sleep(1000);
               
               boolean connected = p2pManager.connectToPeer(peerId);
               if (connected) {
                   System.out.println("✅ P2P connected to " + getUserName(peerId));
               } else {
                   System.out.println("⚠️ Failed to connect P2P to " + getUserName(peerId));
               }
           } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
           }
       }, "p2p-connector-" + peerId).start();
   }
    
    
    /**
    * Danh sách peers hiện tại đang online
    */
   public List<PeerInfo> getOnlinePeers() {
       return discoveryService.getOnlinePeersForUser(userId);
   }
   
   /**
    * Tắt toàn bộ P2P infrastructure
    */
   public void shutdown() {
       if (!running && signalingClient == null && p2pManager == null && p2pServer == null) {
           return;
       }

       System.out.println("\n👋 Shutting down P2P Infrastructure...");
       running = false;
       
       // 1. Logout khỏi Signaling Server
       if (signalingClient != null) {
           try {
               signalingClient.logout();
               signalingClient.disconnect();
               System.out.println("✅ Logged out from Signaling Server");
           } catch (Exception e) {
               System.err.println("⚠️ Error during signaling logout: " + e.getMessage());
           }
       }
       
       // 2. Đóng tất cả P2P connections
       if (p2pManager != null) {
           try {
               p2pManager.shutdown();
               System.out.println("✅ P2P Manager shutdown");
           } catch (Exception e) {
               System.err.println("⚠️ Error during P2P Manager shutdown: " + e.getMessage());
           }
       }
       
       // 3. Dừng P2P Server
       if (p2pServer != null) {
           try {
               p2pServer.stop();
               System.out.println("✅ P2P Server stopped");
           } catch (Exception e) {
               System.err.println("⚠️ Error stopping P2P Server: " + e.getMessage());
           }
       }
       
       System.out.println("✅ P2P Infrastructure shutdown complete");
   }
   
// ===== GETTERS =====

   public P2PManager getP2pManager() {
       return p2pManager;
   }

   public ChatService getChatService() {
       return chatService;
   }

   public Integer getUserId() {
       return userId;
   }

   public String getUsername() {
       return username;
   }

   public int getP2pPort() {
       return p2pPort;
   }

   public boolean isRunning() {
       return running;
   }

   public boolean isConnectedToSignalingServer() {
       return signalingClient != null && signalingClient.isConnected();
   }

   // ===== HELPER METHODS =====

   private String getUserName(Integer userId) {
       Users user = userDao.findById(userId);
       return user != null ? user.getDisplayName() : "User" + userId;
   }

   /**
    * Generate P2P port dựa trên userId để tránh conflict
    */
   private int generateP2PPort() {
       // Base port + userId để mỗi user có port riêng
       // Fallback: random port nếu không có userId
       if (userId != null && userId > 0 && userId < 1000) {
           return P2P_PORT_BASE + userId;
       }
       return P2P_PORT_BASE + new Random().nextInt(1000);
   }

   /**
    * Kiểm tra xem có thể kết nối tới peer không
    */
   public boolean canConnectToPeer(Integer peerId) {
       PeerInfo peer = discoveryService.getPeer(peerId);
       return peer != null;
   }

   /**
    * Force reconnect tới peer
    */
   public void reconnectToPeer(Integer peerId) {
       if (p2pManager != null) {
           p2pManager.disconnectPeer(peerId);
           connectToPeerAsync(peerId);
       }
   }

   /**
    * Kiểm tra kết nối P2P tới peer
    */
   public boolean isPeerConnected(Integer peerId) {
       return p2pManager != null && p2pManager.connectToPeer(peerId);
   }

   private void stopP2PServer() {
       if (p2pServer != null) {
           p2pServer.stop();
       }
   }

}

    
    

