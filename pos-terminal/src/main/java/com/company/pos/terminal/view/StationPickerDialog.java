package com.company.pos.terminal.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Modal to choose a kitchen station for a product. Pure view — collects input only; the controller
 * performs all HTTP. An editable combo pre-populated with the station names already in use (pick an
 * existing one to avoid typo-duplicates, or type a new one). Only {@link #normalize} is unit-tested.
 */
public final class StationPickerDialog {

    private StationPickerDialog() {
    }

    public static Optional<String> promptForStation(List<String> existingStations, String current) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Kitchen station");
        dialog.setHeaderText("Route to station");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        List<String> names = new ArrayList<>(existingStations == null ? List.of() : existingStations);
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(names));
        combo.setEditable(true);
        combo.setPromptText("station name");
        if (current != null && !current.isBlank()) {
            combo.setValue(current);
        }

        Label label = new Label("Station");
        label.getStyleClass().add("field-label");
        VBox box = new VBox(6, label, combo);
        box.getStyleClass().add("field");
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(normalize(combo.getEditor().getText()) == null);
        combo.getEditor().textProperty().addListener((o, a, b) -> revalidate.run());
        combo.valueProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == submit ? normalize(combo.getEditor().getText()) : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Trim the entered station; null if blank. */
    static String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
