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
    @FXML private Button kitchenButton;
    @FXML private Button tablesButton;
    @FXML private Button settingsButton;
    @FXML private Button menuButton;
    @FXML private Button variantsButton;
    @FXML private Button reportsButton;

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
        boolean manager = services.session.isManager();
        kitchenButton.setVisible(manager);
        kitchenButton.setManaged(manager);
        kitchenButton.setOnAction(e -> navigator.toKitchenRouting());
        tablesButton.setVisible(manager);
        tablesButton.setManaged(manager);
        tablesButton.setOnAction(e -> navigator.toTables());
        settingsButton.setVisible(admin);
        settingsButton.setManaged(admin);
        settingsButton.setOnAction(e -> navigator.toSettings());
        menuButton.setVisible(manager);
        menuButton.setManaged(manager);
        menuButton.setOnAction(e -> navigator.toMenu());
        variantsButton.setVisible(manager);
        variantsButton.setManaged(manager);
        variantsButton.setOnAction(e -> navigator.toVariants());
        reportsButton.setVisible(manager);
        reportsButton.setManaged(manager);
        reportsButton.setOnAction(e -> navigator.toReports());
    }
}
