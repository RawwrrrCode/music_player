package com.musicplayer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("player.fxml"));
        Scene scene = new Scene(loader.load(), 460, 710);
        PlayerController controller = loader.getController();

        // Load CSS
        String css = getClass().getResource("player.css").toExternalForm();
        scene.getStylesheets().add(css);

        stage.setTitle("Music Player");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> controller.savePlaylist());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
