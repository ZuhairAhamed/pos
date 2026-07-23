package com.company.pos.terminal.view;

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
 * Modal to create or rename a variant group. Pure view — collects name only.
 * No HTTP, no ViewModel import. Only {@link #validate} is unit-tested headlessly.
 */
public final class VariantGroupFormDialog {

    private VariantGroupFormDialog() {
    }

    public record GroupResult(String name) {
    }

    /**
     * {@code currentName} null on create; non-null on rename (pre-fills the field).
     */
    public static Optional<GroupResult> promptForGroup(String currentName) {
        boolean editing = currentName != null;
        Dialog<GroupResult> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit variant group" : "New variant group");
        dialog.setHeaderText(editing ? "Rename \"" + currentName + "\"" : "Create a variant group");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField nameField = new TextField();
        nameField.setPromptText("name (e.g. Sizes)");
        if (editing) {
            nameField.setText(currentName);
        }

        VBox box = new VBox(12, fieldBox("Name", nameField));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(validate(nameField.getText()) != null);
        nameField.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || validate(nameField.getText()) != null) {
                return null;
            }
            return new GroupResult(nameField.getText().trim());
        });
        return dialog.showAndWait();
    }

    /** Null when valid; non-null message when invalid. */
    static String validate(String name) {
        if (name == null || name.isBlank()) {
            return "Group name is required";
        }
        return null;
    }

    private static VBox fieldBox(String labelText, Node control) {
        Label l = new Label(labelText);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }
}
