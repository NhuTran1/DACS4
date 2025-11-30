package network.tcp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;

public class TcpServer {
	private final int PORT = 9000;
	private final TcpEventHandler handler;
	private final CopyOnWriteArrayList<TcpConnection> connection = new CopyOnWriteArrayList<TcpConnection>();
	
	public TcpServer(TcpEventHandler handler) {
        this.handler = handler;
    }
	
	public void start() {
		new Thread(()->{
			try(ServerSocket serverSocket = new ServerSocket(PORT)){
				System.out.println("TCP Server started on port " + PORT);
				while(true) {
					Socket clientSocket = serverSocket.accept();
					TcpConnection conn = new TcpConnection(clientSocket);
					connection.add(conn);
					
					TcpReaderThread reader = new TcpReaderThread(conn, handler);
					reader.start();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}).start();;
	}

	
    
    
}
