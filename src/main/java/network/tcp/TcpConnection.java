package network.tcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class TcpConnection {
	private final Socket socket;
	private final BufferedReader reader;
	private final PrintWriter writer;
	
	public TcpConnection(Socket socket) throws IOException {
	        this.socket = socket;
	        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
	        this.writer = new PrintWriter(socket.getOutputStream(), true);
	}
	
	public Socket getSocket() {
	        return socket;
    }

    public BufferedReader getReader() {
        return reader;
    }

    public PrintWriter getWriter() {
        return writer;
    }

    public void send(String message) {
        writer.println(message);
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }
}
