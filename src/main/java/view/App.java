package view;
import javafx.application.Application;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class App extends Application {
    
	@Override
    public void start(Stage primaryStage) {
        // Set window style
        primaryStage.initStyle(StageStyle.DECORATED);
        
        // Show login window
        LoginWindow loginWindow = new LoginWindow(primaryStage);
//        RegisterWindow registerWindow = new RegisterWindow(primaryStage);
        loginWindow.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
