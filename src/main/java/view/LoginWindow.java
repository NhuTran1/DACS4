package view;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;
import client.ClientManager;
import controller.LoginController;
import model.Users;
import network.p2p.P2PManager;
import service.ChatService;

public class LoginWindow {
    private final Stage stage;
    private final LoginController loginController = new LoginController();
    private ProgressIndicator loadingIndicator;

    public LoginWindow(Stage stage) {
        this.stage = stage;
    }
    
    public void show() {
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #1a1a2e;");
        
        Region animatedBg = createAnimatedBackground();
        VBox loginCard = createLoginCard();
        
        root.getChildren().addAll(animatedBg, loginCard);
        
        Scene scene = new Scene(root, 1000, 650);
        stage.setScene(scene);
        stage.setTitle("Chat App - Login");
        stage.setResizable(false);
        stage.show();
        
        playEntranceAnimation(loginCard);
    }
    
    private Region createAnimatedBackground() {
        Pane pane = new Pane();
        pane.setPrefSize(1000, 650);
        
        for (int i = 0; i < 5; i++) {
            Circle circle = createGradientCircle(i);
            pane.getChildren().add(circle);
        }
        
        return pane;
    }
    
    private Circle createGradientCircle(int index) {
        double[] sizes = {300, 250, 200, 150, 100};
        double[] x = {-50, 800, 200, 700, 400};
        double[] y = {-50, -30, 500, 600, 300};
        
        Circle circle = new Circle(sizes[index] / 2);
        circle.setLayoutX(x[index]);
        circle.setLayoutY(y[index]);
        
        Stop[] stops = new Stop[] {
            new Stop(0, Color.web("#667eea", 0.3)),
            new Stop(1, Color.web("#764ba2", 0.1))
        };
        
        circle.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE, stops));
        circle.setEffect(new GaussianBlur(50));
        
        TranslateTransition tt = new TranslateTransition(Duration.seconds(5 + index), circle);
        tt.setByY(30 + index * 10);
        tt.setAutoReverse(true);
        tt.setCycleCount(Timeline.INDEFINITE);
        tt.play();
        
        return circle;
    }
    
    private VBox createLoginCard() {
        VBox card = new VBox(25);
        card.setMaxWidth(420);
        card.setMaxHeight(550);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(50, 50, 40, 50));
        
        card.setStyle(
            "-fx-background-color: rgba(22, 33, 62, 0.85);" +
            "-fx-background-radius: 20;" +
            "-fx-border-color: rgba(102, 126, 234, 0.3);" +
            "-fx-border-width: 1;" +
            "-fx-border-radius: 20;"
        );
        
        DropShadow shadow = new DropShadow();
        shadow.setRadius(30);
        shadow.setColor(Color.web("#667eea", 0.5));
        card.setEffect(shadow);
        
        Label logo = new Label("💬");
        logo.setFont(Font.font(60));
        
        Label title = new Label("Welcome Back");
        title.setFont(Font.font("System", FontWeight.BOLD, 32));
        title.setTextFill(Color.web("#eaeaea"));
        
        Label subtitle = new Label("Login to continue your conversations");
        subtitle.setFont(Font.font(14));
        subtitle.setTextFill(Color.web("#aaaaaa"));
        
        TextField usernameField = createStyledTextField("Username");
        PasswordField passwordField = createStyledPasswordField("Password");
        
        // Loading indicator
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(30, 30);
        loadingIndicator.setVisible(false);
        
        Button loginBtn = createGradientButton("Login");
        loginBtn.setOnAction(e -> handleLogin(usernameField.getText(), passwordField.getText(), loginBtn));
        
        HBox registerBox = new HBox(5);
        registerBox.setAlignment(Pos.CENTER);
        
        Label registerLabel = new Label("Don't have an account?");
        registerLabel.setTextFill(Color.web("#aaaaaa"));
        registerLabel.setFont(Font.font(13));
        
        Hyperlink registerLink = new Hyperlink("Sign up");
        registerLink.setTextFill(Color.web("#667eea"));
        registerLink.setFont(Font.font(13));
        registerLink.setStyle("-fx-border-width: 0; -fx-padding: 0;");
        registerLink.setOnAction(e -> new RegisterWindow(stage).show());
        
        registerBox.getChildren().addAll(registerLabel, registerLink);
        
        card.getChildren().addAll(
            logo, title, subtitle,
            usernameField, passwordField,
            loadingIndicator, loginBtn, registerBox
        );
        
        return card;
    }
    
    private TextField createStyledTextField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setPrefHeight(50);
        field.setFont(Font.font(14));
        
        field.setStyle(
            "-fx-background-color: rgba(15, 52, 96, 0.5);" +
            "-fx-background-radius: 10;" +
            "-fx-text-fill: #eaeaea;" +
            "-fx-prompt-text-fill: #888888;" +
            "-fx-border-color: transparent;" +
            "-fx-border-width: 2;" +
            "-fx-border-radius: 10;" +
            "-fx-padding: 0 15;"
        );
        
        field.focusedProperty().addListener((obs, old, focused) -> {
            if (focused) {
                field.setStyle(field.getStyle() + "-fx-border-color: #667eea;");
            } else {
                field.setStyle(field.getStyle().replace("-fx-border-color: #667eea;", ""));
            }
        });
        
        return field;
    }
    
    private PasswordField createStyledPasswordField(String prompt) {
        PasswordField field = new PasswordField();
        field.setPromptText(prompt);
        field.setPrefHeight(50);
        field.setFont(Font.font(14));
        
        field.setStyle(
            "-fx-background-color: rgba(15, 52, 96, 0.5);" +
            "-fx-background-radius: 10;" +
            "-fx-text-fill: #eaeaea;" +
            "-fx-prompt-text-fill: #888888;" +
            "-fx-border-color: transparent;" +
            "-fx-border-width: 2;" +
            "-fx-border-radius: 10;" +
            "-fx-padding: 0 15;"
        );
        
        field.focusedProperty().addListener((obs, old, focused) -> {
            if (focused) {
                field.setStyle(field.getStyle() + "-fx-border-color: #667eea;");
            } else {
                field.setStyle(field.getStyle().replace("-fx-border-color: #667eea;", ""));
            }
        });
        
        return field;
    }
    
    private Button createGradientButton(String text) {
        Button btn = new Button(text);
        btn.setPrefWidth(320);
        btn.setPrefHeight(50);
        btn.setFont(Font.font("System", FontWeight.BOLD, 15));
        btn.setTextFill(Color.WHITE);
        
        btn.setStyle(
            "-fx-background-color: linear-gradient(to right, #667eea, #764ba2);" +
            "-fx-background-radius: 10;" +
            "-fx-cursor: hand;"
        );
        
        btn.setOnMouseEntered(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(100), btn);
            st.setToX(1.05);
            st.setToY(1.05);
            st.play();
        });
        
        btn.setOnMouseExited(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(100), btn);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });
        
        return btn;
    }
    
    private void playEntranceAnimation(VBox card) {
        card.setOpacity(0);
        card.setTranslateY(30);
        
        FadeTransition fade = new FadeTransition(Duration.millis(600), card);
        fade.setFromValue(0);
        fade.setToValue(1);
        
        TranslateTransition translate = new TranslateTransition(Duration.millis(600), card);
        translate.setFromY(30);
        translate.setToY(0);
        
        ParallelTransition parallel = new ParallelTransition(fade, translate);
        parallel.play();
    }
    
    private void handleLogin(String username, String password, Button loginBtn) {
        if (username.trim().isEmpty() || password.trim().isEmpty()) {
            showError("Please enter username and password");
            return;
        }
        
        // Disable button and show loading
        loginBtn.setDisable(true);
        loadingIndicator.setVisible(true);
        
        // Run in background thread
        new Thread(() -> {
            try {
                // 1. Authenticate user
                Users user = loginController.login(username, password);
                
                if (user == null) {
                    Platform.runLater(() -> {
                        loginBtn.setDisable(false);
                        loadingIndicator.setVisible(false);
                        showError("Invalid username or password");
                    });
                    return;
                }
                
                // 2. Initialize ClientManager and connect to signaling server
                ClientManager clientManager = new ClientManager(user.getId(), user.getUsername());
                
                boolean serverConnected = clientManager.start();
                
                if (!serverConnected) {
                    Platform.runLater(() -> {
                        loginBtn.setDisable(false);
                        loadingIndicator.setVisible(false);
                        showError("Cannot connect to server. Please check if the server is running.");
                    });
                    return;
                }
                
                // 3. Success - show chat window
                Platform.runLater(() -> {
                    loginBtn.setDisable(false);
                    loadingIndicator.setVisible(false);
                    
                    ChatService chatService = clientManager.getChatService();
                    P2PManager p2pManager = clientManager.getP2pManager();
                    
                    ChatWindow chatWindow = new ChatWindow(
                        stage, 
                        chatService, 
                        p2pManager, 
                        user.getId(), 
                        clientManager
                    );
                    chatWindow.show();
                });
                
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    loginBtn.setDisable(false);
                    loadingIndicator.setVisible(false);
                    showError("Login failed: " + ex.getMessage());
                });
            }
        }).start();
    }
    
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        
        // Style the alert
        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setStyle("-fx-background-color: #1a1d2e;");
        dialogPane.lookup(".content.label").setStyle("-fx-text-fill: #ffffff;");
        
        alert.showAndWait();
    }
    
    private void showSuccess(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Success");
        alert.setHeaderText(null);
        alert.setContentText(message);
        
        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setStyle("-fx-background-color: #1a1d2e;");
        dialogPane.lookup(".content.label").setStyle("-fx-text-fill: #ffffff;");
        
        alert.show();
    }
}