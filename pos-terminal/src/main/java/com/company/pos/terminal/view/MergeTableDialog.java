package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.OpenOrderView;
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
 * Modal occupied-table picker for merging. Lists each candidate open order (its table label + line
 * count) as a full-width touch button; tapping one closes the dialog with that order's id. Pure
 * view; the caller performs the merge. Display-dependent — exercised by the manual E2E.
 *
 * @return the chosen order id, or empty on cancel.
 */
public final class MergeTableDialog {

    private MergeTableDialog() {}

    public static Optional<UUID> promptForTarget(List<OpenOrderView> targets) {
        Dialog<UUID> dialog = new Dialog<>();
        dialog.setTitle("Merge tables");
        dialog.setHeaderText("Merge which table into this order?");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(cancel);
        dialog.getDialogPane().getStyleClass().add("merge-dialog");

        VBox box = new VBox(8);
        box.getStyleClass().add("merge-box");
        for (OpenOrderView t : targets) {
            String items = t.lineCount() == 1 ? "1 item" : t.lineCount() + " items";
            Button b = new Button(t.tableLabel() + "  ·  " + items);
            b.getStyleClass().add("btn-secondary");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                dialog.setResult(t.orderId());
                dialog.close();
            });
            box.getChildren().add(b);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);

        return dialog.showAndWait();
    }
}
