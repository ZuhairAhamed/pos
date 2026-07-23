package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Modal to create or edit a modifier group. Pure view — collects input only. Only {@link #validate}
 *  is unit-tested. */
public final class ModifierGroupFormDialog {

    private ModifierGroupFormDialog() {
    }

    public record GroupResult(String name, int min, int max) {
    }

    /** {@code currentName} null on create. */
    public static Optional<GroupResult> promptForGroup(String currentName, int currentMin, int currentMax) {
        boolean editing = currentName != null;
        Dialog<GroupResult> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit modifier group" : "New modifier group");
        dialog.setHeaderText(editing ? "Edit \"" + currentName + "\"" : "Create a modifier group");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField name = new TextField();
        name.setPromptText("name (e.g. Add-ons)");
        if (editing) {
            name.setText(currentName);
        }
        Spinner<Integer> min = new Spinner<>(0, 99, editing ? currentMin : 0);
        min.setEditable(true);
        Spinner<Integer> max = new Spinner<>(1, 99, editing ? Math.max(currentMax, 1) : 1);
        max.setEditable(true);

        VBox box = new VBox(12, field("Name", name), field("Min selections", min),
                field("Max selections", max));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                validate(name.getText(), min.getValue(), max.getValue()) != null);
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        min.valueProperty().addListener((o, a, b) -> revalidate.run());
        max.valueProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || validate(name.getText(), min.getValue(), max.getValue()) != null) {
                return null;
            }
            return new GroupResult(name.getText().trim(), min.getValue(), max.getValue());
        });
        return dialog.showAndWait();
    }

    /** Null when valid; else a message. */
    static String validate(String name, int min, int max) {
        if (name == null || name.isBlank()) {
            return "Group name is required";
        }
        if (min < 0 || max < 1 || max < min) {
            return "Invalid min/max selections";
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
