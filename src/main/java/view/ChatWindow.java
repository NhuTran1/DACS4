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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import model.Conversation;
import model.Message;
import model.Users;
import network.p2p.P2PManager;
import service.ChatService;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatWindow {
    private final Stage stage;
    private final ChatService chatService;
    private final P2PManager p2pManager;
    private final Integer currentUserId;
    private final ChatController chatController;
    private final client.ClientManager clientManager;
    
    // UI Components
    private ListView<Conversation> conversationListView;
    private VBox messageArea;
    private ScrollPane messageScrollPane;
    private TextField messageInput;
    private Label chatTitleLabel;
    private Label typingIndicator;
    
    // Current state
    private Conversation currentConversation;
    private Map<Integer, VBox> conversationMessageBoxes = new HashMap<>();
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public ChatWindow(Stage stage, ChatService chatService, P2PManager p2pManager, Integer userId, client.ClientManager clientManager) {
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
        root.setStyle("-fx-background-color: #0f1419;");

        // Left Sidebar
        VBox sidebar = createSidebar();
        
        // Center Chat Area
        VBox chatArea = createChatArea();
        
        root.setLeft(sidebar);
        root.setCenter(chatArea);

        Scene scene = new Scene(root, 1200, 750);
        stage.setScene(scene);
        stage.setTitle("Chat App");
        
        // Handle window close event
        stage.setOnCloseRequest(e -> {
            System.out.println("🛑 Application closing...");
            if (clientManager != null) {
                clientManager.shutdown();
            }
            if (chatController != null) {
                chatController.shutdown();
            }
        });
        
        stage.show();
        
        // Load conversations
        loadConversations();
    }

    // ===== SIDEBAR =====
    private VBox createSidebar() {
        VBox sidebar = new VBox(15);
        sidebar.setPrefWidth(320);
        sidebar.setStyle("-fx-background-color: #16213e; -fx-padding: 20;");

        // Header
        HBox header = createSidebarHeader();
        
        // Search box
        TextField searchField = createSearchField();
        
        // Conversation list
        conversationListView = new ListView<>();
        conversationListView.setStyle("""
            -fx-background-color: transparent;
            -fx-border-width: 0;
            -fx-focus-color: transparent;
        """);
        conversationListView.setCellFactory(lv -> new ConversationCell());
        conversationListView.setOnMouseClicked(e -> {
            Conversation selected = conversationListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                switchToConversation(selected);
            }
        });
        
        VBox.setVgrow(conversationListView, Priority.ALWAYS);

        // Action buttons
        HBox actionButtons = createActionButtons();

        sidebar.getChildren().addAll(header, searchField, conversationListView, actionButtons);
        return sidebar;
    }

    private HBox createSidebarHeader() {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label appTitle = new Label("💬 Chats");
        appTitle.setFont(Font.font("System", FontWeight.BOLD, 24));
        appTitle.setTextFill(Color.web("#eaeaea"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button settingsBtn = createIconButton("⚙️");
        settingsBtn.setOnAction(e -> showSettingsDialog());

        header.getChildren().addAll(appTitle, spacer, settingsBtn);
        return header;
    }

    private TextField createSearchField() {
        TextField search = new TextField();
        search.setPromptText("🔍 Search conversations...");
        search.setPrefHeight(45);
        search.setStyle("""
            -fx-background-color: rgba(102, 126, 234, 0.15);
            -fx-background-radius: 10;
            -fx-text-fill: #eaeaea;
            -fx-prompt-text-fill: #888;
            -fx-border-width: 0;
            -fx-padding: 0 15;
            -fx-font-size: 14;
        """);
        
        search.textProperty().addListener((obs, old, newVal) -> filterConversations(newVal));
        
        return search;
    }

    private HBox createActionButtons() {
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        Button newChatBtn = createGradientButton("➕ New Chat");
        newChatBtn.setOnAction(e -> showNewChatDialog());
        
        Button newGroupBtn = createGradientButton("👥 New Group");
        newGroupBtn.setOnAction(e -> showNewGroupDialog());

        buttons.getChildren().addAll(newChatBtn, newGroupBtn);
        return buttons;
    }

    // ===== CHAT AREA =====
    private VBox createChatArea() {
        VBox chatArea = new VBox();
        chatArea.setStyle("-fx-background-color: #0f1419;");

        // Chat header
        HBox chatHeader = createChatHeader();
        
        // Message area
        messageArea = new VBox(10);
        messageArea.setPadding(new Insets(20));
        messageArea.setStyle("-fx-background-color: #0f1419;");
        
        messageScrollPane = new ScrollPane(messageArea);
        messageScrollPane.setFitToWidth(true);
        messageScrollPane.setStyle("""
            -fx-background-color: transparent;
            -fx-border-width: 0;
        """);
        VBox.setVgrow(messageScrollPane, Priority.ALWAYS);

        // Typing indicator
        typingIndicator = new Label();
        typingIndicator.setTextFill(Color.web("#888"));
        typingIndicator.setFont(Font.font(12));
        typingIndicator.setPadding(new Insets(0, 0, 5, 20));
        typingIndicator.setVisible(false);

        // Message input
        HBox inputArea = createInputArea();

        chatArea.getChildren().addAll(chatHeader, messageScrollPane, typingIndicator, inputArea);
        return chatArea;
    }

    private HBox createChatHeader() {
        HBox header = new HBox(15);
        header.setPadding(new Insets(20));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("""
            -fx-background-color: #16213e;
            -fx-border-color: rgba(102, 126, 234, 0.2);
            -fx-border-width: 0 0 1 0;
        """);

        Label avatar = new Label("👤");
        avatar.setFont(Font.font(32));
        avatar.setStyle("""
            -fx-background-color: rgba(102, 126, 234, 0.3);
            -fx-background-radius: 50%;
            -fx-min-width: 50; -fx-min-height: 50;
            -fx-max-width: 50; -fx-max-height: 50;
            -fx-alignment: center;
        """);

        chatTitleLabel = new Label("Select a conversation");
        chatTitleLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        chatTitleLabel.setTextFill(Color.web("#eaeaea"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button callBtn = createIconButton("📞");
        Button videoBtn = createIconButton("📹");
        Button infoBtn = createIconButton("ℹ️");

        callBtn.setOnAction(e -> handleVoiceCall());
        videoBtn.setOnAction(e -> handleVideoCall());
        infoBtn.setOnAction(e -> showConversationInfo());

        header.getChildren().addAll(avatar, chatTitleLabel, spacer, callBtn, videoBtn, infoBtn);
        return header;
    }

    private HBox createInputArea() {
        HBox inputArea = new HBox(10);
        inputArea.setPadding(new Insets(15, 20, 20, 20));
        inputArea.setAlignment(Pos.CENTER);
        inputArea.setStyle("-fx-background-color: #16213e;");

        Button attachBtn = createIconButton("📎");
        Button emojiBtn = createIconButton("😊");
        
        messageInput = new TextField();
        messageInput.setPromptText("Type a message...");
        messageInput.setPrefHeight(45);
        messageInput.setStyle("""
            -fx-background-color: rgba(15, 52, 96, 0.5);
            -fx-background-radius: 22.5;
            -fx-text-fill: #eaeaea;
            -fx-prompt-text-fill: #888;
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
    private Button createIconButton(String icon) {
        Button btn = new Button(icon);
        btn.setFont(Font.font(18));
        btn.setStyle("""
            -fx-background-color: rgba(102, 126, 234, 0.2);
            -fx-background-radius: 50%;
            -fx-text-fill: #eaeaea;
            -fx-min-width: 40; -fx-min-height: 40;
            -fx-max-width: 40; -fx-max-height: 40;
            -fx-cursor: hand;
            -fx-border-width: 0;
        """);
        
        btn.setOnMouseEntered(e -> 
            btn.setStyle(btn.getStyle() + "-fx-background-color: rgba(102, 126, 234, 0.4);")
        );
        btn.setOnMouseExited(e -> 
            btn.setStyle(btn.getStyle().replace("0.4", "0.2"))
        );
        
        return btn;
    }

    private Button createGradientButton(String text) {
        Button btn = new Button(text);
        btn.setPrefHeight(40);
        btn.setFont(Font.font("System", FontWeight.BOLD, 13));
        btn.setTextFill(Color.WHITE);
        btn.setStyle("""
            -fx-background-color: linear-gradient(to right, #667eea, #764ba2);
            -fx-background-radius: 10;
            -fx-cursor: hand;
            -fx-border-width: 0;
        """);
        HBox.setHgrow(btn, Priority.ALWAYS);
        btn.setMaxWidth(Double.MAX_VALUE);
        return btn;
    }

    private Button createSendButton() {
        Button btn = new Button("➤");
        btn.setFont(Font.font(20));
        btn.setStyle("""
            -fx-background-color: linear-gradient(to right, #667eea, #764ba2);
            -fx-background-radius: 50%;
            -fx-text-fill: white;
            -fx-min-width: 45; -fx-min-height: 45;
            -fx-max-width: 45; -fx-max-height: 45;
            -fx-cursor: hand;
            -fx-border-width: 0;
        """);
        
        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.web("#667eea", 0.5));
        shadow.setRadius(10);
        btn.setEffect(shadow);
        
        return btn;
    }

    // ===== MESSAGE RENDERING =====
    private void displayMessage(Message msg, boolean isOwn) {
        HBox messageBox = new HBox(10);
        messageBox.setPadding(new Insets(5, 0, 5, 0));
        messageBox.setAlignment(isOwn ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox bubble = new VBox(5);
        bubble.setMaxWidth(500);
        bubble.setPadding(new Insets(12, 16, 12, 16));
        
        if (isOwn) {
            bubble.setStyle("""
                -fx-background-color: linear-gradient(to right, #667eea, #764ba2);
                -fx-background-radius: 18 18 4 18;
            """);
        } else {
            bubble.setStyle("""
                -fx-background-color: #1e2a3a;
                -fx-background-radius: 18 18 18 4;
            """);
        }

        // Sender name (for group chats)
        if (!isOwn && currentConversation != null && 
            currentConversation.getType() == Conversation.ConversationType.group) {
            Label senderLabel = new Label(msg.getSender().getDisplayName());
            senderLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
            senderLabel.setTextFill(Color.web("#667eea"));
            bubble.getChildren().add(senderLabel);
        }

        // Message content
        Label contentLabel = new Label(msg.getContent());
        contentLabel.setWrapText(true);
        contentLabel.setFont(Font.font(14));
        contentLabel.setTextFill(Color.web("#eaeaea"));

        // Timestamp
        Label timeLabel = new Label(msg.getCreatedAt().format(TIME_FORMATTER));
        timeLabel.setFont(Font.font(11));
        timeLabel.setTextFill(Color.web("#aaa"));

        bubble.getChildren().addAll(contentLabel, timeLabel);
        messageBox.getChildren().add(bubble);
        
        messageArea.getChildren().add(messageBox);
        
        // Auto scroll to bottom
        Platform.runLater(() -> 
            messageScrollPane.setVvalue(messageScrollPane.getVmax())
        );
    }

    // ===== CONVERSATION CELL =====
    private class ConversationCell extends ListCell<Conversation> {
        @Override
        protected void updateItem(Conversation conv, boolean empty) {
            super.updateItem(conv, empty);
            
            if (empty || conv == null) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-background-color: transparent;");
                return;
            }

            HBox cell = new HBox(12);
            cell.setPadding(new Insets(12));
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.setStyle("""
                -fx-background-color: rgba(102, 126, 234, 0.1);
                -fx-background-radius: 12;
                -fx-cursor: hand;
            """);

            Label avatar = new Label(conv.getType() == Conversation.ConversationType.group ? "👥" : "👤");
            avatar.setFont(Font.font(28));

            VBox info = new VBox(5);
            HBox.setHgrow(info, Priority.ALWAYS);

            String displayName = getConversationDisplayName(conv);
            Label nameLabel = new Label(displayName);
            nameLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
            nameLabel.setTextFill(Color.web("#eaeaea"));

            Label lastMsg = new Label("Click to view messages");
            lastMsg.setFont(Font.font(12));
            lastMsg.setTextFill(Color.web("#aaa"));

            info.getChildren().addAll(nameLabel, lastMsg);
            cell.getChildren().addAll(avatar, info);

            setGraphic(cell);
            setStyle("-fx-background-color: transparent; -fx-padding: 5;");
            
            // Hover effect
            cell.setOnMouseEntered(e -> 
                cell.setStyle(cell.getStyle() + "-fx-background-color: rgba(102, 126, 234, 0.2);")
            );
            cell.setOnMouseExited(e -> 
                cell.setStyle(cell.getStyle().replace("0.2", "0.1"))
            );
        }
    }

    // ===== CONTROLLER ACTIONS =====
    private void sendMessage() {
        String content = messageInput.getText().trim();
        if (content.isEmpty() || currentConversation == null) return;

        chatController.sendMessage(currentConversation.getId(), content);
        
        // Display immediately (optimistic UI)
        Message tempMsg = new Message();
        tempMsg.setContent(content);
        tempMsg.setSender(chatService.getUserById(currentUserId));
        tempMsg.setCreatedAt(java.time.LocalDateTime.now());
        displayMessage(tempMsg, true);
        
        messageInput.clear();
    }

    private void switchToConversation(Conversation conv) {
        currentConversation = conv;
        chatTitleLabel.setText(getConversationDisplayName(conv));
        
        messageArea.getChildren().clear();
        
        List<Message> messages = chatService.listMessages(conv.getId());
        for (Message msg : messages) {
            boolean isOwn = msg.getSender().getId().equals(currentUserId);
            displayMessage(msg, isOwn);
        }
    }

    private void loadConversations() {
        List<Conversation> conversations = chatService.listConversationsByUser(currentUserId);
        conversationListView.getItems().setAll(conversations);
    }

    private void filterConversations(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            loadConversations();
            return;
        }
        
        List<Conversation> all = chatService.listConversationsByUser(currentUserId);
        List<Conversation> filtered = all.stream()
            .filter(c -> getConversationDisplayName(c).toLowerCase().contains(keyword.toLowerCase()))
            .toList();
        
        conversationListView.getItems().setAll(filtered);
    }

    private String getConversationDisplayName(Conversation conv) {
        if (conv.getType() == Conversation.ConversationType.group) {
            return conv.getName() != null ? conv.getName() : "Group Chat";
        }
        
        List<Users> participants = chatService.listParticipants(conv.getId());
        return participants.stream()
            .filter(u -> !u.getId().equals(currentUserId))
            .findFirst()
            .map(Users::getDisplayName)
            .orElse("Unknown");
    }

    // ===== DIALOGS =====
    private void showNewChatDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Direct Chat");
        dialog.setHeaderText("Start a new conversation");
        dialog.setContentText("Enter friend's username:");
        
        dialog.showAndWait().ifPresent(username -> {
            Users friend = chatService.getUserByUsername(username);
            if (friend == null) {
                showAlert("User not found", "No user with username: " + username);
                return;
            }
            
            chatController.createDirectConversation(friend.getId(), result -> {
                if (result.success) {
                    Platform.runLater(() -> {
                        loadConversations();
                        showAlert("Success", "Conversation created!");
                    });
                } else {
                    Platform.runLater(() -> 
                        showAlert("Error", result.message)
                    );
                }
            });
        });
    }

    private void showNewGroupDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Create Group");
        dialog.setHeaderText("Create a new group conversation");
        
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        
        TextField groupNameField = new TextField();
        groupNameField.setPromptText("Group name");
        
        TextField membersField = new TextField();
        membersField.setPromptText("Member usernames (comma-separated)");
        
        content.getChildren().addAll(
            new Label("Group Name:"), groupNameField,
            new Label("Members:"), membersField
        );
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        dialog.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                String groupName = groupNameField.getText().trim();
                String[] usernames = membersField.getText().split(",");
                
                chatController.createGroupConversation(groupName, usernames, result -> {
                    Platform.runLater(() -> {
                        if (result.success) {
                            loadConversations();
                            showAlert("Success", "Group created!");
                        } else {
                            showAlert("Error", result.message);
                        }
                    });
                });
            }
        });
    }

    private void showSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Settings & Info");
        dialog.setHeaderText("Connection Status");
        
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setStyle("-fx-background-color: #16213e;");
        
        // Connection status
        Label statusLabel = new Label("Signaling Server: " + 
            (clientManager.isConnectedToSignalingServer() ? "✅ Connected" : "❌ Disconnected"));
        statusLabel.setTextFill(Color.web("#eaeaea"));
        statusLabel.setFont(Font.font(14));
        
        Label userLabel = new Label("User: " + chatService.getUserById(currentUserId).getDisplayName() + 
            " (ID: " + currentUserId + ")");
        userLabel.setTextFill(Color.web("#eaeaea"));
        userLabel.setFont(Font.font(14));
        
        Label portLabel = new Label("P2P Port: " + clientManager.getP2pPort());
        portLabel.setTextFill(Color.web("#eaeaea"));
        portLabel.setFont(Font.font(14));
        
        // Online peers
        Label peersHeader = new Label("\n👥 Online Friends:");
        peersHeader.setTextFill(Color.web("#667eea"));
        peersHeader.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        VBox peersList = new VBox(5);
        List<network.p2p.PeerInfo> onlinePeers = clientManager.getOnlinePeers();
        
        if (onlinePeers.isEmpty()) {
            Label noPeers = new Label("  No friends online");
            noPeers.setTextFill(Color.web("#888"));
            noPeers.setFont(Font.font(12));
            peersList.getChildren().add(noPeers);
        } else {
            for (network.p2p.PeerInfo peer : onlinePeers) {
                if (!peer.getUserId().equals(currentUserId)) {
                    Label peerLabel = new Label("  • " + getConversationDisplayNameForUser(peer.getUserId()) + 
                        " (" + peer.getIp() + ":" + peer.getPort() + ")");
                    peerLabel.setTextFill(Color.web("#aaa"));
                    peerLabel.setFont(Font.font(12));
                    peersList.getChildren().add(peerLabel);
                }
            }
        }
        
        content.getChildren().addAll(statusLabel, userLabel, portLabel, peersHeader, peersList);
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setStyle("-fx-background-color: #16213e;");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK);
        
        dialog.showAndWait();
    }
    
    private String getConversationDisplayNameForUser(Integer userId) {
        Users user = chatService.getUserById(userId);
        return user != null ? user.getDisplayName() : "User" + userId;
    }

    private void showConversationInfo() {
        if (currentConversation == null) return;
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Conversation Info");
        alert.setHeaderText(getConversationDisplayName(currentConversation));
        
        List<Users> participants = chatService.listParticipants(currentConversation.getId());
        String members = participants.stream()
            .map(Users::getDisplayName)
            .reduce((a, b) -> a + ", " + b)
            .orElse("None");
        
        alert.setContentText("Members: " + members);
        alert.showAndWait();
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
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
                    loadConversations(); // Update conversation list
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
                        
                        // Hide after 3 seconds
                        new Thread(() -> {
                            try {
                                Thread.sleep(3000);
                                Platform.runLater(() -> typingIndicator.setVisible(false));
                            } catch (InterruptedException ignored) {}
                        }).start();
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
                System.out.println("Connection lost with user: " + userId);
            }
        });
    }

    // ===== PLACEHOLDER METHODS =====
    private void handleAttachment() {
        showAlert("Feature", "File attachment - To be implemented");
    }

    private void showEmojiPicker() {
        showAlert("Feature", "Emoji picker - To be implemented");
    }

    private void handleVoiceCall() {
        if (currentConversation == null) return;
        showAlert("Feature", "Voice call - To be implemented");
    }

    private void handleVideoCall() {
        if (currentConversation == null) return;
        showAlert("Feature", "Video call - To be implemented");
    }
}