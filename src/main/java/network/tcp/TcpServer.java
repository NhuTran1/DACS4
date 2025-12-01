package network.tcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import com.google.gson.Gson;

import dao.UserDao;
import model.Users;
import network.p2p.PeerInfo;
import service.RealtimeService;

/**
 * Basic TcpServer for signaling (login/logout), broadcasts and peer list.
 * - On LOGIN,<username>,<port> -> store client info and broadcast all peers
 * - On LOGOUT -> remove and broadcast removePeer
 *
 * Note: You likely have an existing ServerApp in your project. Use or adapt this class.
 */

public class TcpServer {
	private final int listenPort;
    private final Set<ClientSession> sessions = new CopyOnWriteArraySet<>();
    private volatile boolean running = false;
    private Thread acceptor;
    private final Gson gson = new Gson();
	
    private final RealtimeService realtimeService = new RealtimeService(); // server-side service
    private final UserDao userDao = new UserDao();

	public TcpServer(int port) {
        this.listenPort = port;
    }

	public void start() throws IOException {
        if (running) return;
        running = true;
        acceptor = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
                while (running) {
                    Socket client = serverSocket.accept();
                    ClientSession s = new ClientSession(client);
                    sessions.add(s);
                    new Thread(s, "tcp-client-" + client.getInetAddress()).start();
                }
            } catch (IOException e) {
                running = false;
            }
        }, "tcp-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

	 public void stop() {
        running = false;
        for (ClientSession s : sessions) s.stop();
        sessions.clear();
    }

	public void broadcast(String json) {
        Iterator<ClientSession> it = sessions.iterator();
        while (it.hasNext()) {
            ClientSession s = it.next();
            if (!s.send(json)) {
                it.remove();
                s.stop();
            }
        }
    }
	
	 private class ClientSession implements Runnable {
        private final Socket socket;
        private volatile boolean running = true;
        private final BufferedReader in;
        private final PrintWriter out;
        private PeerInfo peerInfo;
        private Long userId;

		public ClientSession(Socket socket) throws IOException {
			this.socket = socket;
			this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			this.out = new PrintWriter(socket.getOutputStream(), true);
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
                    try { 
                        p = Integer.parseInt(portStr); 
                    } catch (NumberFormatException ex) { 
                         
                    }

                    if (uid != null) {
                        userId = uid;
                        PeerInfo peer = new PeerInfo(uid, ip, p);
                        this.peerInfo = peer;
                        out.println(gson.toJson(getAllPeers())); // send current list
                        broadcast(gson.toJson(new AddNewPeer("addNewPeer", peer)));
                        // server-side RealtimeService handles friend broadcast
                        realtimeService.onUserOnline(uid, new model.ClientConnection(out)); // create minimal connection wrapper if needed
                    } else {
                        out.println(gson.toJson(new AddNewPeer("error", null))); // or send error
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

		private static class AddNewPeer {
			public String message;
			public PeerInfo peer;
			public AddNewPeer(String m, PeerInfo p) { this.message = m; this.peer = p; }
		}

		private Set<PeerInfo> getAllPeers() {
			Set<PeerInfo> res = new CopyOnWriteArraySet<>();
			for (ClientSession s : sessions) {
				if (s.peerInfo != null) res.add(s.peerInfo);
			}
			return res;
		}

	}
    
    
}
