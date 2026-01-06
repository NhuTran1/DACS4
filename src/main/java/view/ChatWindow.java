package view;

import client.ClientManager;
import controller.ChatController;
import dao.FileAttachmentDao;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
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
import javafx.util.Duration;
import model.Conversation;
import model.FileAttachment;
import model.FriendRequest;
import model.Message;
import model.Users;
import network.p2p.P2PManager;
import network.p2p.PeerInfo;
import service.ChatService;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.kordamp.ikonli.javafx.FontIcon;

public class ChatWindow {
    private final Stage stage;
    private final ChatService chatService;
    private final P2PManager p2pManager;
    private final Integer currentUserId;
    private final ChatController chatController;
    private final ClientManager clientManager;
    
    // UI Components
    private TabPane leftTabPane;
    private ListView<PeerInfo> onlineListView;
    private ListView<Users> friendListView;
    private ListView<Conversation> groupListView;
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
 // ChatWindow field
    private final Map<String, File> pendingUploadFiles = new ConcurrentHashMap<>();

    
    // ✅ Shutdown management
    private javafx.animation.Timeline statusRefreshTimeline;
    private volatile boolean isShuttingDown = false;
    
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

        // Left Sidebar với 3 tabs
        VBox sidebar = createSidebarWithTabs();
        
        // Center Chat Area
        VBox chatArea = createChatArea();
        
        root.setLeft(sidebar);
        root.setCenter(chatArea);

        Scene scene = new Scene(root, 1400, 850);
        stage.setScene(scene);
        stage.setTitle("Chat Application");
        stage.setResizable(true);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        
        scene.getStylesheets().add(
            getClass().getResource("/css/chat.css").toExternalForm()
        );
        
        stage.setOnCloseRequest(e -> {
            // ✅ Prevent default close behavior
            e.consume();
            
            // ✅ Shutdown in background thread to avoid blocking UI
            new Thread(() -> {
                try {
                    isShuttingDown = true;
                    
                    // ✅ Stop all animations and timers on JavaFX thread
                    Platform.runLater(() -> {
                        try {
                            if (statusRefreshTimeline != null) {
                                statusRefreshTimeline.stop();
                            }
                            
                            // ✅ Close all open dialogs
                            closeAllDialogs();
                        } catch (Exception ex) {
                            System.err.println("⚠️ Error stopping animations: " + ex.getMessage());
                        }
                    });
                    
                    // ✅ Wait a bit for UI updates to complete
                    Thread.sleep(50); // Reduced from 150ms
                    
                    // ✅ Shutdown controllers (non-blocking with timeout)
                    shutdownControllers();
                    
                    // ✅ Force exit immediately (no additional delay)
                    
                    // ✅ Exit on JavaFX thread
                    Platform.runLater(() -> {
                        try {
                            Platform.exit();
                        } catch (Exception ex) {
                            // Ignore
                        }
                        System.exit(0);
                    });
                    
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    // ✅ Force exit on interrupt
                    Platform.runLater(() -> {
                        Platform.exit();
                        System.exit(0);
                    });
                } catch (Exception ex) {
                    System.err.println("❌ Error during shutdown: " + ex.getMessage());
                    // ✅ Force exit even if shutdown fails
                    Platform.runLater(() -> {
                        Platform.exit();
                        System.exit(0);
                    });
                }
            }, "ShutdownThread").start();
        });
        
        // ✅ Set implicit exit to ensure app closes properly
        Platform.setImplicitExit(true);
        
        stage.show();
        
        loadAllData();
        startStatusRefreshTimer();
    }

    // ===== SIDEBAR WITH 3 TABS =====
    private VBox createSidebarWithTabs() {
        VBox sidebar = new VBox(15);
        sidebar.setPrefWidth(350);
        sidebar.setStyle("-fx-background-color: #1a1d2e; -fx-padding: 20;");

        // Header
        HBox header = createSidebarHeader();
        
        // Search box - dynamic based on selected tab
        TextField searchField = createSearchField();
        
        // Update search placeholder when tab changes
        leftTabPane = new TabPane();
        leftTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null) {
                String tabText = newTab.getText();
                if (tabText.contains("Friends")) {
                    searchField.setPromptText("🔍 Search friends...");
                } else if (tabText.contains("Groups")) {
                    searchField.setPromptText("🔍 Search groups...");
                } else if (tabText.contains("Online")) {
                    searchField.setPromptText("🔍 Search online users...");
                }
            }
        });
        
        // Dynamic search based on selected tab
        searchField.textProperty().addListener((obs, old, newVal) -> {
            Tab selectedTab = leftTabPane.getSelectionModel().getSelectedItem();
            if (selectedTab != null) {
                String tabText = selectedTab.getText();
                if (tabText.contains("Friends")) {
                    filterFriends(newVal);
                } else if (tabText.contains("Groups")) {
                    filterGroups(newVal);
                } else if (tabText.contains("Online")) {
                    filterOnlineUsers(newVal);
                }
            }
        });
        
        // Tab Pane với styling đẹp
        leftTabPane = new TabPane();
        leftTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        leftTabPane.setStyle("""
            -fx-background-color: transparent;
            -fx-tab-min-width: 100;
            -fx-tab-max-width: 120;
        """);
        
        // Style cho tabs
        leftTabPane.getStyleClass().add("custom-tab-pane");
        
        // Tab 1: Online Users
        Tab onlineTab = createOnlineTab();
        
        // Tab 2: Friends
        Tab friendTab = createFriendTab();
        
        // Tab 3: Groups
        Tab groupTab = createGroupTab();
        
        leftTabPane.getTabs().addAll(onlineTab, friendTab, groupTab);
        VBox.setVgrow(leftTabPane, Priority.ALWAYS);

        sidebar.getChildren().addAll(header, searchField, leftTabPane);
        return sidebar;
    }

    // ===== TAB 1: ONLINE USERS =====
    private Tab createOnlineTab() {
        Tab tab = new Tab("🌐");
        tab.setStyle("""
            -fx-background-color: #262b40;
            -fx-text-fill: #aaaaaa;
            -fx-font-weight: bold;
        """);
        
        VBox content = new VBox(7);
        content.setPadding(new Insets(10));
        
        Label infoLabel = new Label("Users online in your network");
        infoLabel.setTextFill(Color.web("#aaaaaa"));
        infoLabel.setFont(Font.font(12));
        
        onlineListView = new ListView<>();
        onlineListView.setStyle("""
            -fx-background-color: transparent;
            -fx-border-width: 0;
        """);
        onlineListView.setCellFactory(lv -> new OnlineUserCell());
        onlineListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                PeerInfo selected = onlineListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    showAddFriendDialog(selected);
                }
            }
        });
        
        VBox.setVgrow(onlineListView, Priority.ALWAYS);
        
        content.getChildren().addAll(infoLabel, onlineListView);
        tab.setContent(content);
        
        return tab;
    }

    // ===== TAB 2: FRIENDS =====
    private Tab createFriendTab() {
        Tab tab = new Tab("👥");
        tab.setStyle("""
            -fx-background-color: #262b40;
            -fx-text-fill: #aaaaaa;
            -fx-font-weight: bold;
        """);
        
        VBox content = new VBox(7);
        content.setPadding(new Insets(10));
        
        HBox headerBox = new HBox(10);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        
        Label infoLabel = new Label("Your friends");
        infoLabel.setTextFill(Color.web("#aaaaaa"));
        infoLabel.setFont(Font.font(12));
        HBox.setHgrow(infoLabel, Priority.ALWAYS);
        
        // Button to view friend requests
        Button requestBtn = createIconButton("fas-user-plus", 30);
        requestBtn.setTooltip(new Tooltip("Friend Requests"));
        requestBtn.setOnAction(e -> showFriendRequestsDialog());
        
        headerBox.getChildren().addAll(infoLabel, requestBtn);
        
        friendListView = new ListView<>();
        friendListView.setStyle("""
            -fx-background-color: transparent;
            -fx-border-width: 0;
        """);
        friendListView.setCellFactory(lv -> new FriendCell());
        friendListView.setOnMouseClicked(e -> {
            Users selected = friendListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                openChatWithUser(selected);
            }
        });
        
        VBox.setVgrow(friendListView, Priority.ALWAYS);
        
        content.getChildren().addAll(headerBox, friendListView);
        tab.setContent(content);
        
        return tab;
    }

    // ===== TAB 3: GROUPS =====
    private Tab createGroupTab() {
        Tab tab = new Tab("💬");
        tab.setStyle("""
            -fx-background-color: #262b40;
            -fx-text-fill: #aaaaaa;
            -fx-font-weight: bold;
        """);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        
        HBox headerBox = new HBox(10);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        
        Label infoLabel = new Label("Your group chats");
        infoLabel.setTextFill(Color.web("#aaaaaa"));
        infoLabel.setFont(Font.font(12));
        HBox.setHgrow(infoLabel, Priority.ALWAYS);
        
        // Button to create new group
        Button createGroupBtn = createIconButton("fas-plus", 30);
        createGroupBtn.setTooltip(new Tooltip("Create Group"));
        createGroupBtn.setOnAction(e -> showCreateGroupDialog());
        
        headerBox.getChildren().addAll(infoLabel, createGroupBtn);
        
        groupListView = new ListView<>();
        groupListView.setStyle("""
            -fx-background-color: transparent;
            -fx-border-width: 0;
        """);
        groupListView.setCellFactory(lv -> new GroupCell());
        groupListView.setOnMouseClicked(e -> {
            Conversation selected = groupListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                openGroupConversation(selected);
            }
        });
        
        VBox.setVgrow(groupListView, Priority.ALWAYS);
        
        content.getChildren().addAll(headerBox, groupListView);
        tab.setContent(content);
        
        return tab;
    }

 // ===== CUSTOM CELLS =====
    private class OnlineUserCell extends ListCell<PeerInfo> {
        @Override
        protected void updateItem(PeerInfo peer, boolean empty) {
            super.updateItem(peer, empty);
            
            if (empty || peer == null) {
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

            StackPane avatarStack = createAvatarWithStatus("👤", true, 45);

            VBox info = new VBox(5);
            HBox.setHgrow(info, Priority.ALWAYS);

            Users user = chatService.getUserById(peer.getUserId());
            String displayName = user != null ? user.getDisplayName() : "User" + peer.getUserId();
            
            Label nameLabel = new Label(displayName);
            nameLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
            nameLabel.setTextFill(Color.web("#ffffff"));

            Label statusLabel = new Label("🟢 Online • " + peer.getIp());
            statusLabel.setFont(Font.font(11));
            statusLabel.setTextFill(Color.web("#4ade80"));

            info.getChildren().addAll(nameLabel, statusLabel);
            
            if (peer.getUserId().equals(currentUserId)) {
                return;
            }
            
            // Check if already friend
            boolean isFriend = chatService.listFriends(currentUserId).stream()
                .anyMatch(f -> f.getId().equals(peer.getUserId()));
                
            
            if (isFriend) {
                Label friendBadge = new Label("✓ Friend");
                friendBadge.setFont(Font.font("System", FontWeight.BOLD, 10));
                friendBadge.setTextFill(Color.web("#4ade80"));
                friendBadge.setStyle("""
                    -fx-background-color: rgba(74, 222, 128, 0.2);
                    -fx-background-radius: 8;
                    -fx-padding: 3 8;
                """);
                cell.getChildren().addAll(avatarStack, info, friendBadge);
            } else {
                Button addBtn = new Button("+");
                addBtn.setStyle("""
                    -fx-background-color: #667eea;
                    -fx-text-fill: white;
                    -fx-background-radius: 50%;
                    -fx-min-width: 30;
                    -fx-min-height: 30;
                    -fx-font-weight: bold;
                    -fx-cursor: hand;
                """);
                addBtn.setOnAction(e -> showAddFriendDialog(peer));
                cell.getChildren().addAll(avatarStack, info, addBtn);
            }

            setGraphic(cell);
            setStyle("-fx-background-color: transparent; -fx-padding: 5;");
            
            cell.setOnMouseEntered(e -> 
                cell.setStyle(cell.getStyle() + "-fx-background-color: #262b40;")
            );
            cell.setOnMouseExited(e -> 
                cell.setStyle(cell.getStyle().replace("-fx-background-color: #262b40;", ""))
            );
        }
    }

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

            boolean isOnline = isUserOnline(friend.getId());
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
            cell.getChildren().addAll(avatarStack, info);

            setGraphic(cell);
            setStyle("-fx-background-color: transparent; -fx-padding: 5;");
            
            cell.setOnMouseEntered(e -> 
                cell.setStyle(cell.getStyle() + "-fx-background-color: #262b40;")
            );
            cell.setOnMouseExited(e -> 
                cell.setStyle(cell.getStyle().replace("-fx-background-color: #262b40;", ""))
            );
        }
    }

    private class GroupCell extends ListCell<Conversation> {
        @Override
        protected void updateItem(Conversation group, boolean empty) {
            super.updateItem(group, empty);
            
            if (empty || group == null) {
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

            Circle avatar = new Circle(22.5);
            avatar.setFill(Color.web("#764ba2"));
            
            Label avatarLabel = new Label("👥");
            avatarLabel.setFont(Font.font(25));
            
            StackPane avatarStack = new StackPane(avatar, avatarLabel);

            VBox info = new VBox(5);
            HBox.setHgrow(info, Priority.ALWAYS);

            Label nameLabel = new Label(group.getName() != null ? group.getName() : "Group " + group.getId());
            nameLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
            nameLabel.setTextFill(Color.web("#ffffff"));

            List<Users> participants = chatService.listParticipants(group.getId());
            Label memberLabel = new Label(participants.size() + " members");
            memberLabel.setFont(Font.font(12));
            memberLabel.setTextFill(Color.web("#aaaaaa"));

            info.getChildren().addAll(nameLabel, memberLabel);
            cell.getChildren().addAll(avatarStack, info);

            setGraphic(cell);
            setStyle("-fx-background-color: transparent; -fx-padding: 5;");
            
            cell.setOnMouseEntered(e -> 
                cell.setStyle(cell.getStyle() + "-fx-background-color: #262b40;")
            );
            cell.setOnMouseExited(e -> 
                cell.setStyle(cell.getStyle().replace("-fx-background-color: #262b40;", ""))
            );
        }
    }

    // ===== DIALOGS =====
    private void showAddFriendDialog(PeerInfo peer) {
        Users user = chatService.getUserById(peer.getUserId());
        if (user == null) {
            showAlert("Error", "User not found");
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Add Friend");
        alert.setHeaderText("Send friend request to " + user.getDisplayName() + "?");
        alert.setContentText("They will need to accept your request before you can chat.");
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    chatService.sendFriendRequest(currentUserId, peer.getUserId());
                    showSuccess("Friend request sent!");
                } catch (Exception e) {
                    showAlert("Error", "Failed to send friend request: " + e.getMessage());
                }
            }
        });
    }

    private void showFriendRequestsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Friend Requests");
        dialog.setHeaderText("Pending friend requests");
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        content.setPrefWidth(400);
        
        List<FriendRequest> requests = chatService.listFriendRequest(currentUserId);
        
        if (requests.isEmpty()) {
            Label noRequests = new Label("No pending friend requests");
            noRequests.setTextFill(Color.web("#aaaaaa"));
            content.getChildren().add(noRequests);
        } else {
            for (FriendRequest request : requests) {
                HBox requestBox = new HBox(15);
                requestBox.setAlignment(Pos.CENTER_LEFT);
                requestBox.setPadding(new Insets(10));
                requestBox.setStyle("""
                    -fx-background-color: #262b40;
                    -fx-background-radius: 10;
                """);
                
                Label nameLabel = new Label(request.getFromUser().getDisplayName());
                nameLabel.setTextFill(Color.WHITE);
                nameLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
                HBox.setHgrow(nameLabel, Priority.ALWAYS);
                
                Button acceptBtn = new Button("Accept");
                acceptBtn.setStyle("""
                    -fx-background-color: #4ade80;
                    -fx-text-fill: white;
                    -fx-background-radius: 8;
                    -fx-padding: 5 15;
                    -fx-cursor: hand;
                """);
                acceptBtn.setOnAction(e -> {
                    chatService.acceptFriendRequest(request.getId());
                    dialog.close();
                    loadAllData();
                    showSuccess("Friend request accepted!");
                });
                
                Button rejectBtn = new Button("Reject");
                rejectBtn.setStyle("""
                    -fx-background-color: #ef4444;
                    -fx-text-fill: white;
                    -fx-background-radius: 8;
                    -fx-padding: 5 15;
                    -fx-cursor: hand;
                """);
                rejectBtn.setOnAction(e -> {
                    chatService.denyFriendRequest(request.getId());
                    dialog.close();
                    loadAllData();
                });
                
                requestBox.getChildren().addAll(nameLabel, acceptBtn, rejectBtn);
                content.getChildren().add(requestBox);
            }
        }
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void showCreateGroupDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Create Group");
        dialog.setHeaderText("Create a new group chat");
        
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(400);
       
        
        TextField groupNameField = new TextField();
        groupNameField.setPromptText("Group name");
        groupNameField.setPrefHeight(40);
        
        Label selectLabel = new Label("Select members (at least 2 friends, 3 people total):");
        selectLabel.setTextFill(Color.WHITE);
        
        ListView<Users> friendSelectList = new ListView<>();
        friendSelectList.setPrefHeight(200);
        friendSelectList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        friendSelectList.getItems().setAll(chatService.listFriends(currentUserId));
        friendSelectList.setCellFactory(lv -> new ListCell<Users>() {
            @Override
            protected void updateItem(Users user, boolean empty) {
                super.updateItem(user, empty);

                if (empty || user == null) {
                    setText(null);
                    setStyle("-fx-background-color: #000000;");
                    return;
                }

                setText(user.getDisplayName());
                setTextFill(Color.WHITE);

                setStyle("-fx-background-color: #000000; -fx-padding: 8 12;");

                // Hover
                setOnMouseEntered(e ->
                    setStyle("-fx-background-color: #1f2937; -fx-padding: 8 12;")
                );

                setOnMouseExited(e ->
                    setStyle(isSelected()
                        ? "-fx-background-color: #374151; -fx-padding: 8 12;"
                        : "-fx-background-color: #000000; -fx-padding: 8 12;"
                    )
                );

                // Selected
                selectedProperty().addListener((obs, oldVal, selected) -> {
                    if (selected) {
                        setStyle("-fx-background-color: #374151; -fx-padding: 8 12;");
                    } else {
                        setStyle("-fx-background-color: #000000; -fx-padding: 8 12;");
                    }
                });
            }
        });

        
        content.getChildren().addAll(groupNameField, selectLabel, friendSelectList);
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        dialog.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                String groupName = groupNameField.getText().trim();
                List<Users> selectedMembers = new ArrayList<>(friendSelectList.getSelectionModel().getSelectedItems());
                
                if (groupName.isEmpty()) {
                    showAlert("Error", "Please enter a group name");
                    return;
                }
                
                if (selectedMembers.size() < 2) {
                    showAlert("Error", "Please select at least 2 friends (3 people total including you)");
                    return;
                }
                
                // Create group (you'll need to implement this in ChatService)
                String[] usernames = selectedMembers.stream()
                    .map(Users::getUsername)
                    .toArray(String[]::new);
                
                chatController.createGroupConversation(groupName, usernames, result -> {
                    Platform.runLater(() -> {
                        if (result.success) {
                            loadAllData();
                            showSuccess("Group '" + groupName + "' created successfully!");
                            dialog.close();
                        } else {
                            showAlert("Error", result.message);
                        }
                    });
                });
            }
        });
    }
    
    private void loadAllData() {
        Platform.runLater(() -> {
            refreshOnlineUsers();
            refreshFriends();
            refreshGroups();
        });
    }

    private void showSuccess(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Success");
        alert.setContentText(message);
        alert.show();
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
        search.setPromptText("🔍 Search...");
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
        
        return search;
    }
    
    private void filterOnlineUsers(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            refreshOnlineUsers();
            return;
        }
        
        List<PeerInfo> allPeers = clientManager != null ? clientManager.getOnlinePeers() : new ArrayList<>();
        allPeers = allPeers.stream()
            .filter(p -> !p.getUserId().equals(currentUserId))
            .filter(p -> {
                Users user = chatService.getUserById(p.getUserId());
                if (user == null) return false;
                return user.getDisplayName().toLowerCase().contains(keyword.toLowerCase()) ||
                       user.getUsername().toLowerCase().contains(keyword.toLowerCase()) ||
                       p.getIp().contains(keyword);
            })
            .toList();
        
        onlineListView.getItems().setAll(allPeers);
    }
    
    private void filterGroups(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            refreshGroups();
            return;
        }
        
        List<Conversation> allGroups = chatService.listConversationsByUser(currentUserId).stream()
            .filter(c -> c.getType() == Conversation.ConversationType.group)
            .filter(c -> {
                String name = c.getName() != null ? c.getName() : "Group " + c.getId();
                return name.toLowerCase().contains(keyword.toLowerCase());
            })
            .toList();
        
        groupListView.getItems().setAll(allGroups);
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
        videoBtn.setOnAction(e -> handleVoiceCall());
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
    
    private void openGroupConversation(Conversation group) {
        currentConversation = group;
        currentChatUser = null;
        chatTitleLabel.setText(group.getName() != null ? group.getName() : "Group " + group.getId());
        
        List<Users> participants = chatService.listParticipants(group.getId());
        chatStatusLabel.setText(participants.size() + " members");
        chatStatusLabel.setTextFill(Color.web("#aaaaaa"));
        
        loadMessages();
    }
    
    private void loadMessages() {
        messageArea.getChildren().clear();
        if (currentConversation != null) {
            List<Message> messages = chatService.listMessages(currentConversation.getId());
            for (Message msg : messages) {
                boolean isOwn = msg.getSender().getId().equals(currentUserId);
                displayMessage(msg, isOwn);
                
                if (!isOwn && msg.getStatus() != Message.MessageStatus.DELIVERED) {
                    chatController.markMessageAsSeen(msg.getId());
                }
            }
        }
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
    
    
 // ===== NEW: Create file message box =====
    private VBox createFileMessageBox(Message msg, boolean isOwn) {
        VBox fileBox = new VBox(8);
        fileBox.setStyle("""
            -fx-background-color: rgba(0, 0, 0, 0.2);
            -fx-background-radius: 10;
            -fx-padding: 12;
        """);
        
        String fileUrl = msg.getImageUrl(); // chỉ dùng để parse name/size
        		
        // Parse file info from URL
        String fileName = extractFileName(fileUrl);
        String fileSize = extractFileSize(fileUrl);
        
        HBox fileHeader = new HBox(10);
        fileHeader.setAlignment(Pos.CENTER_LEFT);
        
        // File icon
        FontIcon fileIcon = new FontIcon("fas-file");
        fileIcon.setIconSize(30);
        fileIcon.setIconColor(Color.web("#4ade80"));
        
        VBox fileInfo = new VBox(3);
        Label nameLabel = new Label(fileName);
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        nameLabel.setTextFill(Color.WHITE);
        nameLabel.setMaxWidth(400);
        nameLabel.setStyle("-fx-text-overflow: ellipsis;");
        
        Label sizeLabel = new Label(fileSize);
        sizeLabel.setFont(Font.font(12));
        sizeLabel.setTextFill(Color.web("#aaaaaa"));
        
        fileInfo.getChildren().addAll(nameLabel, sizeLabel);
        
        fileHeader.getChildren().addAll(fileIcon, fileInfo);
        
        // Download button
        Button downloadBtn = new Button("Open File");
        downloadBtn.setStyle("""
            -fx-background-color: #4ade80;
            -fx-text-fill: white;
            -fx-background-radius: 8;
            -fx-padding: 8 16;
            -fx-cursor: hand;
            -fx-font-weight: bold;
        """);
        
    
        
        downloadBtn.setOnAction(e -> openFile(msg));
        
        fileBox.getChildren().addAll(fileHeader, downloadBtn);
        
        return fileBox;
    }

    // ===== HELPER: Extract file name from URL =====
    private String extractFileName(String fileUrl) {
        try {
            // Format: file://filename|size
            String[] parts = fileUrl.replace("file://", "").split("\\|");
            return parts[0];
        } catch (Exception e) {
            return "Unknown File";
        }
    }

    // ===== HELPER: Extract file size from URL =====
    private String extractFileSize(String fileUrl) {
        try {
            String[] parts = fileUrl.replace("file://", "").split("\\|");
            if (parts.length > 1) {
                return parts[1];
            }
        } catch (Exception e) {}
        return "";
    }

    // ===== HELPER: Open file =====
//    private void openFile(String fileUrl) {
//        try {
//        	 Message msg = chatService.findMessageByFileUrl(
//        	            currentConversation.getId(), fileUrl
//        	        );
//        	 if (msg == null || msg.getFileAttachment() == null) {
//                 showAlert("Error", "File metadata not found");
//                 return;
//             }
//
//             File file = new File(msg.getFileAttachment().getFilePath());
//
//             if (!file.exists()) {
//                 showAlert("Error", "File missing on disk");
//                 return;
//             }
//
//             Desktop.getDesktop().open(file);
//
//            
//            // ✅ Strategy 1: Tìm file từ FileAttachment trong DB (nếu có message context)
//            // Lấy message từ fileUrl hoặc từ current conversation
////            if (currentConversation != null) {
////                List<model.Message> messages = chatService.listMessages(currentConversation.getId());
////                for (model.Message msg : messages) {
////                    if (msg.getImageUrl() != null && msg.getImageUrl().equals(fileUrl)) {
////                        // Tìm FileAttachment từ messageId
////                        dao.FileAttachmentDao fileDao = new dao.FileAttachmentDao();
////                        model.FileAttachment attachment = fileDao.findByMessageId(msg.getId());
////                        if (attachment != null && attachment.getFilePath() != null) {
////                            file = new File(attachment.getFilePath());
////                            System.out.println("✅ [ChatWindow] Found file from FileAttachment: " + file.getAbsolutePath());
////                            break;
////                        }
////                    }
////                }
////            }
//            
//            // ✅ Strategy 2: Tìm file trong downloads directory với tên gốc hoặc có timestamp
////            if (file == null || !file.exists()) {
////                File downloadsDir = new File("file_storage/downloads");
////                
////                // Thử tìm với tên gốc trước
////                file = new File(downloadsDir, fileName);
////                
////                // Nếu không tìm thấy, tìm file có chứa tên này (có thể có timestamp)
////                if (!file.exists()) {
////                	String baseNameTmp = fileName;
////                	String extensionTmp = "";
////
////                	int lastDot = baseNameTmp.lastIndexOf('.');
////                	if (lastDot > 0) {
////                	    extensionTmp = baseNameTmp.substring(lastDot);
////                	    baseNameTmp = baseNameTmp.substring(0, lastDot);
////                	}
////
////                	// ✅ biến final dùng cho lambda
////                	final String baseName = baseNameTmp;
////                	final String extension = extensionTmp;
////
////                	File[] files = downloadsDir.listFiles((dir, name) ->
////                	    name.equals(fileName) ||
////                	    (name.startsWith(baseName + "_") && name.endsWith(extension)) ||
////                	    name.contains(baseName)
////                	);
////                    
////                    if (files != null && files.length > 0) {
////                        // Lấy file mới nhất (có thể có timestamp)
////                        file = files[0];
////                        for (File f : files) {
////                            if (f.lastModified() > file.lastModified()) {
////                                file = f;
////                            }
////                        }
////                        System.out.println("✅ [ChatWindow] Found file by name pattern: " + file.getName());
////                    }
////                } else {
////                    System.out.println("✅ [ChatWindow] Found file with original name: " + fileName);
////                }
////            }
//            
////            // ✅ Strategy 3: Tìm trong uploads directory (cho sender)
////            if (file == null || !file.exists()) {
////                File uploadsDir = new File("file_storage/uploads");
////                if (uploadsDir.exists()) {
////                    File[] files = uploadsDir.listFiles((dir, name) -> 
////                        name.equals(fileName) || name.contains(fileName)
////                    );
////                    
////                    if (files != null && files.length > 0) {
////                        file = files[0];
////                        for (File f : files) {
////                            if (f.lastModified() > file.lastModified()) {
////                                file = f;
////                            }
////                        }
////                        System.out.println("✅ [ChatWindow] Found file in uploads: " + file.getName());
////                    }
////                }
////            }
//            
//            // ✅ Open file
//            if (file != null && file.exists()) {
//                if (java.awt.Desktop.isDesktopSupported()) {
//                    java.awt.Desktop.getDesktop().open(file);
//                    System.out.println("✅ [ChatWindow] Opened file: " + file.getAbsolutePath());
//                } else {
//                    showAlert("Error", "Cannot open file on this system");
//                }
//            } else {
//            	String errorMsg = "File not found: " + msg.getFileAttachment().getFileName();
//
//                showAlert("Error", errorMsg);
//                System.err.println("❌ [ChatWindow] " + errorMsg);
//            }
//        } catch (Exception e) {
//            showAlert("Error", "Failed to open file: " + e.getMessage());
//            System.err.println("❌ [ChatWindow] Error opening file: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
    
//	private void openFile(String fileUrl) {
//	    try {
//	        // 1. Lấy message (từ imageUrl hoặc fileUrl)
//	        Message msg = chatService.findMessageByFileUrl(
//	                currentConversation.getId(), fileUrl
//	        );
//	
//	        if (msg == null) {
//	            showAlert("Error", "Message not found");
//	            return;
//	        }
//	
//	        // 2. Lấy FileAttachment theo messageId
//	        FileAttachmentDao fileDao = new FileAttachmentDao();
//	        FileAttachment attachment = fileDao.findByMessageId(msg.getId());
//	
//	        if (attachment == null) {
//	        	System.err.println("❌ No FileAttachment for messageId=" + msg.getId());
//	            showAlert("Error", "File metadata not found");
//	            return;
//	        }
//	
//	        // 3. Validate status
//	        if (attachment.getStatus() != FileAttachment.FileStatus.COMPLETED) {
//	            showAlert("Error", "File is not ready yet");
//	            return;
//	        }
//	
//	        // 4. Open file
//	        File file = new File(attachment.getFilePath());
//	        if (!file.exists()) {
//	            showAlert("Error", "File missing on disk");
//	            return;
//	        }
//	
//	        Desktop.getDesktop().open(file);
//	        System.out.println("✅ Opened file: " + file.getAbsolutePath());
//	
//	    } catch (Exception e) {
//	        showAlert("Error", "Failed to open file: " + e.getMessage());
//	        e.printStackTrace();
//	    }
//	}
    
//    private void openFile(Integer messageId) {
//        try {
//            FileAttachmentDao fileDao = new FileAttachmentDao();
//
//            FileAttachment attachment =
//                fileDao.findByMessageId(messageId);
//
//            if (attachment == null) {
//                System.err.println("❌ No FileAttachment for messageId=" + messageId);
//                showAlert("Error", "File metadata not found");
//                return;
//            }
//
//            if (attachment.getStatus() != FileAttachment.FileStatus.COMPLETED) {
//                showAlert("Error", "File is not ready yet");
//                return;
//            }
//
//            File file = new File(attachment.getFilePath());
//            if (!file.exists()) {
//                showAlert("Error", "File missing on disk");
//                return;
//            }
//
//            Desktop.getDesktop().open(file);
//
//        } catch (Exception e) {
//            showAlert("Error", "Failed to open file: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
    
    private void openFile(Message msg) {
        try {
            if (msg == null) {
                showAlert("Error", "Message not found");
                return;
            }

            // ✅ QUERY ĐÚNG DUY NHẤT
            FileAttachmentDao fileDao = new FileAttachmentDao();
            FileAttachment fa = fileDao.findByMessageId(msg.getId());

            if (fa == null) {
                System.err.println("❌ No FileAttachment for messageId=" + msg.getId());
                showAlert("Error", "File metadata not found");
                return;
            }

            // ✅ CHECK STATUS
            if (fa.getStatus() != FileAttachment.FileStatus.COMPLETED) {
                showAlert("Error", "File is not ready yet");
                return;
            }

            // ✅ CHECK FILE THẬT
            File file = new File(fa.getFilePath());
            if (!file.exists()) {
                showAlert("Error", "File missing on disk");
                return;
            }

            Desktop.getDesktop().open(file);
            System.out.println("✅ Opened file: " + file.getAbsolutePath());

        } catch (Exception e) {
            showAlert("Error", "Failed to open file: " + e.getMessage());
            e.printStackTrace();
        }
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

    // Check if message has file attachment
    if (msg.getImageUrl() != null && msg.getImageUrl().startsWith("file://")) {
        // This is a file message
        VBox fileBox = createFileMessageBox(msg, isOwn);
        bubble.getChildren().add(fileBox);
    } else {
        // Regular text message
        Label contentLabel = new Label(msg.getContent());
        contentLabel.setWrapText(true);
        contentLabel.setFont(Font.font(15));
        contentLabel.setTextFill(Color.web("#ffffff"));
        bubble.getChildren().add(contentLabel);
    }

    // Timestamp
    Label timeLabel = new Label(msg.getCreatedAt().format(TIME_FORMATTER));
    timeLabel.setFont(Font.font(11));
    timeLabel.setTextFill(isOwn ? Color.web("#e0e0e0") : Color.web("#6b7280"));

    bubble.getChildren().add(timeLabel);
    messageBox.getChildren().add(bubble);
    
    messageArea.getChildren().add(messageBox);
    
    // Auto scroll
    Platform.runLater(() -> 
        messageScrollPane.setVvalue(messageScrollPane.getVmax())
    );
}

    // ===== SHUTDOWN HELPERS =====
    
    private void closeAllDialogs() {
        try {
            // Close all file progress dialogs
            if (fileProgressDialogs != null) {
                for (ProgressDialog dialog : new ArrayList<>(fileProgressDialogs.values())) {
                    try {
                        dialog.close();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                fileProgressDialogs.clear();
            }
            
            // Close all audio call dialogs
            if (audioCallDialogs != null) {
                for (AudioCallDialog dialog : new ArrayList<>(audioCallDialogs.values())) {
                    try {
                        dialog.close();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                audioCallDialogs.clear();
            }
        } catch (Exception e) {
            System.err.println("⚠️ Error closing dialogs: " + e.getMessage());
        }
    }
    
    private void shutdownControllers() {
        try {
            // Shutdown with timeout to avoid hanging
            Thread shutdownThread = new Thread(() -> {
                try {
                    if (chatController != null) {
                        chatController.shutdown();
                    }
                    
                    if (clientManager != null) {
                        clientManager.shutdown();
                    }
                } catch (Exception e) {
                    // Ignore errors during shutdown
                    if (!isShuttingDown) {
                        System.err.println("⚠️ Error shutting down controllers: " + e.getMessage());
                    }
                }
            }, "ControllerShutdown");
            
            shutdownThread.setDaemon(true); // Don't prevent JVM exit
            shutdownThread.start();
            shutdownThread.join(500); // Wait max 500ms (reduced from 1000ms)
            
            if (shutdownThread.isAlive()) {
                System.err.println("⚠️ Shutdown timeout, forcing exit");
                shutdownThread.interrupt(); // Try to interrupt
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Ignore - we're shutting down anyway
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
        // If already chatting with this user, don't reload
        if (currentChatUser != null && currentChatUser.getId().equals(friend.getId()) && currentConversation != null) {
            return;
        }

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
                
                // ✅ Auto mark as seen if not own message
                if (!isOwn && msg.getStatus() != Message.MessageStatus.DELIVERED) {
                    chatController.markMessageAsSeen(msg.getId());
                }
            }
        }
    }

    private void sendMessage() {
        String content = messageInput.getText().trim();
        if (content.isEmpty() || currentConversation == null) return;

        // Clear input immediately after validation
        messageInput.clear();

        try {
            chatController.sendMessage(currentConversation.getId(), content, null);
            
            // Display immediately
            Message tempMsg = new Message();
            tempMsg.setContent(content);
            tempMsg.setSender(chatService.getUserById(currentUserId));
            tempMsg.setCreatedAt(java.time.LocalDateTime.now());
            displayMessage(tempMsg, true);
        } catch (Exception e) {
            // If sending failed, show error and restore text
            showAlert("Error", "Failed to send message: " + e.getMessage());
            messageInput.setText(content); // Restore text if failed
        }
    }

    private void startStatusRefreshTimer() {
        statusRefreshTimeline = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(3), e -> {
                // ✅ Skip if shutting down
                if (isShuttingDown) {
                    return;
                }
                
                Platform.runLater(() -> {
                    // ✅ Skip if shutting down
                    if (isShuttingDown) {
                        return;
                    }
                    
                    // ✅ Realtime update: Refresh all lists
                    refreshOnlineUsers();
                    refreshFriends();
                    refreshGroups();
                    
                    // Update current chat status if open
                    if (currentChatUser != null) {
                        boolean isOnline = isUserOnline(currentChatUser.getId());
                        chatStatusLabel.setText(isOnline ? "Online" : "Offline");
                        chatStatusLabel.setTextFill(isOnline ? Color.web("#4ade80") : Color.web("#6b7280"));
                    }
                });
            })
        );
        statusRefreshTimeline.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        statusRefreshTimeline.play();
    }
    
    // ✅ Realtime refresh methods
    private void refreshOnlineUsers() {
        if (isShuttingDown) return;
        
        if (clientManager != null && onlineListView != null) {
            try {
                List<PeerInfo> onlinePeers = clientManager.getOnlinePeers();
                // Filter out current user
                onlinePeers = onlinePeers.stream()
                    .filter(p -> !p.getUserId().equals(currentUserId))
                    .toList();
                onlineListView.getItems().setAll(onlinePeers);
            } catch (Exception e) {
                // Ignore errors during shutdown
                if (!isShuttingDown) {
                    System.err.println("⚠️ Error refreshing online users: " + e.getMessage());
                }
            }
        }
    }
    
    private void refreshFriends() {
        if (isShuttingDown) return;
        
        if (friendListView != null) {
            try {
                List<Users> friends = chatService.listFriends(currentUserId);
                friendListView.getItems().setAll(friends);
                friendListView.refresh(); // Force cell update for status indicators
            } catch (Exception e) {
                // Ignore errors during shutdown
                if (!isShuttingDown) {
                    System.err.println("⚠️ Error refreshing friends: " + e.getMessage());
                }
            }
        }
    }
    
    private void refreshGroups() {
        if (isShuttingDown) return;
        
        if (groupListView != null) {
            try {
                List<Conversation> groups = chatService.listConversationsByUser(currentUserId).stream()
                    .filter(c -> c.getType() == Conversation.ConversationType.group)
                    .toList();
                groupListView.getItems().setAll(groups);
            } catch (Exception e) {
                // Ignore errors during shutdown
                if (!isShuttingDown) {
                    System.err.println("⚠️ Error refreshing groups: " + e.getMessage());
                }
            }
        }
    }

    // ===== P2P LISTENERS =====
    private void setupP2PListeners() {
    	 p2pManager.setEventListener(new P2PManager.P2PEventListener() {
    	        @Override
    	        public void onChatMessageReceived(Integer conversationId, Message message) {
    	            Platform.runLater(() -> {
                        // ✅ Hiển thị message nếu conversation đang mở
                        if (currentConversation != null && 
                            currentConversation.getId().equals(conversationId)) {
                            displayMessage(message, false);
                            
                            // ✅ Nếu là file message, reload để đảm bảo hiển thị đúng format
                            if (message.getMessageType() == Message.MessageType.FILE) {
                                // Delay nhỏ để đảm bảo message đã được lưu vào DB
                                new Thread(() -> {
                                    try {
                                        Thread.sleep(100); // Đợi 100ms để DB commit
                                        Platform.runLater(() -> {
                                            if (currentConversation != null && 
                                                currentConversation.getId().equals(conversationId)) {
                                                reloadCurrentConversationMessages();
                                            }
                                        });
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }).start();
                            }
                        }
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
    	        public void onFileProgress(String fileId, int progress, boolean isUpload) {
    	        	 Platform.runLater(() -> {
    	        	        if (!fileProgressDialogs.containsKey(fileId)) {
    	        	            showFileProgressDialog(fileId, "Sending file...", isUpload);
    	        	        }
    	        	        updateFileProgress(fileId, progress);
    	        	    });
    	        }

    	        @Override
    	        public void onFileComplete(String fileId, File file, boolean isUpload) {
    	            Platform.runLater(() -> {
    	                closeFileProgressDialog(fileId);

    	             // ================== SENDER ==================
    	                if (isUpload) {
    	                	File originalFile = pendingUploadFiles.remove(fileId);

    	                    if (originalFile == null) {
    	                        System.err.println("❌ SENDER: originalFile not found for fileId=" + fileId);
    	                        return;
    	                    }
    	                    
//    	                    Message msg = chatService.createFileMessage(
//    	                            currentConversation.getId(),
//    	                            currentUserId,
//    	                            originalFile.getName(),
//    	                            "file://" + originalFile.getName() + "|" +
//    	                            formatFileSize(originalFile.length())
//    	                        );
//
//    	                    try {
//								chatService.createFileAttachment(
//								    msg,
//								    currentUserId,
//								    file,
//								    fileId
//								);
//							} catch (IOException e) {
//								// TODO Auto-generated catch block
//								e.printStackTrace();
//							}

    	                 // UI chỉ nhận callback
    	                    //displayFileMessage(msg);
    	                    
    	                    showSuccessNotification("File sent successfully!");
    	                    reloadCurrentConversationMessages();
    	                    return;
    	                }

    	                //phía receiver 
    	                if (file == null) {
    	                    showAlert("Error", "File received but file object is null");
    	                    System.err.println("❌ onFileComplete: file is null, fileId=" + fileId);
    	                    return;
    	                }

    	                if (!file.exists()) {
    	                    showAlert("Error", "Received file not found on disk");
    	                    return;
    	                }
    	                
                        // ✅ RECEIVER: File đã được nhận và message đã được tạo trong FileTransferController
                        showSuccessNotification("File received: " + (file != null ? file.getName() : "file"));
                        System.out.println("✅ [ChatWindow] RECEIVER: File received notification");
                        
                        // ✅ Reload messages để đảm bảo message hiển thị (ngay cả khi conversation đang mở)
                        if (currentConversation != null) {
                            // Delay nhỏ để đảm bảo DB đã commit
                            new Thread(() -> {
                                try {
                                    Thread.sleep(200); // Đợi 200ms để DB commit
                                    Platform.runLater(() -> {
                                        if (currentConversation != null) {
                                            reloadCurrentConversationMessages();
                                            System.out.println("✅ [ChatWindow] RECEIVER: Reloaded messages after file receive");
                                        }
                                    });
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }).start();
                        } else {
                            System.out.println("✅ [ChatWindow] RECEIVER: File received but conversation not open. Message saved to DB.");
                        }
    	            });
    	        }


    	        @Override
    	        public void onFileCanceled(String fileId, boolean isUpload) {
    	            Platform.runLater(() -> {
    	                closeFileProgressDialog(fileId);
    	                showAlert("Canceled", "File transfer was canceled");
    	            });
    	        }

    	        @Override
    	        public void onFileError(String fileId, String error) {
    	            Platform.runLater(() -> {
    	                closeFileProgressDialog(fileId);
    	                showAlert("Error", "File transfer failed: " + error);
    	            });
    	        }

    	     // ===== HELPER: Create styled alert =====
    	        private void reloadCurrentConversationMessages() {
    	            if (currentConversation == null) return;
    	            
    	            // Clear and reload
    	            messageArea.getChildren().clear();
    	            List<Message> messages = chatService.listMessages(currentConversation.getId());
    	            
    	            for (Message msg : messages) {
    	                boolean isOwn = msg.getSender().getId().equals(currentUserId);
    	                displayMessage(msg, isOwn);
    	                
    	                // Auto mark as seen if not own message
    	                if (!isOwn && msg.getStatus() != Message.MessageStatus.DELIVERED) {
    	                    chatController.markMessageAsSeen(msg.getId());
    	                }
    	            }
    	            
    	            System.out.println("✅ Reloaded " + messages.size() + " messages");
    	        }
    	        
    	        private Alert createStyledAlert(Alert.AlertType type) {
    	            Alert alert = new Alert(type);
    	            
    	            DialogPane dialogPane = alert.getDialogPane();
    	            dialogPane.setStyle("""
    	                -fx-background-color: #1a1d2e;
    	                -fx-border-color: #667eea;
    	                -fx-border-width: 1;
    	                -fx-border-radius: 10;
    	            """);
    	            
    	            // Style header
    	            dialogPane.lookup(".header-panel").setStyle("""
    	                -fx-background-color: #262b40;
    	            """);
    	            
    	            // Style content
    	            dialogPane.lookup(".content").setStyle("""
    	                -fx-background-color: #1a1d2e;
    	            """);
    	            
    	            // Style labels
    	            for (javafx.scene.Node node : dialogPane.getChildren()) {
    	                if (node instanceof Label) {
    	                    ((Label) node).setTextFill(Color.web("#ffffff"));
    	                }
    	            }
    	            
    	            return alert;
    	        }

    	        // ===== HELPER: Show success notification =====
    	        private void showSuccessNotification(String message) {
    	            // Create temporary notification
    	            Label notification = new Label("✅ " + message);
    	            notification.setStyle("""
    	                -fx-background-color: rgba(74, 222, 128, 0.9);
    	                -fx-text-fill: white;
    	                -fx-padding: 12 20;
    	                -fx-background-radius: 10;
    	                -fx-font-size: 14;
    	                -fx-font-weight: bold;
    	            """);
    	            
    	            StackPane notificationPane = new StackPane(notification);
    	            notificationPane.setStyle("-fx-background-color: transparent;");
    	            StackPane.setAlignment(notification, Pos.TOP_CENTER);
    	            StackPane.setMargin(notification, new Insets(20, 0, 0, 0));
    	            
    	            // Add to stage
    	            if (stage.getScene().getRoot() instanceof StackPane) {
    	                StackPane root = (StackPane) stage.getScene().getRoot();
    	                root.getChildren().add(notificationPane);
    	                
    	                // Fade out after 3 seconds
    	                FadeTransition fade = new FadeTransition(Duration.seconds(3), notificationPane);
    	                fade.setFromValue(1.0);
    	                fade.setToValue(0.0);
    	                fade.setOnFinished(e -> root.getChildren().remove(notificationPane));
    	                fade.play();
    	            }
    	        }
    	        
    	        // ===== AUDIO CALL EVENTS =====
    	        
//    	        @Override
//    	        public void onAudioCallRequested(Integer fromUser, String callId) {
//    	            Platform.runLater(() -> {
//    	                Users caller = chatService.getUserById(fromUser);
//    	                String callerName = caller != null ? caller.getDisplayName() : "User" + fromUser;
//    	                
//    	                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
//    	                alert.setTitle("Incoming Call");
//    	                alert.setHeaderText(callerName + " is calling you");
//    	                alert.setContentText("Do you want to answer?");
//    	                
//    	                alert.showAndWait().ifPresent(response -> {
//    	                    if (response == ButtonType.OK) {
//    	                        p2pManager.acceptAudioCall(callId);
//    	                        showAudioCallDialog(callId, "Connected", true);
//    	                    } else {
//    	                        p2pManager.rejectAudioCall(callId, "User declined");
//    	                    }
//    	                });
//    	            });
//    	        }

//    	        @Override
//    	        public void onAudioCallAccepted(Integer fromUser, String callId) {
//    	            updateAudioCallStatus(callId, "Connected");
//    	        }
//
//    	        @Override
//    	        public void onAudioCallRejected(Integer fromUser, String callId, String reason) {
//    	            Platform.runLater(() -> {
//    	                closeAudioCallDialog(callId);
//    	                showAlert("Call Rejected", "The recipient declined the call");
//    	            });
//    	        }
//
//    	        @Override
//    	        public void onAudioCallStarted(String callId) {
//    	            updateAudioCallStatus(callId, "Active");
//    	        }
//
//    	        @Override
//    	        public void onAudioCallEnded(String callId) {
//    	            Platform.runLater(() -> {
//    	                closeAudioCallDialog(callId);
//    	                showAlert("Call Ended", "The call has ended");
//    	            });
//    	        }
//
//    	        @Override
//    	        public void onAudioCallError(String callId, String error) {
//    	            Platform.runLater(() -> {
//    	                closeAudioCallDialog(callId);
//    	                showAlert("Call Error", error);
//    	            });
//    	        }

    	        @Override
    	        public void onConnectionLost(Integer userId) {
    	            System.out.println("Connection lost with user: " + userId);
    	        }
    	    });
    	};
    

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
    if (currentConversation == null) {
        showAlert("Error", "Please select a conversation first");
        return;
    }

    // File chooser
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Select File to Send");
    
    // Add filters
    fileChooser.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter("All Files", "*.*"),
        new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"),
        new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt"),
        new FileChooser.ExtensionFilter("Archives", "*.zip", "*.rar", "*.7z")
    );

    File selectedFile = fileChooser.showOpenDialog(stage);
    if (selectedFile != null) {
        sendFileToConversation(selectedFile);
    }
}

   private void sendFileToConversation(File file) {
	    if (currentConversation == null) return;
	    
	    // Get participants
	    List<Users> participants = chatService.listParticipants(currentConversation.getId());
	    
	    // Find peer
	    Integer targetUserId = participants.stream()
	        .map(Users::getId)
	        .filter(id -> !id.equals(currentUserId))
	        .findFirst()
	        .orElse(null);
	    
	    if (targetUserId == null) {
	        showAlert("Error", "Cannot find recipient");
	        return;
	    }

	    try {
	        // Generate client message ID for idempotent sending
	        String clientMessageId = UUID.randomUUID().toString(); 
	        String fileId = UUID.randomUUID().toString();
	        
	        String sentFileId = p2pManager.sendFile(
	            targetUserId,
	            file,
	            currentConversation.getId(),
	            clientMessageId,
	            fileId    
	        );
	        
	     // ⭐ LƯU FILE GỐC
	        pendingUploadFiles.put(fileId, file);
	        
	        // Show progress dialog
	        showFileProgressDialog(sentFileId, file.getName(), true);
	        
	    } catch (Exception e) {
	        showAlert("Error", "Failed to send file: " + e.getMessage());
	    }
	}


    // Thêm dialog hiển thị progress:
    private Map<String, ProgressDialog> fileProgressDialogs = new HashMap<>();

    private void showFileProgressDialog(String fileId, String fileName, boolean isUpload) {
        Platform.runLater(() -> {
            ProgressDialog dialog = new ProgressDialog(fileName, isUpload, fileId);
            fileProgressDialogs.put(fileId, dialog);
            dialog.show();
        });
    }
    
    private void updateFileProgress(String fileId, int progress) {
        Platform.runLater(() -> {
            ProgressDialog dialog = fileProgressDialogs.get(fileId);
            if (dialog != null) {
                dialog.updateProgress(progress);
            }
        });
    }

    private void closeFileProgressDialog(String fileId) {
        Platform.runLater(() -> {
            ProgressDialog dialog = fileProgressDialogs.remove(fileId);
            if (dialog != null) {
                dialog.close();
            }
        });
    }
    
    private void showEmojiPicker() {
        showAlert("Feature", "Emoji picker - Coming soon");
    }

    private void handleVoiceCall() {
    	 if (currentConversation == null) {
    	        showAlert("Error", "Please select a conversation first");
    	        return;
    	    }

    	    // Lấy peer user
    	    List<Users> participants = chatService.listParticipants(currentConversation.getId());
    	    Integer targetUserId = participants.stream()
    	        .map(Users::getId)
    	        .filter(id -> !id.equals(currentUserId))
    	        .findFirst()
    	        .orElse(null);
    	    
    	    if (targetUserId == null) {
    	        showAlert("Error", "Cannot find recipient");
    	        return;
    	    }

    	    try {
    	        String callId = p2pManager.startAudioCall(targetUserId);
    	        showAudioCallDialog(callId, "Calling...", false);
    	        
    	    } catch (Exception e) {
    	        showAlert("Error", "Failed to start call: " + e.getMessage());
    	    }
    	}

    	// Audio call dialog
    	private Map<String, AudioCallDialog> audioCallDialogs = new HashMap<>();

    	private void showAudioCallDialog(String callId, String status, boolean isIncoming) {
    	    Platform.runLater(() -> {
    	        AudioCallDialog dialog = new AudioCallDialog(callId, status, isIncoming);
    	        audioCallDialogs.put(callId, dialog);
    	        dialog.show();
    	    });
    	}

    	private void updateAudioCallStatus(String callId, String status) {
    	    Platform.runLater(() -> {
    	        AudioCallDialog dialog = audioCallDialogs.get(callId);
    	        if (dialog != null) {
    	            dialog.updateStatus(status);
    	        }
    	    });
    	}

    	private void closeAudioCallDialog(String callId) {
    	    Platform.runLater(() -> {
    	        AudioCallDialog dialog = audioCallDialogs.remove(callId);
    	        if (dialog != null) {
    	            dialog.close();
    	        }
    	    });
    	}

    	// Helper method
    	private String formatFileSize(Long bytes) {
    	    if (bytes < 1024) return bytes + " B";
    	    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    	    return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    	}

    	// ===== INNER CLASSES FOR DIALOGS =====

    	// ===== UPDATED: File progress dialog with better styling =====
private class ProgressDialog extends Stage {
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label speedLabel;
    private long startTime;
    private final String fileId;
    
    public ProgressDialog( String fileName, boolean isUpload, String fileId) {
    	this.fileId = fileId;
    	setTitle(isUpload ? "Sending File" : "Receiving File");
        setWidth(450);
        setHeight(200);
        setResizable(false);
        startTime = System.currentTimeMillis();
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(25));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #1a1d2e;");
        
        // Icon
        FontIcon icon = new FontIcon(isUpload ? "fas-upload" : "fas-download");
        icon.setIconSize(40);
        icon.setIconColor(Color.web("#667eea"));
        
        // File name
        Label fileLabel = new Label(fileName);
        fileLabel.setTextFill(Color.web("#eaeaea"));
        fileLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        fileLabel.setMaxWidth(400);
        fileLabel.setStyle("-fx-text-overflow: ellipsis;");
        
        // Progress bar
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setStyle("""
            -fx-accent: #667eea;
        """);
        
        // Status labels
        HBox statusBox = new HBox(10);
        statusBox.setAlignment(Pos.CENTER);
        
        statusLabel = new Label("0%");
        statusLabel.setTextFill(Color.web("#aaa"));
        statusLabel.setFont(Font.font(13));
        
        speedLabel = new Label("");
        speedLabel.setTextFill(Color.web("#888"));
        speedLabel.setFont(Font.font(12));
        
        statusBox.getChildren().addAll(statusLabel, new Label("•"), speedLabel);
        
        // Cancel button
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setStyle("""
            -fx-background-color: #ef4444;
            -fx-text-fill: white;
            -fx-background-radius: 8;
            -fx-padding: 8 20;
            -fx-cursor: hand;
        """);
        cancelBtn.setOnAction(e -> close());
        
        root.getChildren().addAll(icon, fileLabel, progressBar, statusBox, cancelBtn);
        
        Scene scene = new Scene(root);
        setScene(scene);
        
        // Center on parent
        if (stage != null) {
            initOwner(stage);
            setX(stage.getX() + (stage.getWidth() - getWidth()) / 2);
            setY(stage.getY() + (stage.getHeight() - getHeight()) / 2);
        }
    }
    
    public void updateProgress(int progress) {
        Platform.runLater(() -> {
            progressBar.setProgress(progress / 100.0);
            statusLabel.setText(progress + "%");
            
            // Calculate speed
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > 0) {
                double speed = (progress / 100.0) / (elapsed / 1000.0);
                speedLabel.setText(String.format("%.1f%% per second", speed * 100));
            }
        });
    }
}

    	// ===== UPDATED: Audio call dialog with better UI =====
private class AudioCallDialog extends Stage {
    private Label statusLabel;
    private Label timerLabel;
    private String callId;
    private long callStartTime;
    private javafx.animation.Timeline timer;
    
    public AudioCallDialog(String callId, String status, boolean isIncoming) {
        this.callId = callId;
        setTitle(isIncoming ? "Incoming Call" : "Outgoing Call");
        setWidth(350);
        setHeight(280);
        setResizable(false);
        
        VBox root = new VBox(20);
        root.setPadding(new Insets(30));
        root.setAlignment(Pos.CENTER);
        root.setStyle("""
            -fx-background-color: linear-gradient(to bottom, #1a1d2e, #0f1419);
        """);
        
        // Call icon with animation
        StackPane iconStack = new StackPane();
        
        Circle outerCircle = new Circle(50);
        outerCircle.setFill(Color.TRANSPARENT);
        outerCircle.setStroke(Color.web("#667eea", 0.3));
        outerCircle.setStrokeWidth(2);
        
        Circle innerCircle = new Circle(40);
        innerCircle.setFill(Color.web("#667eea"));
        
        FontIcon callIcon = new FontIcon("fas-phone");
        callIcon.setIconSize(30);
        callIcon.setIconColor(Color.WHITE);
        
        iconStack.getChildren().addAll(outerCircle, innerCircle, callIcon);
        
        // Pulse animation
        ScaleTransition pulse = new ScaleTransition(Duration.seconds(1), outerCircle);
        pulse.setFromX(1.0);
        pulse.setFromY(1.0);
        pulse.setToX(1.2);
        pulse.setToY(1.2);
        pulse.setCycleCount(javafx.animation.Animation.INDEFINITE);
        pulse.setAutoReverse(true);
        pulse.play();
        
        // User name (from current chat)
        Label nameLabel = new Label(currentChatUser != null ? 
            currentChatUser.getDisplayName() : "Unknown");
        nameLabel.setTextFill(Color.web("#eaeaea"));
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        
        // Status
        statusLabel = new Label(status);
        statusLabel.setTextFill(Color.web("#aaa"));
        statusLabel.setFont(Font.font(14));
        
        // Timer
        timerLabel = new Label("00:00");
        timerLabel.setTextFill(Color.web("#667eea"));
        timerLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        timerLabel.setVisible(false);
        
        // Buttons
        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER);
        
        Button endBtn = createRoundButton("fas-phone", Color.web("#ef4444"));
        endBtn.setOnAction(e -> {
            p2pManager.endAudioCall(callId);
            close();
        });
        
        Button muteBtn = createRoundButton("fas-microphone-slash", Color.web("#6b7280"));
        muteBtn.setOnAction(e -> {
            // TODO: Implement mute
            showAlert("Feature", "Mute - Coming soon");
        });
        
        buttonBox.getChildren().addAll(muteBtn, endBtn);
        
        root.getChildren().addAll(
            iconStack, nameLabel, statusLabel, timerLabel, buttonBox
        );
        
        Scene scene = new Scene(root);
        setScene(scene);
        
        // Center on parent
        if (stage != null) {
            initOwner(stage);
            setX(stage.getX() + (stage.getWidth() - getWidth()) / 2);
            setY(stage.getY() + (stage.getHeight() - getHeight()) / 2);
        }
    }
    
    private Button createRoundButton(String iconName, Color bgColor) {
        Button btn = new Button();
        btn.setGraphic(new FontIcon(iconName));
        btn.setStyle(String.format("""
            -fx-background-color: %s;
            -fx-background-radius: 50%%;
            -fx-min-width: 60;
            -fx-min-height: 60;
            -fx-cursor: hand;
        """, toHexString(bgColor)));
        
        btn.setOnMouseEntered(e -> {
            btn.setStyle(btn.getStyle() + "-fx-opacity: 0.8;");
        });
        btn.setOnMouseExited(e -> {
            btn.setStyle(btn.getStyle().replace("-fx-opacity: 0.8;", ""));
        });
        
        return btn;
    }
    
    private String toHexString(Color color) {
        return String.format("#%02X%02X%02X",
            (int)(color.getRed() * 255),
            (int)(color.getGreen() * 255),
            (int)(color.getBlue() * 255));
    }
    
    public void updateStatus(String status) {
        Platform.runLater(() -> {
            statusLabel.setText(status);
            
            if ("Active".equals(status) || "Connected".equals(status)) {
                // Start timer
                callStartTime = System.currentTimeMillis();
                timerLabel.setVisible(true);
                
                timer = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(Duration.seconds(1), e -> updateTimer())
                );
                timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
                timer.play();
            }
        });
    }
    
    private void updateTimer() {
        long elapsed = (System.currentTimeMillis() - callStartTime) / 1000;
        long minutes = elapsed / 60;
        long seconds = elapsed % 60;
        timerLabel.setText(String.format("%02d:%02d", minutes, seconds));
    }
    
    @Override
    public void close() {
        if (timer != null) {
            timer.stop();
        }
        super.close();
    }
}
}