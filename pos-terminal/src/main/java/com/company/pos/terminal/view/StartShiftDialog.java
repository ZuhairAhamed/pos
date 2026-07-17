package com.company.pos.terminal.view;

import com.company.pos.terminal.viewmodel.StartShiftViewModel;
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
 * Modal start-shift prompt shown on the first sign-in of the day (no open shift on this
 * terminal). Collects the opening cash float via keyboard, the shared pin-pad form
 * language, or an optional count-by-denomination helper whose running total writes the
 * float field. Pure view with no server access, mirroring ModifierPickerDialog: returns
 * the confirmed float, or empty when the cashier skips. The caller performs the POST.
 *
 * <p>Display-dependent (constructs a JavaFX Dialog) — exercised by the manual E2E, never
 * a headless unit test; only the static {@link #parse} rule is unit-tested.
 */
public final class StartShiftDialog {

    private StartShiftDialog() {}

    public static Optional<BigDecimal> promptForFloat(String terminalId, String username) {
        Dialog<BigDecimal> dialog = new Dialog<>();
        dialog.setTitle("Start shift");
        dialog.setHeaderText("Start shift · Terminal " + terminalId);
        ButtonType start = new ButtonType("Start shift", ButtonBar.ButtonData.OK_DONE);
        ButtonType skip = new ButtonType("Skip for now", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(start, skip);
        dialog.getDialogPane().getStyleClass().add("shift-modal");

        Label signedIn = new Label("Signed in as " + username);
        signedIn.getStyleClass().add("subtitle");

        Label floatLabel = new Label("Opening cash float");
        floatLabel.getStyleClass().add("field-label");
        TextField floatField = new TextField();
        floatField.setPromptText("0.00");
        floatField.getStyleClass().add("money");
        VBox floatBox = new VBox(6, floatLabel, floatField);
        floatBox.getStyleClass().add("field");

        VBox box = new VBox(16, signedIn, floatBox, Keypads.numericPad(floatField),
                DenominationCounter.pane(floatField));
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        // "Start shift" stays disabled while the field does not parse to a non-negative amount.
        javafx.scene.Node startNode = dialog.getDialogPane().lookupButton(start);
        Runnable revalidate = () -> startNode.setDisable(parse(floatField.getText()) == null);
        floatField.textProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == start ? parse(floatField.getText()) : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Non-negative decimal, blank counting as zero (an empty till is legal); invalid → null. */
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
