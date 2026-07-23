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
 * Modal to collect a display label for a variant member. Used both on add-member (after
 * {@link SkuPickerDialog} has picked the SKU) and on relabel. Pure view — no HTTP, no ViewModel.
 * Only {@link #validate} is unit-tested headlessly.
 */
public final class VariantMemberFormDialog {

    private VariantMemberFormDialog() {
    }

    public record MemberResult(String displayLabel) {
    }

    /**
     * {@code currentLabel} null on add-member; non-null on relabel (pre-fills).
     */
    public static Optional<MemberResult> promptForLabel(String currentLabel) {
        boolean editing = currentLabel != null;
        Dialog<MemberResult> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit label" : "Member label");
        dialog.setHeaderText(editing ? "Relabel member" : "Set a display label for this member");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Add", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField labelField = new TextField();
        labelField.setPromptText("label (e.g. Small)");
        if (editing) {
            labelField.setText(currentLabel);
        }

        VBox box = new VBox(12, fieldBox("Display label", labelField));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(validate(labelField.getText()) != null);
        labelField.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || validate(labelField.getText()) != null) {
                return null;
            }
            return new MemberResult(labelField.getText().trim());
        });
        return dialog.showAndWait();
    }

    /** Null when valid; non-null message when invalid. */
    static String validate(String label) {
        if (label == null || label.isBlank()) {
            return "Display label is required";
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
