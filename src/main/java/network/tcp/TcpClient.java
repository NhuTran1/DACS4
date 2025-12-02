package network.tcp;

import java.util.function.Consumer;
import network.p2p.PeerDiscoveryService;

public class TcpClient {
    private final String host;
    private final int port;
    private final int timeoutMs;
    private TcpConnection conn;
    private Consumer<String> messageReceiver;
    private final PeerDiscoveryService discoveryService;
    private final Integer localUserId; // optional for filtering

    public TcpClient(String host, int port, int timeoutMs, Integer localUserId) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.discoveryService = PeerDiscoveryService.getInstance();
        this.localUserId = localUserId;
    }

    public void start() {
        if (conn != null && conn.isConnected()) return;
        conn = new TcpConnection();
        conn.setLineListener(this::onLineReceived);
        conn.connect(host, port, timeoutMs);
    }

    public void stop() {
        if (conn != null) conn.close();
    }

    public void sendCommand(String cmd) {
        if (conn != null && conn.isConnected()) conn.send(cmd);
    }

    public void addMessageReceiver(Consumer<String> receiver) {
        this.messageReceiver = receiver;
    }

    private void onLineReceived(String line) {
        if (messageReceiver != null) {
            try { messageReceiver.accept(line); } catch (Exception ignored) {}
        }
        try {
            discoveryService.processServerMessage(line, localUserId);
        } catch (Exception ignored) {}
    }
}