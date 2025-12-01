package network.tcp;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class TcpConnection {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
    private Thread readerThread;
    private Thread writerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public interface LineListener { 
        void onLine(String line); 
    }
    private LineListener listener;
    public void setLineListener(LineListener l) { 
        this.listener = l; 
    }

    public TcpConnection() {}

    public boolean connect(String host, int port, int timeoutMs) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
            running.set(true);
            startReaderAndWriter();
            return true;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    // New: wrap an existing socket and optionally start writer thread only
    public TcpConnection(Socket socket, boolean startWriter) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
        if (startWriter) {
            running.set(true);
            startWriterThread();
        }
    }

    private void startReaderAndWriter() {
        // reader
        readerThread = new Thread(() -> {
            try {
                String line;
                while (running.get() && (line = in.readLine()) != null) {
                    if (listener != null) listener.onLine(line);
                }
            } catch (Exception ignored) {}
            finally { running.set(false); }
        }, "tcp-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // writer
        startWriterThread();
    }

    private void startWriterThread() {
        writerThread = new Thread(() -> {
           try {
               while (running.get()) {
                   String msg = outbound.take();
                   if (out != null) {
                       synchronized (out) {
                           out.println(msg);
                           out.flush();
                       }
                   }
               }
           } catch (InterruptedException ignored) {}
        }, "tcp-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public boolean send(String line) {
        if (out == null) return false;
        // If writer thread running => enqueue; otherwise send sync
        if (running.get() && writerThread != null && writerThread.isAlive()) {
            try { outbound.put(line); return true; } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        } else {
            // writer thread is not active (server with direct writer)
            try {
                synchronized (out) {
                    out.println(line);
                    out.flush();
                }
                return true;
            } catch (Exception e) { return false; }
        }
    }

    public boolean sendSync(String line) {
        if (out == null) return false;
        try {
            synchronized (out) {
                out.println(line);
                out.flush();
            }
            return true;
        } catch (Exception e) { return false; }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close() {
        running.set(false);
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (readerThread != null) readerThread.interrupt(); } catch (Exception ignored) {}
        try { if (writerThread != null) writerThread.interrupt(); } catch (Exception ignored) {}
        outbound.clear();
    }
}