package network.p2p;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

public class PeerConnection {
    private PeerInfo peerInfo;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private boolean connected = false;
    
    private PeerEventHandler eventHandler;
    
    public PeerConnection(PeerInfo info, PeerEventHandler handler) {
        this.peerInfo = info;
        this.eventHandler = handler;
    }
    
    public void connect() throws Exception {
        this.socket = new Socket(peerInfo.getIp(), peerInfo.getPort());
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
        this.connected = true;

        startListenerThread();
    }
    
    private void startListenerThread() {
        Thread t = new Thread(() -> {
            try {
                while (connected) {
                    String msg = in.readUTF();
                    eventHandler.onMessage(peerInfo.getUserId(), msg);
                }
            } catch (Exception e) {
                eventHandler.onDisconnect(peerInfo.getUserId());
            }
        });
        t.start();
    }
    
    public void send(String msg) throws Exception {
        if (!connected) return;
        out.writeUTF(msg);
        out.flush();
    }
    
    public void close() {
        try {
            connected = false;
            socket.close();
        } catch (Exception ignored) {}
    }
    
    
}
