package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.DrawerReconciliation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Modal mid-shift drawer peek. Shows only blind-safe activity — opening float, cash-sales COUNT,
 * pay-ins and pay-outs totals — and offers Pay in / Pay out / Done. It deliberately never shows the
 * cash-sales amount, expected cash, or variance, so it cannot un-blind the shift close. Pure view;
 * only {@link #activityRows} is unit-tested.
 */
public final class DrawerActivityDialog {

    /** Which movement the cashier chose; empty result = Done. */
    public enum DrawerAction { PAY_IN, PAY_OUT }

    /** One key/value line of the activity grid. */
    public record Row(String label, String value) {}

    private DrawerActivityDialog() {}

    /** Blind-safe rows: opening float, cash-sales COUNT (not amount), pay-ins, pay-outs. */
    public static List<Row> activityRows(DrawerReconciliation r) {
        return List.of(
                new Row("Opening float", money(r.openingFloat(), r.currencyCode())),
                new Row("Cash sales", String.valueOf(r.cashSalesCount())),
                new Row("Pay-ins", money(r.payIns(), r.currencyCode())),
                new Row("Pay-outs", money(r.payOuts(), r.currencyCode())));
    }

    public static Optional<DrawerAction> promptForAction(DrawerReconciliation activity,
            String terminalId) {
        Dialog<DrawerAction> dialog = new Dialog<>();
        dialog.setTitle("Cash drawer");
        dialog.setHeaderText("Cash drawer · Terminal " + terminalId);
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType payIn = new ButtonType("Pay in", ButtonBar.ButtonData.OTHER);
        ButtonType payOut = new ButtonType("Pay out", ButtonBar.ButtonData.OTHER);
        ButtonType done = new ButtonType("Done", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(payIn, payOut, done);

        GridPane g = new GridPane();
        g.setHgap(24);
        g.setVgap(8);
        int r = 0;
        for (Row row : activityRows(activity)) {
            Label k = new Label(row.label());
            k.getStyleClass().add("field-label");
            Label v = new Label(row.value());
            v.getStyleClass().add("money");
            g.add(k, 0, r);
            g.add(v, 1, r);
            r++;
        }
        dialog.getDialogPane().setContent(new VBox(12, g));

        dialog.setResultConverter(bt -> {
            if (bt == payIn) return DrawerAction.PAY_IN;
            if (bt == payOut) return DrawerAction.PAY_OUT;
            return null;
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    private static String money(BigDecimal v, String currency) {
        BigDecimal amt = v == null ? BigDecimal.ZERO : v;
        return amt.toPlainString() + " " + (currency == null ? "" : currency);
    }
}
