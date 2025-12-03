package view;

import controller.ChatController;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import model.Conversation;
import model.Message;
import network.p2p.PeerInfo;
import network.p2p.PeerDiscoveryService;
import service.ChatService;
import network.p2p.P2PManager;
import dao.UserDao;

import java.util.List;
import java.util.stream.Collectors;

public class ChatWindow {
    
}