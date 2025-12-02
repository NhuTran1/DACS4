package test;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;

import network.p2p.PeerInfo;
import network.p2p.PeerConnection;

public class P2PClient {
    public static void main(String[] args) throws Exception {
        // Target peer IP/port (PeerInfo normally from PeerDiscoveryService)
        String targetIp = "127.0.0.1";
        int targetPort = 7003;

        // Use plain Socket:
        Socket s = new Socket();
        s.connect(new InetSocketAddress(targetIp, targetPort), 5000);
        System.out.println("Connected to peer " + targetIp + ":" + targetPort);

        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        PrintWriter out = new PrintWriter(s.getOutputStream(), true);

        // Chat
        out.println("CHAT|123|Xin chao tu client");
        System.out.println("Sent chat; server replied: " + in.readLine());

        // Typing on/off
        out.println("TYPING|123|true");

        // File send
        Path p = Path.of("sample.txt");
        System.out.println("Working dir: " + System.getProperty("user.dir"));
        System.out.println("Checking " + p.toAbsolutePath() + " exists=" + Files.exists(p));

        byte[] fileBytes = null;
        InputStream is = P2PClient.class.getResourceAsStream("/sample.txt");
        if (is != null) {
            System.out.println("Loaded sample.txt from classpath");
            fileBytes = is.readAllBytes();
            is.close();
        } else if (Files.exists(p)) {
            System.out.println("Loaded sample.txt from working directory");
            fileBytes = Files.readAllBytes(p);
        } else {
            throw new java.io.FileNotFoundException("sample.txt not found (classpath or working dir). Put sample.txt in src/main/resources or project root.");
        }
        out.println("FILE|" + p.getFileName().toString() + "|" + fileBytes.length);
        out.flush();
        // send raw bytes
        s.getOutputStream().write(fileBytes);
        s.getOutputStream().flush();
        System.out.println("Waiting for file ack: " + in.readLine());

        // UDP voice test (client sends random packets)
        try (DatagramSocket ds = new DatagramSocket()) {
            byte[] pkt = new byte[160]; // e.g., 20ms of 8kHz 1 byte (fake)
            for (int i = 0; i < 50; i++) {
                ds.send(new DatagramPacket(pkt, pkt.length, java.net.InetAddress.getByName(targetIp), 16000));
                Thread.sleep(20);
            }
            System.out.println("Sent UDP test packets.");
        }
        
        System.out.println("Working dir: " + System.getProperty("user.dir"));
        System.out.println("Path abs: " + Path.of("sample.txt").toAbsolutePath());
        System.out.println("Exists? " + Files.exists(Path.of("sample.txt")));
        

        s.close();
    }
}
