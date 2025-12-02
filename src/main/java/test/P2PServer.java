package test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.file.Files;

public class P2PServer {
    public static void main(String[] args) throws Exception {
        int tcpPort = 7003; // chỉnh port theo client
        int udpPort = 16000; // UDP voice test port
        new Thread(() -> runTcpServer(tcpPort)).start();
        new Thread(() -> runUdpServer(udpPort)).start();
    }

    private static void runTcpServer(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("P2P TCP Server listening on " + port);
            while (true) {
                Socket s = ss.accept();
                System.out.println("Peer connected: " + s.getInetAddress() + ":" + s.getPort());
                new Thread(() -> handleClient(s)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket s) {
        try (BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in));
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());) {

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("RECV: " + line);
                if (line.startsWith("CHAT|")) {
                    // CHAT|from|message
                    out.write(("ECHO|" + line + "\n").getBytes());
                    out.flush();
                } else if (line.startsWith("TYPING|")) {
                    // just log
                    System.out.println("TYPING EVENT: " + line);
                } else if (line.startsWith("FILE|")) {
                    // FILE|filename|size
                    String[] parts = line.split("\\|", 3);
                    String filename = parts[1];
                    int size = Integer.parseInt(parts[2]);
                    System.out.println("Incoming file " + filename + " size " + size);
                    File f = new File("recv_" + filename);
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        byte[] buf = new byte[4096];
                        int remaining = size;
                        while (remaining > 0) {
                            int r = in.read(buf, 0, Math.min(buf.length, remaining));
                            if (r < 0) break;
                            fos.write(buf,0,r);
                            remaining -= r;
                        }
                        fos.flush();
                    }
                    out.write(("FILEACK|" + filename + "|ok\n").getBytes());
                    out.flush();
                    System.out.println("Saved file: " + f.getAbsolutePath());
                } else {
                    System.out.println("Unknown: " + line);
                }
            }
        } catch (Exception e) {
            System.out.println("Peer connection closed.");
        }
    }

    private static void runUdpServer(int port) {
        try (DatagramSocket ds = new DatagramSocket(port)) {
            System.out.println("P2P UDP Server listening on " + port);
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (true) {
                ds.receive(p);
                System.out.println("UDP from " + p.getAddress() + ":" + p.getPort() + " size=" + p.getLength());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}