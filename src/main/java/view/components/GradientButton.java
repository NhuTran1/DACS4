package view.components;

import javafx.animation.ScaleTransition;
import javafx.scene.control.Button;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

public class GradientButton extends Button {

    public GradientButton(String text) {
        super(text);

        setPrefWidth(320);
        setPrefHeight(50);
        setFont(Font.font("System", FontWeight.BOLD, 15));
        setTextFill(Color.WHITE);

        setStyle("""
            -fx-background-color: linear-gradient(to right, #667eea, #764ba2);
            -fx-background-radius: 10;
            -fx-cursor: hand;
        """);

        setOnMouseEntered(e -> playHover(true));
        setOnMouseExited(e -> playHover(false));
    }

    private void playHover(boolean enter) {
        ScaleTransition st = new ScaleTransition(Duration.millis(100), this);
        st.setToX(enter ? 1.05 : 1.0);
        st.setToY(enter ? 1.05 : 1.0);
        st.play();
    }
}
