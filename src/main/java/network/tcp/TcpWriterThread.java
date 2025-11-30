package network.tcp;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

public class TcpWriterThread extends Thread{
	private final TcpConnection connection;
	private final BlockingDeque<String> queue = new LinkedBlockingDeque<String>();
	
	public TcpWriterThread(TcpConnection connection) {
        this.connection = connection;
    }

    public void send(String message) {
        queue.offer(message);
    }
    
    @Override
    public void run() {
    	try {
    		while (!Thread.currentThread().isInterrupted()) {
    			String msg = queue.take();
    			connection.send(msg);
    		}
    	}catch (Exception ignored) {
        } finally {
            connection.close();
        }
    }
}
