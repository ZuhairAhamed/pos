package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.ModifierOptionView;
import com.company.pos.terminal.order.ModifierSelectionValidator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/**
 * Modal modifier picker for a product with one or more {@link ModifierGroupView modifier groups}.
 * Each group is rendered as a labelled section (with a "required"/"optional" hint) whose options are
 * large toggle buttons showing the option name and its {@code priceDelta}. The chosen option-id set
 * is live-validated against {@link ModifierSelectionValidator} on every toggle; the confirm ("Add")
 * button is disabled until every forced group is satisfied and no group exceeds its maximum. The
 * server re-validates authoritatively on {@code addLine} — this only gives fast local feedback.
 *
 * <p>The dialog is a pure view concern with no server access. It is display-dependent (constructs a
 * JavaFX {@link Dialog}) and is therefore exercised only by the Task 14 manual E2E, never a headless
 * unit test.
 *
 * @return in {@link #pickFor} an {@code Optional} of the chosen option ids in selection order; an
 *     empty optional means the picker was cancelled (the caller must not add the line).
 */
public final class ModifierPickerDialog {

    private ModifierPickerDialog() {}

    public static Optional<List<UUID>> pickFor(String productName, List<ModifierGroupView> groups) {
        Dialog<List<UUID>> dialog = new Dialog<>();
        dialog.setTitle("Options");
        dialog.setHeaderText("Choose options for " + productName);
        ButtonType ok = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.getDialogPane().getStyleClass().add("modifier-dialog");

        // Selection order is preserved so the added line lists options as the user picked them.
        Set<UUID> selected = new LinkedHashSet<>();

        VBox box = new VBox(16);
        box.getStyleClass().add("modifier-box");
        for (ModifierGroupView g : groups) {
            Label groupLabel = new Label(g.name() + (isForced(g) ? "  (required)" : "  (optional)"));
            groupLabel.getStyleClass().add("modifier-group-label");
            box.getChildren().add(groupLabel);

            for (ModifierOptionView opt : g.options()) {
                CheckBox cb = new CheckBox(labelFor(opt));
                cb.getStyleClass().add("modifier-option");
                cb.selectedProperty()
                        .addListener(
                                (obs, was, now) -> {
                                    if (now) {
                                        selected.add(opt.id());
                                    } else {
                                        selected.remove(opt.id());
                                    }
                                });
                box.getChildren().add(cb);
            }
        }

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);

        // Live-validate: disable "Add" until the selection satisfies every group.
        javafx.scene.Node okNode = dialog.getDialogPane().lookupButton(ok);
        Runnable revalidate = () -> okNode.setDisable(ModifierSelectionValidator.validate(groups, selected) != null);
        // Re-run validation whenever any checkbox toggles.
        for (javafx.scene.Node node : box.getChildren()) {
            if (node instanceof CheckBox cb) {
                cb.selectedProperty().addListener((obs, was, now) -> revalidate.run());
            }
        }
        revalidate.run(); // initial state (forced groups start invalid)

        dialog.setResultConverter(bt -> bt == ok ? new ArrayList<>(selected) : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** A group is forced when at least one selection is mandatory. */
    private static boolean isForced(ModifierGroupView g) {
        return g.minSelections() >= 1;
    }

    private static String labelFor(ModifierOptionView opt) {
        if (opt.priceDelta() == null || opt.priceDelta().signum() == 0) {
            return opt.name();
        }
        String sign = opt.priceDelta().signum() > 0 ? "+" : "";
        return opt.name() + "   " + sign + opt.priceDelta().toPlainString();
    }
}
