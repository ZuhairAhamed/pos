package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal manager approval: collects the manager's code + PIN. Pure view — the CALLER exchanges
 * the credentials for a one-shot token off the FX thread (AuthApi.pinLoginForToken) and attaches
 * it to exactly one checkout call. The cashier session is never touched.
 *
 * <p>Display-dependent — exercised by the manual E2E; only the static {@link #build} rule is
 * unit-tested.
 */
public final class ManagerPinDialog {

    /** What the manager typed; exchanged for a one-shot token by the caller. */
    public record Credentials(String cashierCode, String pin) {}

    private ManagerPinDialog() {}

    public static Optional<Credentials> promptForApproval(String message) {
        Dialog<Credentials> dialog = new Dialog<>();
        dialog.setTitle("Manager approval");
        dialog.setHeaderText("Manager approval required");
        ButtonType approve = new ButtonType("Approve", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(approve, cancel);
        dialog.getDialogPane().getStyleClass().add("approval-modal");

        Label why = new Label(message);
        why.getStyleClass().add("approval-hint");
        why.setWrapText(true);

        Label codeLabel = new Label("Manager code");
        codeLabel.getStyleClass().add("field-label");
        TextField codeField = new TextField();
        codeField.setPromptText("e.g. M01");
        VBox codeBox = new VBox(6, codeLabel, codeField);
        codeBox.getStyleClass().add("field");

        Label pinLabel = new Label("PIN");
        pinLabel.getStyleClass().add("field-label");
        PasswordField pinField = new PasswordField();
        VBox pinBox = new VBox(6, pinLabel, pinField);
        pinBox.getStyleClass().add("field");

        VBox box = new VBox(16, why, codeBox, pinBox, Keypads.numericPad(pinField));
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        javafx.scene.Node approveNode = dialog.getDialogPane().lookupButton(approve);
        Runnable revalidate = () -> approveNode.setDisable(
                build(codeField.getText(), pinField.getText()) == null);
        codeField.textProperty().addListener((o, was, now) -> revalidate.run());
        pinField.textProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == approve
                ? build(codeField.getText(), pinField.getText())
                : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Both fields required (trimmed); otherwise null (Approve stays disabled). */
    static Credentials build(String code, String pin) {
        if (code == null || code.isBlank() || pin == null || pin.isBlank()) {
            return null;
        }
        return new Credentials(code.trim(), pin.trim());
    }
}
