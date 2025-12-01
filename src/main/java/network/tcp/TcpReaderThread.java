package network.tcp;

import java.io.BufferedReader;

public class TcpReaderThread implements Runnable {
    private final BufferedReader in;
    private final TcpConnection.LineListener listener;

    public TcpReaderThread(BufferedReader in, TcpConnection.LineListener listener) {
        this.in = in;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (listener != null) listener.onLine(line);
            }
        } catch (Exception ignored) {}
    }
}