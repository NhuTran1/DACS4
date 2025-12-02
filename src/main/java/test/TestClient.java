package test;

import java.io.*;
import java.net.Socket;

public class TestClient {
    public static void main(String[] args) throws Exception {
        Socket socket = new Socket("localhost", 7002);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        // Gửi lệnh LOGIN
        out.println("LOGIN,alice,7010");

        // Nhận danh sách peer từ server
        String response = in.readLine();
        System.out.println("Server response: " + response);

        // Gửi lệnh LOGOUT
        out.println("LOGOUT");

        socket.close();
    }
}
