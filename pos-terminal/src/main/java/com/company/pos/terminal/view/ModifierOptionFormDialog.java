package com.company.pos.terminal.view;

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

/** Modal to create or edit a modifier option. Pure view — collects input only. Only
 *  {@link #parseDelta} and {@link #validate} are unit-tested. */
public final class ModifierOptionFormDialog {

    private ModifierOptionFormDialog() {
    }

    public record OptionResult(String name, BigDecimal priceDelta) {
    }

    /** {@code currentName} null on create. {@code currentDelta} is the plain-string delta or "". */
    public static Optional<OptionResult> promptForOption(String currentName, String currentDelta) {
        boolean editing = currentName != null;
        Dialog<OptionResult> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit option" : "New option");
        dialog.setHeaderText(editing ? "Edit \"" + currentName + "\"" : "Add an option");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Add", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField name = new TextField();
        name.setPromptText("name (e.g. Extra cheese)");
        if (editing) {
            name.setText(currentName);
        }
        TextField delta = new TextField();
        delta.setPromptText("price delta (e.g. 2.50, -1.00, 0)");
        delta.setText(currentDelta == null ? "" : currentDelta);

        VBox box = new VBox(12, field("Name", name), field("Price delta", delta));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(validate(name.getText(), delta.getText()) != null);
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        delta.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || validate(name.getText(), delta.getText()) != null) {
                return null;
            }
            return new OptionResult(name.getText().trim(), parseDelta(delta.getText()));
        });
        return dialog.showAndWait();
    }

    /** Parse a signed decimal (negatives allowed), or null if blank/unparseable. */
    static BigDecimal parseDelta(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Null when valid; else a message. */
    static String validate(String name, String delta) {
        if (name == null || name.isBlank()) {
            return "Option name is required";
        }
        if (parseDelta(delta) == null) {
            return "Price delta must be a number (may be 0 or negative)";
        }
        return null;
    }

    private static VBox field(String labelText, Node control) {
        Label l = new Label(labelText);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }
}
