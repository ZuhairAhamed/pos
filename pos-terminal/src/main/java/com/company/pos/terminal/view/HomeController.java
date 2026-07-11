package com.company.pos.terminal.view;

import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * Thin controller for the post-login mode picker. Two tiles route to the dine-in table map or a
 * fresh retail sale. Shows the signed-in user and a sign-out affordance. No business logic.
 */
public class HomeController {

    private final Services services;
    private final Navigator navigator;

    @FXML private Label userLabel;
    @FXML private Button signOutButton;
    @FXML private Button dineInButton;
    @FXML private Button retailButton;

    public HomeController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
    }

    @FXML
    public void initialize() {
        String user = services.session.username();
        userLabel.setText(user == null ? "" : "Signed in: " + user);
        dineInButton.setOnAction(e -> navigator.toTableMap());
        retailButton.setOnAction(e -> navigator.toRetail());
        signOutButton.setOnAction(e -> {
            services.session.clear();
            navigator.toLogin();
        });
    }
}
