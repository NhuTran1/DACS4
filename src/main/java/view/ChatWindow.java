package view;

import client.ClientManager;
import controller.ChatController;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import model.Conversation;
import model.Message;
import model.Users;
import network.p2p.P2PManager;
import network.p2p.PeerInfo;
import service.ChatService;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.kordamp.ikonli.javafx.FontIcon;

public class ChatWindow {
    private final Stage stage;
    private final ChatService chatService;
    private final P2PManager p2pManager;
    private final Integer currentUserId;
    private final ChatController chatController;
    private final ClientManager clientManager;
    
    // UI Components
    private ListView<Users> friendListView;
    private VBox messageArea;
    private ScrollPane messageScrollPane;
    private TextField messageInput;
    private Label chatTitleLabel;
    private Label chatStatusLabel;
    private Label typingIndicator;
    
    // Current state
    private Conversation currentConversation;
    private Users currentChatUser;
    private Map<Integer, Circle> userStatusIndicators = new HashMap<>();
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public ChatWindow(Stage stage, ChatService chatService, P2PManager p2pManager, 
                      Integer userId, ClientManager clientManager) {
        this.stage = stage;
        this.chatService = chatService;
        this.p2pManager = p2pManager;
        this.currentUserId = userId;
        this.chatController = new ChatController(chatService, p2pManager, userId);
        this.clientManager = clientManager;
        
        setupP2PListeners();
    }

    public void show() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0a0e27;");

        // Left Sidebar - Friends List
        VBox sidebar = createSidebar();
        
        // Center Chat Area
        VBox chatArea = createChatArea();
        
        root.setLeft(sidebar);
        root.setCenter(chatArea);

        
        Scene scene = new Scene(root, 1400, 850);
        stage.setScene(scene);
        stage.setTitle("Chat Application");
        
     // Cho phép resize
        stage.setResizable(true);

        // Đặt kích thước tối thiểu để giao diện không bị méo
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        
        scene.getStylesheets().add(
                getClass().getResource("/css/chat.css").toExternalForm()
            );
        
        // Handle window close
        stage.setOnCloseRequest(e -> {
            if (clientManager != null) {
                clientManager.shutdown();
            }
            if (chatController != null) {
                chatController.shutdown();
            }
        });
        
        stage.show();
        
        // Load friends and their online status
        loadFriendsWithStatus();
        
        // Periodic refresh for online status
        startStatusRefreshTimer();
    }

    // ===== SIDEBAR - FRIENDS LIST =====
    private VBox createSidebar() {
        VBox sidebar = new VBox(15);
        sidebar.setPrefWidth(350);
        sidebar.setStyle("-fx-background-color: #1a1d2e; -fx-padding: 20;");

        // Header with user info
        HBox header = createSidebarHeader();
        
        // Search box
        TextField searchField = createSearchField();
        
        // Friends list
        friendListView = new ListView<>();
        friendListView.setStyle("""
            -fx-background-color: transparent;
            -fx-border-width: 0;
            -fx-focus-color: transparent;
        """);
        friendListView.setCellFactory(lv -> new FriendCell());
        friendListView.setOnMouseClicked(e -> {
            Users selectedFriend = friendListView.getSelectionModel().getSelectedItem();
            if (selectedFriend != null) {
                openChatWithUser(selectedFriend);
            }
        });
        
        VBox.setVgrow(friendListView, Priority.ALWAYS);

        sidebar.getChildren().addAll(header, searchField, friendListView);
        return sidebar;
    }

    private HBox createSidebarHeader() {
        HBox header = new HBox(15);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 10, 0));

        // Current user avatar with status
        StackPane avatarStack = createAvatarWithStatus("👤", true, 45);

        VBox userInfo = new VBox(3);
        Users currentUser = chatService.getUserById(currentUserId);
        Label userName = new Label(currentUser.getDisplayName());
        userName.setFont(Font.font("System", FontWeight.BOLD, 16));
        userName.setTextFill(Color.web("#ffffff"));
        
        Label userStatus = new Label("Online");
        userStatus.setFont(Font.font(12));
        userStatus.setTextFill(Color.web("#4ade80"));
        
        userInfo.getChildren().addAll(userName, userStatus);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button settingsBtn = createIconButton("fas-cog", 35);
        settingsBtn.setOnAction(e -> showSettingsDialog());

        header.getChildren().addAll(avatarStack, userInfo, spacer, settingsBtn);
        return header;
    }

    private TextField createSearchField() {
        TextField search = new TextField();
        search.setPromptText("🔍 Search friends...");
        search.setPrefHeight(45);
        search.setStyle("""
            -fx-background-color: #262b40;
            -fx-background-radius: 12;
            -fx-text-fill: #ffffff;
            -fx-prompt-text-fill: #6b7280;
            -fx-border-width: 0;
            -fx-padding: 0 15;
            -fx-font-size: 14;
        """);
        
        search.textProperty().addListener((obs, old, newVal) -> filterFriends(newVal));
        
        return search;
    }

    // ===== CHAT AREA =====
    private VBox createChatArea() {
        VBox chatArea = new VBox();
        chatArea.setStyle("-fx-background-color: #0a0e27;");

        // Chat header
        HBox chatHeader = createChatHeader();
        
        // Message area with gradient background
        messageArea = new VBox(15);
        messageArea.setPadding(new Insets(25));
        messageArea.setStyle("-fx-background-color: #0a0e27;");
        
        messageScrollPane = new ScrollPane(messageArea);
        messageScrollPane.setFitToWidth(true);
        messageScrollPane.setStyle("""
            -fx-background-color: transparent;
            -fx-border-width: 0;
            -fx-background: #0a0e27;
        """);
        messageScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(messageScrollPane, Priority.ALWAYS);

        // Typing indicator
        typingIndicator = new Label();
        typingIndicator.setTextFill(Color.web("#6b7280"));
        typingIndicator.setFont(Font.font(12));
        typingIndicator.setPadding(new Insets(0, 0, 10, 25));
        typingIndicator.setVisible(false);

        // Message input area
        HBox inputArea = createInputArea();

        chatArea.getChildren().addAll(chatHeader, messageScrollPane, typingIndicator, inputArea);
        return chatArea;
    }

    private HBox createChatHeader() {
        HBox header = new HBox(15);
        header.setPadding(new Insets(20, 25, 20, 25));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("""
            -fx-background-color: #1a1d2e;
            -fx-border-color: #262b40;
            -fx-border-width: 0 0 1 0;
        """);

        // Avatar with status indicator
        StackPane avatarStack = createAvatarWithStatus("👤", false, 50);

        VBox userInfo = new VBox(5);
        chatTitleLabel = new Label("Select a friend to chat");
        chatTitleLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        chatTitleLabel.setTextFill(Color.web("#ffffff"));
        
        chatStatusLabel = new Label("Offline");
        chatStatusLabel.setFont(Font.font(13));
        chatStatusLabel.setTextFill(Color.web("#6b7280"));
        
        userInfo.getChildren().addAll(chatTitleLabel, chatStatusLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Action buttons (Ikonli)
        Button callBtn = createIconButton("fas-phone", 40);
        Button videoBtn = createIconButton("fas-video", 40);
        Button infoBtn = createIconButton("fas-info-circle", 40);

        callBtn.setOnAction(e -> handleVoiceCall());
        videoBtn.setOnAction(e -> handleVideoCall());
        infoBtn.setOnAction(e -> showConversationInfo());

        header.getChildren().addAll(avatarStack, userInfo, spacer, callBtn, videoBtn, infoBtn);
        return header;
    }

    private HBox createInputArea() {
        HBox inputArea = new HBox(12);
        inputArea.setPadding(new Insets(20, 25, 25, 25));
        inputArea.setAlignment(Pos.CENTER);
        inputArea.setStyle("-fx-background-color: #1a1d2e;");

        Button attachBtn = createIconButton("fas-paperclip", 40);
        Button audioBtn  = createIconButton("fas-microphone", 40);
        Button emojiBtn = createIconButton("fas-smile", 40);
        
        messageInput = new TextField();
        messageInput.setPromptText("Type a message...");
        messageInput.setPrefHeight(50);
        messageInput.setStyle("""
            -fx-background-color: #262b40;
            -fx-background-radius: 25;
            -fx-text-fill: #ffffff;
            -fx-prompt-text-fill: #6b7280;
            -fx-border-width: 0;
            -fx-padding: 0 20;
            -fx-font-size: 14;
        """);
        HBox.setHgrow(messageInput, Priority.ALWAYS);

        // Typing indicator
        messageInput.textProperty().addListener((obs, old, newVal) -> {
            if (currentConversation != null) {
                if (!newVal.isEmpty() && old.isEmpty()) {
                    chatController.sendTypingStart(currentConversation.getId());
                } else if (newVal.isEmpty() && !old.isEmpty()) {
                    chatController.sendTypingStop(currentConversation.getId());
                }
            }
        });

        messageInput.setOnAction(e -> sendMessage());

        Button sendBtn = createSendButton();
        sendBtn.setOnAction(e -> sendMessage());

        attachBtn.setOnAction(e -> handleAttachment());
        emojiBtn.setOnAction(e -> showEmojiPicker());

        inputArea.getChildren().addAll(attachBtn, emojiBtn, messageInput, sendBtn);
        return inputArea;
    }

    // ===== HELPER UI METHODS =====
    private StackPane createAvatarWithStatus(String emoji, boolean isOnline, double size) {
        StackPane stack = new StackPane();
        
        // Avatar circle with gradient
        Circle avatarBg = new Circle(size / 2);
        avatarBg.setFill(javafx.scene.paint.Color.web("#667eea"));
        
        Label avatarLabel = new Label(emoji);
        avatarLabel.setFont(Font.font(size * 0.6));
        
        // Status indicator
        Circle statusIndicator = new Circle(size * 0.15);
        statusIndicator.setFill(isOnline ? Color.web("#4ade80") : Color.web("#6b7280"));
        statusIndicator.setStroke(Color.web("#1a1d2e"));
        statusIndicator.setStrokeWidth(2);
        
        StackPane.setAlignment(statusIndicator, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(statusIndicator, new Insets(0, 0, 2, 0));
        
        stack.getChildren().addAll(avatarBg, avatarLabel, statusIndicator);
        return stack;
    }

    private Button createIconButton(String iconName, double size) {
        FontIcon icon = new FontIcon(iconName); // ví dụ "fas-cog"
        icon.setIconSize((int) (size * 0.55));

        Button btn = new Button();
        btn.setGraphic(icon);
        btn.setStyle("""
            -fx-background-color: #262b40;
            -fx-background-radius: 50%%;
            -fx-min-width: %f; -fx-min-height: %f;
            -fx-cursor: hand;
        """.formatted(size, size));

        return btn;
    }


    private Button createSendButton() {
        FontIcon icon = new FontIcon("fas-paper-plane");
        icon.setIconSize(20);
        icon.setIconColor(Color.WHITE);

        Button btn = new Button();
        btn.setGraphic(icon);

        btn.setStyle("""
            -fx-background-color: linear-gradient(to right, #667eea, #764ba2);
            -fx-background-radius: 50%;
            -fx-min-width: 50; -fx-min-height: 50;
            -fx-max-width: 50; -fx-max-height: 50;
            -fx-cursor: hand;
            -fx-border-width: 0;
        """);

        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.web("#667eea", 0.6));
        shadow.setRadius(15);
        btn.setEffect(shadow);

        return btn;
    }

    // ===== MESSAGE RENDERING =====
    private void displayMessage(Message msg, boolean isOwn) {
        HBox messageBox = new HBox(12);
        messageBox.setPadding(new Insets(5, 0, 5, 0));
        messageBox.setAlignment(isOwn ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        if (!isOwn) {
            // Avatar for received messages
            Circle avatar = new Circle(18);
            avatar.setFill(Color.web("#667eea"));
            messageBox.getChildren().add(avatar);
        }

        VBox bubble = new VBox(8);
        bubble.setMaxWidth(600);
        bubble.setPadding(new Insets(14, 18, 14, 18));
        
        if (isOwn) {
            bubble.setStyle("""
                -fx-background-color: linear-gradient(to right, #667eea, #764ba2);
                -fx-background-radius: 20 20 4 20;
            """);
        } else {
            bubble.setStyle("""
                -fx-background-color: #1a1d2e;
                -fx-background-radius: 20 20 20 4;
            """);
        }

        // Message content
        Label contentLabel = new Label(msg.getContent());
        contentLabel.setWrapText(true);
        contentLabel.setFont(Font.font(15));
        contentLabel.setTextFill(Color.web("#ffffff"));

        // Timestamp
        Label timeLabel = new Label(msg.getCreatedAt().format(TIME_FORMATTER));
        timeLabel.setFont(Font.font(11));
        timeLabel.setTextFill(isOwn ? Color.web("#e0e0e0") : Color.web("#6b7280"));

        bubble.getChildren().addAll(contentLabel, timeLabel);
        messageBox.getChildren().add(bubble);
        
        messageArea.getChildren().add(messageBox);
        
        // Auto scroll
        Platform.runLater(() -> 
            messageScrollPane.setVvalue(messageScrollPane.getVmax())
        );
    }

    // ===== FRIEND CELL =====
    private class FriendCell extends ListCell<Users> {
        @Override
        protected void updateItem(Users friend, boolean empty) {
            super.updateItem(friend, empty);
            
            if (empty || friend == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            HBox cell = new HBox(15);
            cell.setPadding(new Insets(12, 15, 12, 15));
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.setStyle("""
                -fx-background-color: transparent;
                -fx-background-radius: 12;
                -fx-cursor: hand;
            """);

            // Check online status
            boolean isOnline = isUserOnline(friend.getId());
            
            // Avatar with status
            StackPane avatarStack = createAvatarWithStatus("👤", isOnline, 45);

            VBox info = new VBox(5);
            HBox.setHgrow(info, Priority.ALWAYS);

            Label nameLabel = new Label(friend.getDisplayName());
            nameLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
            nameLabel.setTextFill(Color.web("#ffffff"));

            Label statusLabel = new Label(isOnline ? "Online" : "Offline");
            statusLabel.setFont(Font.font(12));
            statusLabel.setTextFill(isOnline ? Color.web("#4ade80") : Color.web("#6b7280"));

            info.getChildren().addAll(nameLabel, statusLabel);
            
            // Unread count badge (if needed)
            Label unreadBadge = new Label("2");
            unreadBadge.setFont(Font.font("System", FontWeight.BOLD, 11));
            unreadBadge.setTextFill(Color.WHITE);
            unreadBadge.setStyle("""
                -fx-background-color: #ef4444;
                -fx-background-radius: 10;
                -fx-padding: 2 8;
            """);
            unreadBadge.setVisible(false); // Show when there are unread messages

            cell.getChildren().addAll(avatarStack, info, unreadBadge);

            setGraphic(cell);
            setStyle("-fx-background-color: transparent; -fx-padding: 5;");
            
            // Hover effect
            cell.setOnMouseEntered(e -> 
                cell.setStyle(cell.getStyle() + "-fx-background-color: #262b40;")
            );
            cell.setOnMouseExited(e -> 
                cell.setStyle(cell.getStyle().replace("-fx-background-color: #262b40;", ""))
            );
        }
    }

    // ===== CORE FUNCTIONS =====
    private void loadFriendsWithStatus() {
        List<Users> friends = chatService.listFriends(currentUserId);
        friendListView.getItems().setAll(friends);
    }

    private void filterFriends(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            loadFriendsWithStatus();
            return;
        }
        
        List<Users> allFriends = chatService.listFriends(currentUserId);
        List<Users> filtered = allFriends.stream()
            .filter(f -> f.getDisplayName().toLowerCase().contains(keyword.toLowerCase()) ||
                        f.getUsername().toLowerCase().contains(keyword.toLowerCase()))
            .toList();
        
        friendListView.getItems().setAll(filtered);
    }

    private boolean isUserOnline(Integer userId) {
        if (clientManager == null) return false;
        List<PeerInfo> onlinePeers = clientManager.getOnlinePeers();
        return onlinePeers.stream().anyMatch(peer -> peer.getUserId().equals(userId));
    }

    private void openChatWithUser(Users friend) {
        currentChatUser = friend;
        chatTitleLabel.setText(friend.getDisplayName());
        
        // Update status
        boolean isOnline = isUserOnline(friend.getId());
        chatStatusLabel.setText(isOnline ? "Online" : "Offline");
        chatStatusLabel.setTextFill(isOnline ? Color.web("#4ade80") : Color.web("#6b7280"));
        
        // Get or create conversation
        currentConversation = chatService.getDirectConversation(currentUserId, friend.getId());
        if (currentConversation == null) {
            currentConversation = chatService.createDirectConversation(currentUserId, friend.getId());
        }
        
        // Load messages
        messageArea.getChildren().clear();
        if (currentConversation != null) {
            List<Message> messages = chatService.listMessages(currentConversation.getId());
            for (Message msg : messages) {
                boolean isOwn = msg.getSender().getId().equals(currentUserId);
                displayMessage(msg, isOwn);
            }
        }
    }

    private void sendMessage() {
        String content = messageInput.getText().trim();
        if (content.isEmpty() || currentConversation == null) return;

        chatController.sendMessage(currentConversation.getId(), content);
        
        // Display immediately
        Message tempMsg = new Message();
        tempMsg.setContent(content);
        tempMsg.setSender(chatService.getUserById(currentUserId));
        tempMsg.setCreatedAt(java.time.LocalDateTime.now());
        displayMessage(tempMsg, true);
        
        messageInput.clear();
    }

    private void startStatusRefreshTimer() {
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(5), e -> {
                Platform.runLater(() -> {
                    friendListView.refresh();
                    // Update current chat status if open
                    if (currentChatUser != null) {
                        boolean isOnline = isUserOnline(currentChatUser.getId());
                        chatStatusLabel.setText(isOnline ? "Online" : "Offline");
                        chatStatusLabel.setTextFill(isOnline ? Color.web("#4ade80") : Color.web("#6b7280"));
                    }
                });
            })
        );
        timeline.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        timeline.play();
    }

    // ===== P2P LISTENERS =====
    private void setupP2PListeners() {
        p2pManager.setEventListener(new P2PManager.P2PEventListener() {
            @Override
            public void onChatMessageReceived(Integer conversationId, Message message) {
                Platform.runLater(() -> {
                    if (currentConversation != null && 
                        currentConversation.getId().equals(conversationId)) {
                        displayMessage(message, false);
                    }
                    loadFriendsWithStatus();
                });
            }

            @Override
            public void onTypingReceived(Integer conversationId, Integer userId) {
                Platform.runLater(() -> {
                    if (currentConversation != null && 
                        currentConversation.getId().equals(conversationId)) {
                        Users user = chatService.getUserById(userId);
                        typingIndicator.setText(user.getDisplayName() + " is typing...");
                        typingIndicator.setVisible(true);
                        
                        new java.util.Timer().schedule(new java.util.TimerTask() {
                            @Override
                            public void run() {
                                Platform.runLater(() -> typingIndicator.setVisible(false));
                            }
                        }, 3000);
                    }
                });
            }

            @Override
            public void onFileRequestReceived(Integer fromUser, String fileName, Integer fileSize) {
                Platform.runLater(() -> 
                    showAlert("File Request", 
                        chatService.getUserById(fromUser).getDisplayName() + 
                        " wants to send: " + fileName)
                );
            }

            @Override
            public void onCallOfferReceived(Integer fromUser, String sdp) {
                Platform.runLater(() -> 
                    showAlert("Incoming Call", 
                        chatService.getUserById(fromUser).getDisplayName() + " is calling...")
                );
            }

            @Override
            public void onConnectionLost(Integer userId) {
                Platform.runLater(() -> {
                    friendListView.refresh();
                });
            }

			@Override
			public void onChatRequestReceived(Integer fromUser, String fromDisplayName) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void onChatRequestResponse(Integer fromUser, boolean accepted) {
				// TODO Auto-generated method stub
				
			}
        });
    }

    // ===== DIALOGS & ALERTS =====
    private void showSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("Connection Status");
        
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        
        Users currentUser = chatService.getUserById(currentUserId);
        Label userLabel = new Label("User: " + currentUser.getDisplayName());
        Label statusLabel = new Label("Server: " + 
            (clientManager != null && clientManager.isConnectedToSignalingServer() ? 
            "✅ Connected" : "❌ Disconnected"));
        
        content.getChildren().addAll(userLabel, statusLabel);
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.showAndWait();
    }

    private void showConversationInfo() {
        if (currentChatUser == null) return;
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("User Info");
        alert.setHeaderText(currentChatUser.getDisplayName());
        alert.setContentText("Username: " + currentChatUser.getUsername() + "\n" +
                            "Status: " + (isUserOnline(currentChatUser.getId()) ? "Online" : "Offline"));
        alert.showAndWait();
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void handleAttachment() {
    	showAlert("File", "Sending file request...");
    }

    private void showEmojiPicker() {
        showAlert("Feature", "Emoji picker - Coming soon");
    }

    private void handleVoiceCall() {
        showAlert("Feature", "Voice call - Coming soon");
    }

    private void handleVideoCall() {
        showAlert("Feature", "Video call - Coming soon");
    }
    
    private void handleAudioRecord() {
        showAlert("Feature", "Audio recording - Coming soon");
    }
    
    

}