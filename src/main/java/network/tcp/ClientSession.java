package network.tcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import com.google.gson.Gson;

import dao.UserDao;
import model.Users;
import model.ClientConnection;
import network.p2p.PeerInfo;
import service.RealtimeService;

/* ...existing class header... */

public class ClientSession implements Runnable {
    private final Socket socket;
    private volatile boolean running = true;
    private final BufferedReader in;
    private final PrintWriter out;
    private final TcpConnection tcpConnection; // wrapper used by RealtimeService
    private PeerInfo peerInfo;
    private Long userId;
    private UserDao userDao;
    private Gson gson;
    private RealtimeService realtimeService;
    
    public ClientSession(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(socket.getOutputStream(), true);
        // Create a TcpConnection wrapper without starting reader threads — server will handle reading
         this.tcpConnection = new TcpConnection(socket, true);
    }

    @Override
    public void run() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                handle(line);
            }
        } catch (IOException e) {
            // disconnection
        } finally {
            stop();
        }
    }

    private void handle(String line) {
        if (line == null) return;
        String[] parts = line.split(",", 3);
        String cmd = parts[0].trim().toUpperCase();
        try {
            if ("LOGIN".equals(cmd) && parts.length == 3) {
                String username = parts[1].trim();
                String portStr = parts[2].trim();
                String ip = socket.getInetAddress().getHostAddress();

                Users u = userDao.findByUsername(username);
                Long uid = u != null ? u.getId() : null;
                int p = 0;
                try { p = Integer.parseInt(portStr); } catch (NumberFormatException ex) { }

                if (uid != null) {
                    userId = uid;
                    PeerInfo peer = new PeerInfo(uid, ip, p);
                    this.peerInfo = peer;
                    out.println(gson.toJson(getAllPeers())); // send current list
                    broadcast(gson.toJson(new AddNewPeer("addNewPeer", peer)));
                    // Use TcpConnection wrapper for realtime service
                    realtimeService.onUserOnline(uid, new ClientConnection(uid, tcpConnection));
                } else {
                    out.println(gson.toJson(new AddNewPeer("error", null)));
                }
            } else if ("LOGOUT".equals(cmd)) {
                if (peerInfo != null) {
                    broadcast(gson.toJson(new AddNewPeer("removePeer", peerInfo)));
                    stop();
                }
            }
        } catch (Exception ignored) {}
    }

    public void stop() {
        running = false;
        if (peerInfo != null) {
            broadcast(gson.toJson(new AddNewPeer("removePeer", peerInfo)));
        }
        try { if (tcpConnection != null) tcpConnection.close(); } catch (Exception ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }

    public boolean send(String msg) {
        try {
            out.println(msg);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /* ...getAllPeers()... */
}