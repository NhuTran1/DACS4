package network.p2p;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PeerConnection {
    private Socket tcpSocket;
    private BufferedReader in;
    private PrintWriter out;
    private DatagramSocket udpSocket;
    private final AtomicBoolean tcpOpen = new AtomicBoolean(false);
    private final AtomicBoolean udpOpen = new AtomicBoolean(false);
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    
    public interface TcpMessageHandler { 
        void onMessage(String sender, String message); 
    }

    public boolean openTcp(PeerInfo peer, int timeoutMs) {
        if (peer == null) return false;
        try {
            tcpSocket = new Socket();
            tcpSocket.connect(new InetSocketAddress(peer.getIp(), peer.getPort()), timeoutMs);
            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(tcpSocket.getOutputStream())), true);
            tcpOpen.set(true);
            startTcpReader(null);
            return true;
        } catch (Exception e) {
            closeTcp();
            return false;
        }
    }

    private void startTcpReader(TcpMessageHandler handler) {
        exec.submit(() -> {
            try {
                String line;
                while (tcpOpen.get() && (line = in.readLine()) != null) {
                    if (handler != null) handler.onMessage(null, line);
                }
            } catch (Exception ignored) {}
        });
    }

     public boolean sendTcpMessage(String line) {
        if (!tcpOpen.get() || out == null) return false;
        synchronized (out) {
            out.println(line);
            out.flush();
        }
        return true;
    }

    public boolean openUdpForFile() {
        try {
            if (udpSocket == null || udpSocket.isClosed()) {
                udpSocket = new DatagramSocket(); // ephemeral
            }
            udpOpen.set(true);
            return true;
        } catch (SocketException e) {
            return false;
        }
    }

    public boolean sendFileChunk(byte[] data, InetSocketAddress target) {
        if (!udpOpen.get() || udpSocket == null || target == null) return false;
        try {
            DatagramPacket p = new DatagramPacket(data, data.length, target.getAddress(), target.getPort());
            udpSocket.send(p);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean openRtp() {
        // placeholder: create socket for RTP streaming (DTLS/SRTP + codecs are out of scope)
        try {
            if (udpSocket == null || udpSocket.isClosed()) udpSocket = new DatagramSocket();
            udpOpen.set(true);
            return true;
        } catch (SocketException e) {
            return false;
        }
    }

    public void closeTcp() {
        tcpOpen.set(false);
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (tcpSocket != null) tcpSocket.close(); } catch (IOException ignored) {}
    }

     public void closeUdp() {
        udpOpen.set(false);
        if (udpSocket != null) udpSocket.close();
    }

    public void closeAll() {
        closeTcp();
        closeUdp();
        exec.shutdownNow();
    }
    
    
}
