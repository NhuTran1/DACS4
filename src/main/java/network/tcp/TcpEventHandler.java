package network.tcp;

import java.util.function.Consumer;

import network.p2p.PeerDiscoveryService;


public class TcpEventHandler {
    private final PeerDiscoveryService discoveryService = PeerDiscoveryService.getInstance();
    private final Consumer<PeerDiscoveryService.PeerUpdateResult> uiPeerUpdateHandler;
    private final Consumer<String> uiChatMessageHandler;

    public TcpEventHandler(Consumer<PeerDiscoveryService.PeerUpdateResult> uiPeerUpdateHandler,
                           Consumer<String> uiChatMessageHandler) {
        this.uiPeerUpdateHandler = uiPeerUpdateHandler;
        this.uiChatMessageHandler = uiChatMessageHandler;
    }

    public void onServerMessage(String line) {
        if (line == null || line.trim().isEmpty()) return;
        String trimmed = line.trim();
        // try to detect JSON array or object -> signaling
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            PeerDiscoveryService.PeerUpdateResult res = discoveryService.processServerMessage(trimmed, null);
            if (uiPeerUpdateHandler != null && !res.isEmpty()) uiPeerUpdateHandler.accept(res);
            return;
        }

        // normal chat message: "sender|message"
        if (trimmed.contains("|")) {
            if (uiChatMessageHandler != null) uiChatMessageHandler.accept(trimmed);
        } else {
            // server info broadcast; pass to UI as server message
            if (uiChatMessageHandler != null) uiChatMessageHandler.accept("Server|" + trimmed);
        }
    }
    
}
