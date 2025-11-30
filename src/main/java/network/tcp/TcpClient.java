package network.tcp;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

//gui tin den cac peer
public class TcpClient {
	private final String host;
	private final int port;
	private TcpConnection connection;
	private TcpReaderThread readerThread;
	private TcpWriterThread writerThread;
	
	public TcpClient(String host, int port, TcpEventHandler handler) {
        this.host = host;
        this.port = port;
        try {
            Socket socket = new Socket(host, port);
            connection = new TcpConnection(socket);

            readerThread = new TcpReaderThread(connection, handler);
            writerThread = new TcpWriterThread(connection);

            readerThread.start();
            writerThread.start();
        } catch (Exception e) {
            handler.onError(e);
        }
    }
	
	public void send(String message) {
        if (writerThread != null) {
            writerThread.send(message);
        }
    }

    public void close() {
        if (connection != null) connection.close();
        if (readerThread != null) readerThread.interrupt();
        if (writerThread != null) writerThread.interrupt();
    }
    
}
