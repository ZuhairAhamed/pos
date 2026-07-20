package com.company.pos.terminal.view;

import com.company.pos.terminal.viewmodel.TableRows.TableRow;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal to create or edit a dining table. Pure view — collects input only; the controller performs
 * all HTTP. The Dine-in / Counter toggle reconciles the label with the takeaway prefix so the admin
 * never hand-types the magic string. Only {@link #normalizeLabel} is unit-tested headlessly.
 */
public final class TableFormDialog {

    private static final String DINE_IN = "Dine-in";
    private static final String COUNTER = "Counter";

    private TableFormDialog() {
    }

    public record Result(String label, Integer seats) {
    }

    public static Optional<Result> promptForTable(String counterPrefix, TableRow current) {
        boolean editing = current != null;
        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit table" : "New table");
        dialog.setHeaderText(editing ? "Edit " + current.label() : "Create a table");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField label = new TextField();
        label.setPromptText("label (e.g. T7)");
        if (editing) {
            label.setText(current.label());
        }

        Spinner<Integer> seats = new Spinner<>(1, 99, editing ? current.seats() : 2);
        seats.setEditable(true);

        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList(DINE_IN, COUNTER));
        type.setValue(editing && current.counter() ? COUNTER : DINE_IN);

        VBox box = new VBox(12, field("Label", label), field("Seats", seats), field("Type", type));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                normalizeLabel(label.getText(), COUNTER.equals(type.getValue()), counterPrefix) == null);
        label.textProperty().addListener((o, a, b) -> revalidate.run());
        type.valueProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit) {
                return null;
            }
            String finalLabel = normalizeLabel(label.getText(), COUNTER.equals(type.getValue()), counterPrefix);
            if (finalLabel == null) {
                return null;
            }
            return new Result(finalLabel, seats.getValue());
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Reconcile the typed label with the type toggle: a Counter carries the prefix exactly once, a
     *  Dine-in carries no prefix. Trims; returns null when blank (or when stripping leaves nothing). */
    static String normalizeLabel(String rawLabel, boolean counter, String prefix) {
        if (rawLabel == null) {
            return null;
        }
        String t = rawLabel.trim();
        if (t.isEmpty()) {
            return null;
        }
        boolean hasPrefix = prefix != null && !prefix.isBlank() && t.startsWith(prefix);
        if (counter) {
            if (prefix == null || prefix.isBlank()) {
                return t;
            }
            return hasPrefix ? t : prefix + t;
        }
        if (hasPrefix) {
            String base = t.substring(prefix.length()).trim();
            return base.isEmpty() ? null : base;
        }
        return t;
    }

    private static VBox field(String labelText, Node control) {
        Label l = new Label(labelText);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }
}
