package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.TableView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/**
 * Modal free-table picker for moving an order. Lists each candidate table (label + seats) as a
 * full-width touch button; tapping one closes the dialog with that table's id. Pure view; the
 * caller performs the transfer. Display-dependent — exercised by the manual E2E.
 *
 * @return the chosen table id, or empty on cancel.
 */
public final class MoveTableDialog {

    private MoveTableDialog() {}

    public static Optional<UUID> promptForTarget(List<TableView> freeTables) {
        Dialog<UUID> dialog = new Dialog<>();
        dialog.setTitle("Move table");
        dialog.setHeaderText("Move order to which table?");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(cancel);
        dialog.getDialogPane().getStyleClass().add("move-dialog");

        VBox box = new VBox(8);
        box.getStyleClass().add("move-box");
        for (TableView t : freeTables) {
            Button b = new Button(t.label() + "  ·  " + t.seats() + " seats");
            b.getStyleClass().add("btn-secondary");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                dialog.setResult(t.id());
                dialog.close();
            });
            box.getChildren().add(b);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);

        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }
}
