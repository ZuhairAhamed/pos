package com.company.pos.terminal.view;

import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

/**
 * Landing screen for the manager/admin area. Reachable by MANAGER or ADMIN; individual tiles are
 * shown per-role. For now the only tile is Staff (ADMIN-only) — later go-live items add tiles here.
 */
public class AdminController {

    private final Services services;
    private final Navigator navigator;

    @FXML private Button backButton;
    @FXML private Button staffButton;
    @FXML private Button productsButton;

    public AdminController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
    }

    @FXML
    public void initialize() {
        backButton.setOnAction(e -> navigator.toHome());
        boolean admin = services.session.roles().contains("ADMIN");
        staffButton.setVisible(admin);
        staffButton.setManaged(admin);
        staffButton.setOnAction(e -> navigator.toStaff());
        productsButton.setVisible(admin);
        productsButton.setManaged(admin);
        productsButton.setOnAction(e -> navigator.toProducts());
    }
}
