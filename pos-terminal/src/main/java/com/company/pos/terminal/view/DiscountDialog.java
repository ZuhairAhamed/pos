package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.DiscountPolicyView;
import com.company.pos.terminal.viewmodel.DiscountRules;
import java.math.BigDecimal;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Modal whole-sale discount entry: percent/amount toggle, pin-pad value, and a required reason
 * chip from the store policy. Shows a live amber hint when the entry would exceed the cashier
 * cap (approval is then collected at tender time, not here). Pure view with no server access,
 * mirroring StartShiftDialog: the caller re-quotes with the returned discount.
 *
 * <p>Display-dependent (constructs a JavaFX Dialog) — exercised by the manual E2E; only the
 * static {@link #build} rule is unit-tested.
 */
public final class DiscountDialog {

    private DiscountDialog() {}

    /** {@code base} is the UNDISCOUNTED quoted subtotal (the server's cap base). */
    public static Optional<DiscountInput> promptForDiscount(DiscountPolicyView policy,
            BigDecimal base, boolean isManager) {
        Dialog<DiscountInput> dialog = new Dialog<>();
        dialog.setTitle("Discount");
        dialog.setHeaderText("Whole-sale discount");
        ButtonType apply = new ButtonType("Apply discount", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, cancel);
        dialog.getDialogPane().getStyleClass().add("discount-modal");

        ToggleGroup typeGroup = new ToggleGroup();
        ToggleButton percent = new ToggleButton("% Percent");
        ToggleButton amount = new ToggleButton("SAR Amount");
        percent.setToggleGroup(typeGroup);
        amount.setToggleGroup(typeGroup);
        percent.getStyleClass().add("reason-chip");
        amount.getStyleClass().add("reason-chip");
        percent.setSelected(true);
        HBox typeRow = new HBox(8, percent, amount);
        typeRow.setAlignment(Pos.CENTER);

        Label valueLabel = new Label("Discount value");
        valueLabel.getStyleClass().add("field-label");
        TextField valueField = new TextField();
        valueField.setPromptText("0");
        valueField.getStyleClass().add("money");
        VBox valueBox = new VBox(6, valueLabel, valueField);
        valueBox.getStyleClass().add("field");

        ToggleGroup reasonGroup = new ToggleGroup();
        FlowPane reasons = new FlowPane(8, 8);
        reasons.setAlignment(Pos.CENTER);
        for (String code : policy.reasonCodes()) {
            ToggleButton chip = new ToggleButton(code);
            chip.setToggleGroup(reasonGroup);
            chip.getStyleClass().add("reason-chip");
            reasons.getChildren().add(chip);
        }

        Label hint = new Label("⚠ Needs manager approval at payment");
        hint.getStyleClass().add("approval-hint");
        hint.setVisible(false);
        hint.setManaged(false);

        VBox box = new VBox(16, typeRow, valueBox, Keypads.numericPad(valueField), reasons, hint);
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        // Apply stays disabled until the entry builds; the hint tracks the cap live.
        javafx.scene.Node applyNode = dialog.getDialogPane().lookupButton(apply);
        Runnable revalidate = () -> {
            DiscountInput d = build(percent.isSelected() ? "PERCENT" : "AMOUNT",
                    valueField.getText(), selectedReason(reasonGroup));
            applyNode.setDisable(d == null);
            boolean needs = DiscountRules.needsApproval(d, base, policy, isManager);
            hint.setVisible(needs);
            hint.setManaged(needs);
        };
        valueField.textProperty().addListener((o, was, now) -> revalidate.run());
        typeGroup.selectedToggleProperty().addListener((o, was, now) -> revalidate.run());
        reasonGroup.selectedToggleProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == apply
                ? build(percent.isSelected() ? "PERCENT" : "AMOUNT", valueField.getText(),
                        selectedReason(reasonGroup))
                : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    private static String selectedReason(ToggleGroup group) {
        Toggle t = group.getSelectedToggle();
        return t == null ? null : ((ToggleButton) t).getText();
    }

    /** A valid discount or null: positive numeric value, PERCENT ≤ 100, reason required. */
    static DiscountInput build(String type, String rawValue, String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(rawValue == null ? "" : rawValue.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (value.signum() <= 0) {
            return null;
        }
        if ("PERCENT".equals(type) && value.compareTo(new BigDecimal("100")) > 0) {
            return null;
        }
        return new DiscountInput(type, value, reasonCode);
    }
}
