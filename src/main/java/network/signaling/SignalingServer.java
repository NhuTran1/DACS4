package network.signaling;

import com.google.gson.Gson;
import dao.UserDao;
import model.Users;
import network.p2p.PeerInfo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Signaling Server - Server trung tâm CHỈ quản lý peer list
 * Không xử lý chat, typing, file, call → để P2P làm
 */
public class SignalingServer {
    private final int port;
    private final Map<Integer, PeerInfo> activePeers = new ConcurrentHashMap<>();
    private final Set<ClientHandler> clients = new CopyOnWriteArraySet<>();
    private volatile boolean running = false;
    private final Gson gson = new Gson();
    private final UserDao userDao = new UserDao();

    public SignalingServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        if (running) return;
        running = true;
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("✅ Signaling Server started on port " + port);

                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    ClientHandler handler = new ClientHandler(clientSocket);
                    clients.add(handler);
                    new Thread(handler).start();
                }
            } catch (IOException e) {
                System.err.println("❌ Signaling Server error: " + e.getMessage());
            }
        }, "signaling-acceptor").start();
    }

    public void stop() {
        running = false;
        clients.forEach(ClientHandler::close);
        clients.clear();
        activePeers.clear();
    }

    // Broadcast JSON message tới tất cả client
    private void broadcast(String json) {
        clients.forEach(c -> c.send(json));
    }

    // ===== CLIENT HANDLER =====
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private PeerInfo myPeer;
        private volatile boolean active = true;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            try {
                this.out = new PrintWriter(socket.getOutputStream(), true);
                this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            } catch (IOException e) {
                close();
            }
        }

        @Override
        public void run() {
            try {
                String line;
                while (active && (line = in.readLine()) != null) {
                    handleCommand(line);
                }
            } catch (IOException e) {
                // Client disconnected
            } finally {
                close();
            }
        }

        /**
         * Xử lý command từ client:
         * - LOGIN,username,port
         * - LOGOUT
         */
        private void handleCommand(String line) {
            if (line == null || line.trim().isEmpty()) return;
            String[] parts = line.split(",", 3);
            String cmd = parts[0].trim().toUpperCase();
            switch (cmd) {
                case "LOGIN" -> handleLogin(parts);
                case "LOGOUT" -> handleLogout();
                default -> System.out.println("⚠️ Unknown command: " + cmd);
            }
        }

        private void handleLogin(String[] parts) {
            if (parts.length < 3) {
                sendError("Invalid LOGIN format. Use: LOGIN,username,port");
                return;
            }
            String username = parts[1].trim();
            int p2pPort;

            try {
                p2pPort = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException e) {
                sendError("Invalid port number");
                return;
            }

            // Kiểm tra user có tồn tại trong DB không
            Users user = userDao.findByUsername(username);
            if (user == null) {
                sendError("User not found");
                return;
            }

            String ip = socket.getInetAddress().getHostAddress();
            Integer userId = user.getId();                

            if (userId == null) {
                sendError("User id is null");
                return;
            }

            // Tạo PeerInfo (PeerInfo constructor expects Long userId)
            myPeer = new PeerInfo(userId, ip, p2pPort);
            activePeers.put(userId, myPeer);

            System.out.println("✅ User logged in: " + username + " (" + userId + ") - " + ip + ":" + p2pPort);

            // 1. Gửi danh sách peer hiện tại cho client mới
            sendPeerList();

            // 2. Broadcast "addNewPeer" cho các client khác
            SignalingMessage addMsg = new SignalingMessage("addNewPeer", myPeer);
            broadcast(gson.toJson(addMsg));
        }

        private void handleLogout() {
            if (myPeer != null) {
                activePeers.remove(myPeer.getUserId());

                // Broadcast "removePeer"
                SignalingMessage removeMsg = new SignalingMessage("removePeer", myPeer);
                broadcast(gson.toJson(removeMsg));

                System.out.println("👋 User logged out: " + myPeer.getUserId());
            }
            close();
        }

        private void sendPeerList() {
            List<PeerInfo> peers = new ArrayList<>(activePeers.values());
            send(gson.toJson(peers));
        }

        private void sendError(String error) {
            Map<String, String> errMsg = Map.of("error", error);
            send(gson.toJson(errMsg));
        }

        public boolean send(String json) {
            if (out == null || socket.isClosed()) return false;
            try {
                out.println(json);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public void close() {
            active = false;
            try {
                if (myPeer != null) {
                    // ensure logout broadcast only once
                    if (activePeers.containsKey(myPeer.getUserId())) {
                        activePeers.remove(myPeer.getUserId());
                        SignalingMessage removeMsg = new SignalingMessage("removePeer", myPeer);
                        broadcast(gson.toJson(removeMsg));
                    }
                }
            } catch (Exception ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
            clients.remove(this);
        }
    }

    // ===== DTO =====
    private static class SignalingMessage {
        public String message;
        public PeerInfo peer;
        public SignalingMessage(String message, PeerInfo peer) {
            this.message = message;
            this.peer = peer;
        }
    }

    //Test
    public static void main(String[] args) {
        int port = 7002; 
        SignalingServer server = new SignalingServer(port);
        try {
            server.start();
            System.out.println("SignalingServer is running...");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
