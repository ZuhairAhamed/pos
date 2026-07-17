package com.company.pos.terminal.view;

import com.company.pos.terminal.viewmodel.StartShiftViewModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Shared optional count-by-denomination helper: a stepper row per SAR note whose running total
 * overwrites the given money field. Used by both the start-shift and close-shift dialogs so the
 * counting UI/behaviour lives in one place. Pure view; display-dependent (manual E2E).
 */
public final class DenominationCounter {

    private DenominationCounter() {}

    /** A collapsed TitledPane of denomination steppers; the running total writes {@code target}. */
    public static TitledPane pane(TextField target) {
        Map<BigDecimal, Integer> counts = new HashMap<>();
        VBox rows = new VBox(8);
        for (BigDecimal denom : StartShiftViewModel.DENOMINATIONS) {
            counts.put(denom, 0);
            rows.getChildren().add(row(denom, counts, target));
        }
        TitledPane pane = new TitledPane("Count by denomination (optional)", rows);
        pane.setExpanded(false);
        return pane;
    }

    private static HBox row(BigDecimal denom, Map<BigDecimal, Integer> counts, TextField target) {
        Label name = new Label(denom.toPlainString());
        name.getStyleClass().addAll("field-label", "money");
        name.setMinWidth(48);
        Label count = new Label("0");
        count.getStyleClass().add("money");
        count.setMinWidth(40);
        count.setAlignment(Pos.CENTER);
        Label lineTotal = new Label("0.00");
        lineTotal.getStyleClass().addAll("denom-line-total", "money");
        Button minus = new Button("−");
        Button plus = new Button("+");
        minus.getStyleClass().add("qty-stepper");
        plus.getStyleClass().add("qty-stepper");
        Runnable refresh = () -> {
            int n = counts.get(denom);
            count.setText(Integer.toString(n));
            lineTotal.setText(denom.multiply(BigDecimal.valueOf(n))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString());
            target.setText(StartShiftViewModel.total(counts).toPlainString());
        };
        minus.setOnAction(e -> {
            counts.computeIfPresent(denom, (d, n) -> Math.max(0, n - 1));
            refresh.run();
        });
        plus.setOnAction(e -> {
            counts.merge(denom, 1, Integer::sum);
            refresh.run();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, name, minus, count, plus, spacer, lineTotal);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("denom-row");
        return row;
    }
}
