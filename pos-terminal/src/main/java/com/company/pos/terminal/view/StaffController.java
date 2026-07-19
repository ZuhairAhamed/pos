package com.company.pos.terminal.view;

import com.company.pos.terminal.api.CreateUserRequest;
import com.company.pos.terminal.api.UpdateUserRequest;
import com.company.pos.terminal.api.UserView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.UserAdminViewModel;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * ADMIN-only staff list. Loads users off the FX thread via FxTasks, edits through the I/O-free
 * dialogs (the controller performs the HTTP), and reloads after every mutation. Dialog-driven
 * flows re-kick a fresh FxTasks task rather than calling the VM inside onDone.
 */
public class StaffController {

    private static final System.Logger LOG = System.getLogger(StaffController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final UserAdminViewModel vm;

    @FXML private Label errorLabel;
    @FXML private CheckBox includeDisabled;
    @FXML private Button backButton;
    @FXML private Button newButton;
    @FXML private Button editButton;
    @FXML private Button resetPasswordButton;
    @FXML private Button resetPinButton;
    @FXML private Button toggleActiveButton;
    @FXML private TableView<UserView> table;
    @FXML private TableColumn<UserView, String> nameCol;
    @FXML private TableColumn<UserView, String> usernameCol;
    @FXML private TableColumn<UserView, String> rolesCol;
    @FXML private TableColumn<UserView, String> codeCol;
    @FXML private TableColumn<UserView, String> statusCol;

    public StaffController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new UserAdminViewModel(services.usersApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().displayName()));
        usernameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().username()));
        rolesCol.setCellValueFactory(c -> new SimpleStringProperty(String.join(", ", c.getValue().roles())));
        codeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().cashierCode() == null ? "" : c.getValue().cashierCode()));
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().enabled() ? "Active" : "Disabled"));

        errorLabel.textProperty().bind(vm.errorMessage());
        includeDisabled.selectedProperty().addListener((o, a, b) -> reload());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        newButton.setOnAction(e -> createUser());
        editButton.setOnAction(e -> editSelected());
        resetPasswordButton.setOnAction(e -> resetPasswordSelected());
        resetPinButton.setOnAction(e -> resetPinSelected());
        toggleActiveButton.setOnAction(e -> toggleActiveSelected());

        refreshButtons(null);
        reload();
    }

    private void reload() {
        boolean incl = includeDisabled.isSelected();
        final List<UserView>[] holder = new List[1];
        FxTasks.run(() -> holder[0] = vm.load(incl),
                () -> {
                    if (holder[0] != null) {
                        table.setItems(FXCollections.observableArrayList(holder[0]));
                    }
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load users failed", err));
    }

    private void refreshButtons(UserView sel) {
        boolean has = sel != null;
        editButton.setDisable(!has);
        resetPasswordButton.setDisable(!has);
        resetPinButton.setDisable(!has);
        toggleActiveButton.setDisable(!has);
        toggleActiveButton.setText(has && !sel.enabled() ? "Reactivate" : "Deactivate");
    }

    private void createUser() {
        Optional<CreateUserRequest> req = UserFormDialog.promptCreate();
        req.ifPresent(r -> {
            final UserView[] holder = new UserView[1];
            FxTasks.run(() -> holder[0] = vm.create(r),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Create user failed", err));
        });
    }

    private void editSelected() {
        UserView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Optional<UpdateUserRequest> req = UserFormDialog.promptEdit(sel);
        req.ifPresent(r -> {
            final UserView[] holder = new UserView[1];
            FxTasks.run(() -> holder[0] = vm.update(sel.id(), r),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Update user failed", err));
        });
    }

    private void resetPasswordSelected() {
        UserView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        ResetCredentialDialog.prompt("Reset password", "New password", false).ifPresent(value -> {
            final boolean[] holder = {false};
            FxTasks.run(() -> holder[0] = vm.resetPassword(sel.id(), value),
                    () -> { /* errorLabel is bound; nothing to do on success */ },
                    err -> LOG.log(System.Logger.Level.ERROR, "Reset password failed", err));
        });
    }

    private void resetPinSelected() {
        UserView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        ResetCredentialDialog.prompt("Reset PIN", "New PIN (blank clears)", true).ifPresent(value -> {
            final boolean[] holder = {false};
            FxTasks.run(() -> holder[0] = vm.resetPin(sel.id(), value),
                    () -> { /* bound errorLabel surfaces failures */ },
                    err -> LOG.log(System.Logger.Level.ERROR, "Reset PIN failed", err));
        });
    }

    private void toggleActiveSelected() {
        UserView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        boolean reactivating = !sel.enabled();
        final boolean[] holder = {false};
        FxTasks.run(
                () -> holder[0] = reactivating ? vm.reactivate(sel.id()) : vm.deactivate(sel.id()),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Toggle active failed", err));
    }
}
