package com.company.pos.terminal.view;

import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.LoginViewModel;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * Thin controller for the login screen. It binds the FXML controls to a
 * {@link LoginViewModel} (constructed from {@link Services#authApi}), forwards
 * PIN-pad and button gestures to the view-model, and runs the (synchronous) VM
 * login call off the FX thread via {@link FxTasks} so the UI never blocks on
 * the network. No business logic lives here.
 */
public class LoginController {
    private final Services services;
    private final Navigator navigator;
    private final LoginViewModel vm;

    @FXML private Label storeLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Label errorLabel;

    public LoginController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new LoginViewModel(services.authApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        storeLabel.setText("Store " + services.config.storeId()
                + "  ·  Terminal " + services.config.terminalId());

        vm.username().bindBidirectional(usernameField.textProperty());
        vm.passwordOrPin().bindBidirectional(passwordField.textProperty());

        // Error banner: text from the VM; hidden (and not laid out) when empty.
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        // Busy state: disable inputs while a login is in flight.
        usernameField.disableProperty().bind(vm.busy());
        passwordField.disableProperty().bind(vm.busy());
        loginButton.disableProperty().bind(vm.busy());
        loginButton.textProperty().bind(
                Bindings.when(vm.busy()).then("Signing in…").otherwise("Sign in"));

        loginButton.setOnAction(e -> doLogin());
    }

    /** Numeric PIN-pad key: append the digit to the password/PIN field. */
    @FXML
    private void onPinKey(javafx.event.ActionEvent event) {
        if (vm.busy().get()) return;
        Button key = (Button) event.getSource();
        passwordField.appendText(key.getText());
    }

    /** PIN-pad "Clear": empty the password/PIN field. */
    @FXML
    private void onPinClear() {
        if (vm.busy().get()) return;
        passwordField.clear();
    }

    /** PIN-pad "Enter": same action as the Sign in button. */
    @FXML
    private void onPinEnter() {
        if (vm.busy().get()) return;
        doLogin();
    }

    private void doLogin() {
        if (vm.busy().get()) return;
        FxTasks.run(
                vm::login,
                () -> {
                    if (vm.loggedIn().get()) navigator.toTableMap();
                },
                err -> { /* VM already surfaced ApiException text via errorMessage */ });
    }
}
