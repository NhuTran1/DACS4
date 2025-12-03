package view.components;

import javafx.geometry.Insets;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

public class GlassCard extends VBox {

    public GlassCard() {
        setSpacing(25);
        setPadding(new Insets(50, 50, 40, 50));
        setMaxWidth(420);
        setMaxHeight(550);

        setStyle("""
            -fx-background-color: rgba(22, 33, 62, 0.85);
            -fx-background-radius: 20;
            -fx-border-color: rgba(102, 126, 234, 0.3);
            -fx-border-width: 1;
            -fx-border-radius: 20;
        """);

        DropShadow shadow = new DropShadow();
        shadow.setRadius(30);
        shadow.setColor(Color.web("#667eea", 0.5));

        setEffect(shadow);
    }
}
