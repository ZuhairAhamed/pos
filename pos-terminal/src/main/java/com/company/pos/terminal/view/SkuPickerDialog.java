package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.viewmodel.ModifierRows;
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

/** Modal to pick a product SKU to assign a modifier group to. Pure view — the controller passes in
 *  the product list; returns the chosen sku. */
public final class SkuPickerDialog {

    private SkuPickerDialog() {
    }

    public static Optional<String> pickSku(List<ProductView> products) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Assign to product");
        dialog.setHeaderText("Choose a product");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Assign", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        List<ProductView> list = new ArrayList<>(products == null ? List.of() : products);
        ComboBox<ProductView> combo = new ComboBox<>(FXCollections.observableArrayList(list));
        combo.setPromptText("product");
        combo.setCellFactory(cb -> labelCell());
        combo.setButtonCell(labelCell());

        VBox box = new VBox(6, label("Product"), combo);
        box.setAlignment(Pos.TOP_LEFT);
        box.getStyleClass().add("field");
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(combo.getValue() == null);
        combo.valueProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || combo.getValue() == null) {
                return null;
            }
            return combo.getValue().sku();
        });
        return dialog.showAndWait();
    }

    private static javafx.scene.control.ListCell<ProductView> labelCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(ProductView item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : ModifierRows.skuLabel(item.sku(), java.util.List.of(item)));
            }
        };
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
        return l;
    }
}
