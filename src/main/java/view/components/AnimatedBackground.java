package view.components;

import javafx.animation.TranslateTransition;
import javafx.scene.layout.Pane;
import javafx.scene.paint.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

public class AnimatedBackground extends Pane {

    public AnimatedBackground() {
        setPrefSize(1000, 650);

        double[] sizes = {300, 250, 200, 150, 100};
        double[] x = {-50, 800, 200, 700, 400};
        double[] y = {-50, -30, 500, 600, 300};

        for (int i = 0; i < 5; i++) {
            Circle c = createCircle(sizes[i], x[i], y[i], i);
            getChildren().add(c);
        }
    }

    private Circle createCircle(double size, double x, double y, int index) {
        Circle circle = new Circle(size / 2);
        circle.setLayoutX(x);
        circle.setLayoutY(y);

        circle.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#667eea", 0.3)),
                new Stop(1, Color.web("#764ba2", 0.1))
        ));

        TranslateTransition tt = new TranslateTransition(Duration.seconds(5 + index), circle);
        tt.setByY(30 + index * 10);
        tt.setAutoReverse(true);
        tt.setCycleCount(TranslateTransition.INDEFINITE);
        tt.play();

        return circle;
    }
}
