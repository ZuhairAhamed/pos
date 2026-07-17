package com.company.pos.terminal.view;

import com.company.pos.terminal.view.DrawerActivityDialog.DrawerAction;
import java.math.BigDecimal;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal amount + reason entry for a single cash-drawer movement. Pure view; the caller performs the
 * pay-in/pay-out. Submit is disabled until the amount is a positive decimal AND the reason is
 * non-blank. Only {@link #parseAmount} is unit-tested.
 */
public final class CashMovementDialog {

    /** The collected movement input. */
    public record CashMovementInput(BigDecimal amount, String reason) {}

    private CashMovementDialog() {}

    public static Optional<CashMovementInput> prompt(DrawerAction action, String terminalId) {
        boolean payIn = action == DrawerAction.PAY_IN;
        String verb = payIn ? "Pay in" : "Pay out";
        Dialog<CashMovementInput> dialog = new Dialog<>();
        dialog.setTitle(verb);
        dialog.setHeaderText(verb + " · Terminal " + terminalId);
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(verb, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        Label amountLabel = new Label("Amount");
        amountLabel.getStyleClass().add("field-label");
        TextField amountField = new TextField();
        amountField.setPromptText("0.00");
        amountField.getStyleClass().add("money");
        VBox amountBox = new VBox(6, amountLabel, amountField);
        amountBox.getStyleClass().add("field");

        Label reasonLabel = new Label("Reason");
        reasonLabel.getStyleClass().add("field-label");
        TextField reasonField = new TextField();
        reasonField.setPromptText(payIn ? "e.g. change fund top-up" : "e.g. supplier cash");
        VBox reasonBox = new VBox(6, reasonLabel, reasonField);
        reasonBox.getStyleClass().add("field");

        VBox box = new VBox(16, amountBox, reasonBox, Keypads.numericPad(amountField));
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                parseAmount(amountField.getText()) == null || reasonField.getText().isBlank());
        amountField.textProperty().addListener((o, was, now) -> revalidate.run());
        reasonField.textProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit) return null;
            BigDecimal amount = parseAmount(amountField.getText());
            if (amount == null || reasonField.getText().isBlank()) return null;
            return new CashMovementInput(amount, reasonField.getText().trim());
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Positive non-zero decimal → value; blank, non-numeric, zero, or negative → null. */
    static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() <= 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
