package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

/**
 * Single-field modal for a new password or PIN. Pure view — collects input only. When
 * {@code allowBlank} is true (PIN), an empty value is returned as an empty string (the caller
 * treats blank as "clear the PIN"); otherwise Submit stays disabled until the field is non-blank.
 */
public final class ResetCredentialDialog {

    private ResetCredentialDialog() {}

    public static Optional<String> prompt(String title, String fieldLabel, boolean allowBlank) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        PasswordField value = new PasswordField();
        value.setPromptText(fieldLabel);
        Label l = new Label(fieldLabel);
        l.getStyleClass().add("field-label");
        VBox box = new VBox(8, l, value);
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        if (!allowBlank) {
            Node submitNode = dialog.getDialogPane().lookupButton(submit);
            Runnable revalidate = () -> submitNode.setDisable(value.getText().isBlank());
            value.textProperty().addListener((o, a, b) -> revalidate.run());
            revalidate.run();
        }

        dialog.setResultConverter(bt -> {
            if (bt != submit) {
                return null;
            }
            return value.getText();   // may be "" when allowBlank (clears the PIN)
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }
}
