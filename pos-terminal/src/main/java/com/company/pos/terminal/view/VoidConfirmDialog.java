package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * Modal confirmation for voiding the whole order. Collects an OPTIONAL free-text reason (may be
 * blank). Pure view; the caller obtains manager approval and performs the void. Display-dependent
 * — exercised by the manual E2E.
 *
 * @return the reason string (possibly empty) when confirmed; empty Optional when cancelled.
 */
public final class VoidConfirmDialog {

    private VoidConfirmDialog() {}

    public static Optional<String> promptForReason() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Void order");
        dialog.setHeaderText("Void this entire order?");
        ButtonType voidIt = new ButtonType("Void order", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(voidIt, cancel);
        dialog.getDialogPane().getStyleClass().add("void-dialog");

        Label hint = new Label("This cannot be undone. A manager PIN is required next.");
        hint.setWrapText(true);
        Label reasonLabel = new Label("Reason (optional)");
        reasonLabel.getStyleClass().add("field-label");
        TextArea reasonField = new TextArea();
        reasonField.setPromptText("e.g. walkout, wrong table");
        reasonField.setPrefRowCount(2);
        reasonField.setWrapText(true);
        VBox box = new VBox(12, hint, reasonLabel, reasonField);
        dialog.getDialogPane().setContent(box);

        dialog.setResultConverter(bt -> bt == voidIt
                ? (reasonField.getText() == null ? "" : reasonField.getText().trim())
                : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }
}
