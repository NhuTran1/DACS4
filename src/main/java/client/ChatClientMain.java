package client;

import network.signaling.SignalingClient;
import network.p2p.P2PManager;
import network.p2p.P2PServer;
import network.p2p.PeerDiscoveryService;
import service.ChatService;
import dao.UserDao;
import model.Users;

import java.util.Scanner;

/**
 * Client chính - Kết hợp Signaling + P2P
 */
public class ChatClientMain {
    private final Integer userId;
    private final String username;
    private final int p2pPort;
    
    private SignalingClient signalingClient;
    private P2PServer p2pServer;
    private P2PManager p2pManager;
    private ChatService chatService;
    private UserDao userDao;

    public ChatClientMain(Integer userId, String username, int p2pPort) {
        this.userId = userId;
        this.username = username;
        this.p2pPort = p2pPort;
        this.chatService = new ChatService();
        this.userDao = new UserDao();
    }

    public void start(String signalingHost, int signalingPort) {
        System.out.println("🚀 Starting Chat Client...");
        System.out.println("   User: " + username + " (ID: " + userId + ")");
        System.out.println("   P2P Port: " + p2pPort);
        
        // 1. Khởi động P2P Server (lắng nghe incoming connections)
        p2pServer = new P2PServer(p2pPort);
        p2pServer.start();

        // 2. Khởi tạo P2P Manager
        p2pManager = new P2PManager(userId, chatService);
        
        // 3. Connect P2PServer với P2PManager
        p2pServer.setMessageHandler((fromUserId, message) -> {
            // Forward incoming messages tới P2PManager
            p2pManager.onMessageReceived(message);
        });
        
        // 4. Set event listener cho P2PManager
        p2pManager.setEventListener(new P2PManager.P2PEventListener() {
            @Override
            public void onChatMessageReceived(Integer conversationId, model.Message message) {
                System.out.println("\n📨 [Conv " + conversationId + "] " + 
                    getUserName(message.getSender().getId()) + ": " + message.getContent());
            }

            @Override
            public void onTypingReceived(Integer conversationId, Integer userId) {
                System.out.println("⌨️  " + getUserName(userId) + " is typing...");
            }

            @Override
            public void onFileRequestReceived(Integer fromUser, String fileName, Integer fileSize) {
                System.out.println("📁 " + getUserName(fromUser) + " wants to send: " + fileName + 
                    " (" + formatFileSize(fileSize) + ")");
            }

            @Override
            public void onCallOfferReceived(Integer fromUser, String sdp) {
                System.out.println("📞 Incoming call from " + getUserName(fromUser));
            }

            @Override
            public void onConnectionLost(Integer userId) {
                System.out.println("⚠️  Connection lost with " + getUserName(userId));
            }

			@Override
			public void onChatRequestReceived(Integer fromUser, String fromDisplayName) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void onChatRequestResponse(Integer fromUser, boolean accepted) {
				// TODO Auto-generated method stub
				
			}
        });

        // 5. Kết nối tới Signaling Server
        signalingClient = new SignalingClient(signalingHost, signalingPort);
        signalingClient.setPeerUpdateListener(updateResult -> {
            System.out.println("\n🔄 Peer list updated:");
            updateResult.added.forEach(p -> 
                System.out.println("  ✅ " + getUserName(p.getUserId()) + " online"));
            updateResult.removed.forEach(p -> 
                System.out.println("  ❌ " + getUserName(p.getUserId()) + " offline"));
        });

        if (signalingClient.connect(3000)) {
            if (signalingClient.login(username, p2pPort)) {
                System.out.println("✅ Logged in successfully\n");
                System.out.println("==============================================");
                System.out.println("Commands:");
                System.out.println("  /send <conversationId> <message>");
                System.out.println("  /typing <conversationId>");
                System.out.println("  /peers");
                System.out.println("  /quit");
                System.out.println("==============================================\n");
            } else {
                System.err.println("❌ Login failed");
            }
        } else {
            System.err.println("❌ Cannot connect to signaling server");
        }
    }

    // ===== API CHO UI =====

    public void sendMessage(Integer conversationId, String content) {
        // 1. Lưu vào DB local
        model.Message msg = chatService.sendMessage(conversationId, userId, content, null);
        
        if (msg != null) {
            System.out.println("✅ Message saved to DB (ID: " + msg.getId() + ")");
            
            // 2. Gửi P2P tới các peers
            boolean success = p2pManager.sendChatMessage(conversationId, content);
            if (success) {
                System.out.println("✅ Message sent to peers");
            } else {
                System.err.println("⚠️  Failed to send message to some peers");
            }
        } else {
            System.err.println("❌ Failed to save message to DB");
        }
    }

    public void sendTyping(Integer conversationId) {
        p2pManager.sendTypingStart(conversationId);
    }

    public void listPeers() {
        var peers = PeerDiscoveryService.getInstance().getAllPeers();
        System.out.println("\n👥 Online Peers (" + peers.size() + "):");
        peers.forEach(p -> 
            System.out.println("  - " + getUserName(p.getUserId()) + " (" + p.getIp() + ":" + p.getPort() + ")"));
        System.out.println();
    }

    public void shutdown() {
        System.out.println("\n👋 Shutting down...");
        
        if (signalingClient != null) {
            signalingClient.logout();
            signalingClient.disconnect();
        }
        if (p2pManager != null) {
            p2pManager.shutdown();
        }
        if (p2pServer != null) {
            p2pServer.stop();
        }
        
        System.out.println("✅ Goodbye!");
    }

    // ===== HELPER METHODS =====

    private String getUserName(Integer userId) {
        Users user = userDao.findById(userId);
        return user != null ? user.getDisplayName() : "User" + userId;
    }

    private String formatFileSize(Integer bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    // ===== MAIN METHOD (CLI Demo) =====

    public static void main(String[] args) {
        // Demo: chạy 2 instances với các tham số khác nhau
        // Instance 1: java ChatClientMain 1 alice 7010
        // Instance 2: java ChatClientMain 2 bob 7011
        
        if (args.length < 3) {
            System.err.println("Usage: java ChatClientMain <userId> <username> <p2pPort>");
            System.exit(1);
        }

        Integer userId = Integer.parseInt(args[0]);
        String username = args[1];
        int p2pPort = Integer.parseInt(args[2]);

        ChatClientMain client = new ChatClientMain(userId, username, p2pPort);
        client.start("localhost", 7002); // Signaling Server port

        // CLI loop
        // ...existing code...
        Scanner scanner = new Scanner(System.in);
        while (true) {
        	System.out.print("> ");
            String input = scanner.nextLine().trim();

            if (input.startsWith("/send ")) {
                String[] parts = input.substring(6).split(" ", 2);
                if (parts.length == 2) {
                    Integer convId = Integer.parseInt(parts[0]);
                    String message = parts[1];
                    client.sendMessage(convId, message);
                } else {
                    System.err.println("Usage: /send <conversationId> <message>");
                }
            } else if (input.startsWith("/typing ")) {
                Integer convId = Integer.parseInt(input.substring(8));
                client.sendTyping(convId);
            } else if (input.equals("/peers")) {
                client.listPeers();
            } else if (input.equals("/quit")) {
                client.shutdown();
                break;
                
             } else {
                System.err.println("Unknown command. Type /send, /typing, /peers, or /quit");
                System.err.println("Unknown command: [" + input + "]");
                System.err.println("Type: /send <conversationId> <message>, /typing <conversationId>, /peers, /quit");
             }
         }

        scanner.close();
    }
    
}