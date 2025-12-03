package view.components;

import javafx.scene.control.TextField;

public class StyledTextField extends TextField {

    public StyledTextField(String prompt) {
        setPromptText(prompt);
        setPrefHeight(50);
        setStyle("""
            -fx-background-color: rgba(15, 52, 96, 0.5);
            -fx-background-radius: 10;
            -fx-text-fill: #eaeaea;
            -fx-prompt-text-fill: #888888;
            -fx-border-color: transparent;
            -fx-border-width: 2;
            -fx-border-radius: 10;
            -fx-padding: 0 15;
        """);

        focusedProperty().addListener((obs, old, focus) -> {
            if (focus) {
                setStyle(getStyle() + "-fx-border-color: #667eea;");
            } else {
                setStyle(getStyle().replace("-fx-border-color: #667eea;", ""));
            }
        });
    }
}
