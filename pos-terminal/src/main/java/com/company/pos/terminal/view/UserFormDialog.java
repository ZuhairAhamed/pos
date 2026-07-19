package com.company.pos.terminal.view;

import com.company.pos.terminal.api.CreateUserRequest;
import com.company.pos.terminal.api.UpdateUserRequest;
import com.company.pos.terminal.api.UserView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Modal to create or edit a staff user. Pure view — collects input only; the controller performs
 * all HTTP. Create mode returns a {@link CreateUserRequest}; edit mode returns an
 * {@link UpdateUserRequest} (username and password are not editable here — password is changed via
 * {@link ResetCredentialDialog}). Only {@link #isValidCreate} is unit-tested headlessly.
 */
public final class UserFormDialog {

    private static final List<String> ROLES = List.of("CASHIER", "MANAGER", "ADMIN");

    private UserFormDialog() {}

    public static Optional<CreateUserRequest> promptCreate() {
        Dialog<CreateUserRequest> dialog = new Dialog<>();
        dialog.setTitle("New staff user");
        dialog.setHeaderText("Create a staff login");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField username = textField("username");
        TextField displayName = textField("full name");
        PasswordField password = new PasswordField();
        password.setPromptText("password");
        TextField cashierCode = textField("cashier code (optional)");
        PasswordField pin = new PasswordField();
        pin.setPromptText("PIN (optional)");
        List<ToggleButton> roleToggles = roleToggles(Set.of());

        VBox box = new VBox(12,
                field("Username", username),
                field("Full name", displayName),
                field("Password", password),
                field("Roles", roleRow(roleToggles)),
                field("Cashier code", cashierCode),
                field("PIN", pin));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(!isValidCreate(
                username.getText(), displayName.getText(), password.getText(), selectedRoles(roleToggles)));
        username.textProperty().addListener((o, a, b) -> revalidate.run());
        displayName.textProperty().addListener((o, a, b) -> revalidate.run());
        password.textProperty().addListener((o, a, b) -> revalidate.run());
        roleToggles.forEach(t -> t.selectedProperty().addListener((o, a, b) -> revalidate.run()));
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit) {
                return null;
            }
            Set<String> roles = selectedRoles(roleToggles);
            if (!isValidCreate(username.getText(), displayName.getText(), password.getText(), roles)) {
                return null;
            }
            return new CreateUserRequest(username.getText().trim(), displayName.getText().trim(),
                    password.getText(), roles, trimToNull(cashierCode.getText()), trimToNull(pin.getText()));
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    public static Optional<UpdateUserRequest> promptEdit(UserView existing) {
        Dialog<UpdateUserRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit staff user");
        dialog.setHeaderText("Edit " + existing.username());
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField displayName = textField("full name");
        displayName.setText(existing.displayName());
        List<ToggleButton> roleToggles = roleToggles(existing.roles());

        VBox box = new VBox(12,
                field("Full name", displayName),
                field("Roles", roleRow(roleToggles)));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                displayName.getText().isBlank() || selectedRoles(roleToggles).isEmpty());
        displayName.textProperty().addListener((o, a, b) -> revalidate.run());
        roleToggles.forEach(t -> t.selectedProperty().addListener((o, a, b) -> revalidate.run()));
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit) {
                return null;
            }
            Set<String> roles = selectedRoles(roleToggles);
            if (displayName.getText().isBlank() || roles.isEmpty()) {
                return null;
            }
            return new UpdateUserRequest(displayName.getText().trim(), roles);
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Headless-testable create validity: username, display name, password non-blank and ≥1 role. */
    static boolean isValidCreate(String username, String displayName, String password, Set<String> roles) {
        return username != null && !username.isBlank()
                && displayName != null && !displayName.isBlank()
                && password != null && !password.isBlank()
                && roles != null && !roles.isEmpty();
    }

    private static List<ToggleButton> roleToggles(Set<String> selected) {
        return ROLES.stream().map(r -> {
            ToggleButton t = new ToggleButton(r);
            t.getStyleClass().add("chip");
            t.setSelected(selected.contains(r));
            return t;
        }).toList();
    }

    private static HBox roleRow(List<ToggleButton> toggles) {
        HBox row = new HBox(8);
        row.getChildren().addAll(toggles);
        return row;
    }

    private static Set<String> selectedRoles(List<ToggleButton> toggles) {
        Set<String> out = new LinkedHashSet<>();
        for (ToggleButton t : toggles) {
            if (t.isSelected()) {
                out.add(t.getText());
            }
        }
        return out;
    }

    private static TextField textField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        return f;
    }

    private static VBox field(String label, Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
