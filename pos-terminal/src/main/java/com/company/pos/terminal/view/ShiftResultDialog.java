package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.DrawerReconciliation;
import java.math.BigDecimal;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Modal reconciliation result shown after a shift closes: opening float, cash sales, pay-ins/outs,
 * expected, counted, and the variance labelled Over / Short / Balanced (word + colour, never colour
 * alone). Pure view. Display-dependent — exercised by the manual E2E; only {@link #varianceText}
 * and {@link #varianceStyle} are unit-tested.
 */
public final class ShiftResultDialog {

    private ShiftResultDialog() {}

    public static void show(DrawerReconciliation cash) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Shift closed");
        dialog.setHeaderText("Shift closed · drawer reconciliation");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.getDialogPane().getStyleClass().add("shift-result");

        GridPane g = new GridPane();
        g.setHgap(24);
        g.setVgap(8);
        int r = 0;
        r = addRow(g, r, "Opening float", money(cash.openingFloat(), cash.currencyCode()));
        r = addRow(g, r, "Cash sales (" + cash.cashSalesCount() + ")",
                money(cash.cashSales(), cash.currencyCode()));
        r = addRow(g, r, "Pay-ins", money(cash.payIns(), cash.currencyCode()));
        r = addRow(g, r, "Pay-outs", money(cash.payOuts(), cash.currencyCode()));
        r = addRow(g, r, "Expected", money(cash.expectedCash(), cash.currencyCode()));
        r = addRow(g, r, "Counted", money(cash.countedCash(), cash.currencyCode()));

        Label varianceKey = new Label("Variance");
        varianceKey.getStyleClass().add("field-label");
        Label varianceValue = new Label(varianceText(cash.variance(), cash.currencyCode()));
        varianceValue.getStyleClass().addAll("money", varianceStyle(cash.variance()));
        g.add(varianceKey, 0, r);
        g.add(varianceValue, 1, r);

        dialog.getDialogPane().setContent(new VBox(12, g));
        dialog.showAndWait();
    }

    private static int addRow(GridPane g, int r, String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("field-label");
        Label v = new Label(value);
        v.getStyleClass().add("money");
        g.add(k, 0, r);
        g.add(v, 1, r);
        return r + 1;
    }

    /** "Balanced" at zero, else "Over <amt>" / "Short <amt>" (word + amount; colour is a 2nd cue). */
    static String varianceText(BigDecimal variance, String currency) {
        if (variance == null || variance.signum() == 0) {
            return "Balanced";
        }
        String word = variance.signum() > 0 ? "Over" : "Short";
        return word + " " + money(variance.abs(), currency);
    }

    static String varianceStyle(BigDecimal variance) {
        if (variance == null || variance.signum() == 0) {
            return "variance-balanced";
        }
        return variance.signum() > 0 ? "variance-over" : "variance-short";
    }

    private static String money(BigDecimal v, String currency) {
        BigDecimal amt = v == null ? BigDecimal.ZERO : v;
        return amt.toPlainString() + " " + (currency == null ? "" : currency);
    }
}
