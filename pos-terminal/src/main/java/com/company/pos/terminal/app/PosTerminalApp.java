package com.company.pos.terminal.app;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX entry point for the standalone POS terminal. Builds the
 * {@link Services} composition root in {@link #init()} (off the FX thread),
 * then shows the login screen via the {@link Navigator} in {@link #start}.
 */
public class PosTerminalApp extends Application {
    private Services services;

    @Override
    public void init() {
        services = new Services();
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("POS Terminal — " + services.config.terminalId());
        Navigator navigator = new Navigator(stage, services);
        navigator.toLogin();
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
