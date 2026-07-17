package com.company.pos.terminal.view;

import java.math.BigDecimal;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal blind cash count for closing the shift. Collects the counted cash via keyboard or the
 * shared count-by-denomination helper. The expected amount is deliberately NOT shown (blind
 * count). Pure view; the caller performs the close and shows the reconciliation result.
 * Display-dependent — exercised by the manual E2E; only {@link #parse} is unit-tested.
 */
public final class CloseShiftDialog {

    private CloseShiftDialog() {}

    public static Optional<BigDecimal> promptForCount(String terminalId) {
        Dialog<BigDecimal> dialog = new Dialog<>();
        dialog.setTitle("Close shift");
        dialog.setHeaderText("Close shift · Terminal " + terminalId);
        ButtonType close = new ButtonType("Count & close", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(close, cancel);
        dialog.getDialogPane().getStyleClass().add("shift-modal");

        Label hint = new Label("Count the drawer and enter the total. The expected amount is hidden.");
        hint.setWrapText(true);
        Label countLabel = new Label("Counted cash");
        countLabel.getStyleClass().add("field-label");
        TextField countField = new TextField();
        countField.setPromptText("0.00");
        countField.getStyleClass().add("money");
        VBox countBox = new VBox(6, countLabel, countField);
        countBox.getStyleClass().add("field");

        VBox box = new VBox(16, hint, countBox, Keypads.numericPad(countField),
                DenominationCounter.pane(countField));
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        javafx.scene.Node closeNode = dialog.getDialogPane().lookupButton(close);
        Runnable revalidate = () -> closeNode.setDisable(parse(countField.getText()) == null);
        countField.textProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == close ? parse(countField.getText()) : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Non-negative decimal, blank counting as zero; invalid → null. */
    static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
