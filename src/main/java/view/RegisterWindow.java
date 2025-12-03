package view;

import controller.RegisterController;
import javafx.animation.*;
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
import service.AuthService;

/**
 * RegisterWindow - giao diện đăng ký tài khoản tương tự LoginWindow
 */
public class RegisterWindow {
    private final Stage stage;
    private final AuthService authService = new AuthService();
    private RegisterController registerController = new RegisterController();
    
    public RegisterWindow(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #1a1a2e;");

        Region animatedBg = createAnimatedBackground();
        VBox card = createRegisterCard();

        root.getChildren().addAll(animatedBg, card);

        Scene scene = new Scene(root, 1000, 650);
        stage.setScene(scene);
        stage.setTitle("Chat App - Register");
        stage.setResizable(false);
        stage.show();

        playEntranceAnimation(card);
    }

    private Region createAnimatedBackground() {
        Pane pane = new Pane();
        pane.setPrefSize(1000, 650);
        double[] sizes = {300, 250, 200, 150, 100};
        double[] x = {-50, 800, 200, 700, 400};
        double[] y = {-50, -30, 500, 600, 300};

        for (int i = 0; i < sizes.length; i++) {
            Circle circle = new Circle(sizes[i] / 2);
            circle.setLayoutX(x[i]);
            circle.setLayoutY(y[i]);

            Stop[] stops = new Stop[] {
                new Stop(0, Color.web("#667eea", 0.3)),
                new Stop(1, Color.web("#764ba2", 0.1))
            };
            circle.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE, stops));
            circle.setEffect(new GaussianBlur(50));

            TranslateTransition tt = new TranslateTransition(Duration.seconds(5 + i), circle);
            tt.setByY(30 + i * 10);
            tt.setAutoReverse(true);
            tt.setCycleCount(Timeline.INDEFINITE);
            tt.play();

            pane.getChildren().add(circle);
        }
        return pane;
    }

    private VBox createRegisterCard() {
        VBox card = new VBox(18);
        card.setMaxWidth(460);
        card.setMaxHeight(600);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(40, 40, 30, 40));

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

        Label title = new Label("Create Account");
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setTextFill(Color.web("#eaeaea"));

        Label subtitle = new Label("Register to start chatting with friends");
        subtitle.setFont(Font.font(13));
        subtitle.setTextFill(Color.web("#aaaaaa"));

        TextField usernameField = createStyledTextField("Username");
        TextField displayNameField = createStyledTextField("Display name");
        TextField emailField = createStyledTextField("Email");
        PasswordField passwordField = createStyledPasswordField("Password");
        PasswordField confirmField = createStyledPasswordField("Confirm Password");

        Button registerBtn = createGradientButton("Register");
        registerBtn.setOnAction(e -> handleRegister(
            usernameField.getText(),
            displayNameField.getText(),
            emailField.getText(),
            passwordField.getText(),
            confirmField.getText()
        ));

        HBox backBox = new HBox(6);
        backBox.setAlignment(Pos.CENTER_LEFT);

        Hyperlink loginLink = new Hyperlink("Back to Login");
        loginLink.setTextFill(Color.web("#667eea"));
        loginLink.setOnAction(e -> {
            new LoginWindow(stage).show();
        });

        backBox.getChildren().add(loginLink);

        card.getChildren().addAll(
            logo,
            title,
            subtitle,
            usernameField,
            displayNameField,
            emailField,
            passwordField,
            confirmField,
            registerBtn,
            backBox
        );

        return card;
    }

    private TextField createStyledTextField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setPrefHeight(46);
        field.setFont(Font.font(13));
        field.setStyle(
            "-fx-background-color: rgba(15, 52, 96, 0.5);" +
            "-fx-background-radius: 10;" +
            "-fx-text-fill: #eaeaea;" +
            "-fx-prompt-text-fill: #888888;" +
            "-fx-border-color: transparent;" +
            "-fx-border-width: 2;" +
            "-fx-border-radius: 10;" +
            "-fx-padding: 0 12;"
        );
        field.focusedProperty().addListener((obs, old, focused) -> {
            if (focused) field.setStyle(field.getStyle() + "-fx-border-color: #667eea;");
            else field.setStyle(field.getStyle().replace("-fx-border-color: #667eea;", ""));
        });
        return field;
    }

    private PasswordField createStyledPasswordField(String prompt) {
        PasswordField field = new PasswordField();
        field.setPromptText(prompt);
        field.setPrefHeight(46);
        field.setFont(Font.font(13));
        field.setStyle(
            "-fx-background-color: rgba(15, 52, 96, 0.5);" +
            "-fx-background-radius: 10;" +
            "-fx-text-fill: #eaeaea;" +
            "-fx-prompt-text-fill: #888888;" +
            "-fx-border-color: transparent;" +
            "-fx-border-width: 2;" +
            "-fx-border-radius: 10;" +
            "-fx-padding: 0 12;"
        );
        field.focusedProperty().addListener((obs, old, focused) -> {
            if (focused) field.setStyle(field.getStyle() + "-fx-border-color: #667eea;");
            else field.setStyle(field.getStyle().replace("-fx-border-color: #667eea;", ""));
        });
        return field;
    }

    private Button createGradientButton(String text) {
        Button btn = new Button(text);
        btn.setPrefWidth(380);
        btn.setPrefHeight(46);
        btn.setFont(Font.font("System", FontWeight.BOLD, 14));
        btn.setTextFill(Color.WHITE);
        btn.setStyle("-fx-background-color: linear-gradient(to right, #667eea, #764ba2);" +
                     "-fx-background-radius: 10; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(100), btn);
            st.setToX(1.03); st.setToY(1.03); st.play();
        });
        btn.setOnMouseExited(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(100), btn);
            st.setToX(1.0); st.setToY(1.0); st.play();
        });
        return btn;
    }

    private void playEntranceAnimation(VBox card) {
        card.setOpacity(0);
        card.setTranslateY(30);
        FadeTransition fade = new FadeTransition(Duration.millis(500), card);
        fade.setFromValue(0); fade.setToValue(1);
        TranslateTransition translate = new TranslateTransition(Duration.millis(500), card);
        translate.setFromY(30); translate.setToY(0);
        ParallelTransition pt = new ParallelTransition(fade, translate);
        pt.play();
    }

    private void handleRegister(String username, String displayName, String email, String password, String confirm) {
        try {
            boolean ok = registerController.register(username, displayName, email, password, confirm);
            if (ok) {
                showSuccess("Registration successful. You can login now.");
                new LoginWindow(stage).show();
            } else {
                showError("Username already exists or register failed.");
            }
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
        } catch (Exception ex) {
            showError("Register failed: " + ex.getMessage());
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Register error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showSuccess(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Register");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();
    }
}

