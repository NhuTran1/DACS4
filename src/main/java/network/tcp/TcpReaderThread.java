package network.tcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

public class TcpReaderThread extends Thread {
	private final TcpConnection connection;
	private final TcpEventHandler handler;
	
	 public TcpReaderThread(TcpConnection connection, TcpEventHandler handler) {
	        this.connection = connection;
	        this.handler = handler;
	 }
	 
	 @Override
	 public void run() {
		try {
            String line;
            while ((line = connection.getReader().readLine()) != null) {
                handler.onMessageReceived(line);
            }
        } catch (Exception e) {
            handler.onError(e);
        } finally {
            connection.close();
        }
	 }

}


