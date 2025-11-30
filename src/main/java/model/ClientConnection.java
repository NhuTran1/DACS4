package model;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import network.tcp.TcpConnection;

public class ClientConnection {

    private Long userId;
    private TcpConnection tcpConnection;

    // Danh sách conversationId mà user đang tham gia
    private final Set<Long> conversationIds = new HashSet<>();

    // Trạng thái user
    private boolean online;

    public ClientConnection(Long userId, TcpConnection tcpConnection) {
        this.userId = userId;
        this.tcpConnection = tcpConnection;
        this.online = true;
    }

    public Long getUserId() {
        return userId;
    }

    public TcpConnection getTcpConnection() {
        return tcpConnection;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOffline() {
        this.online = false;
    }

    public void addConversation(Long conversationId) {
        conversationIds.add(conversationId);
    }

    //giúp server ktra nhanh thành viên trong conversation mỗi lần gửi tn, mà ko cần query DB nhiù lần
    public boolean isInConversation(Long cid) {
        return conversationIds.contains(cid);
    }

    public Set<Long> getConversationIds() {
        return conversationIds;
    }
}
