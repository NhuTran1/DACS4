package network.tcp;

import java.io.PrintWriter;
import java.util.concurrent.BlockingQueue;

public class TcpWriterThread implements Runnable {
    private final BlockingQueue<String> outbound;
    private final PrintWriter out;

    public TcpWriterThread(BlockingQueue<String> outbound, PrintWriter out) {
        this.outbound = outbound;
        this.out = out;
    }

    @Override
    public void run() {
        try {
            while (true) {
                String msg = outbound.take();
                synchronized (out) {
                    out.println(msg);
                    out.flush();
                }
            }
        } catch (InterruptedException ignored) {}
    }
}