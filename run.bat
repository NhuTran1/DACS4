@echo off
java ^
--module-path javafx/lib ^
--add-modules javafx.controls,javafx.fxml,javafx.web ^
-jar chat-1.0-SNAPSHOT.jar
pause
