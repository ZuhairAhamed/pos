package com.company.pos.terminal.view;

import com.company.pos.terminal.api.CategoryView;
import com.company.pos.terminal.api.CreateProductRequest;
import com.company.pos.terminal.api.ProductAdminView;
import com.company.pos.terminal.api.UpdateProductRequest;
import java.math.BigDecimal;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal to create or edit a product. Pure view — collects input only; the controller performs all
 * HTTP and passes in the category list. Create returns a {@link CreateProductRequest}; edit returns
 * an {@link UpdateProductRequest} (SKU is immutable and not shown). Only {@link #isValidCreate},
 * {@link #isValidPrice} and {@link #parsePrice} are unit-tested headlessly.
 */
public final class ProductFormDialog {

    private static final String NEW_CATEGORY = "➕ New category…";

    private ProductFormDialog() {}

    public static Optional<CreateProductRequest> promptCreate(List<CategoryView> categories) {
        Dialog<CreateProductRequest> dialog = new Dialog<>();
        dialog.setTitle("New product");
        dialog.setHeaderText("Create a product");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = configureButtons(dialog, "Create");

        TextField sku = textField("SKU");
        TextField name = textField("name");
        TextField price = textField("price");
        TextField uom = textField("unit (e.g. EA)");
        uom.setText("EA");
        TextField barcode = textField("barcode (optional)");
        ComboBox<String> category = categoryCombo(categories, null);
        TextField newCategory = textField("new category name");
        VBox newCategoryField = field("New category", newCategory);
        bindNewCategoryVisibility(category, newCategoryField);

        VBox box = new VBox(12,
                field("SKU", sku),
                field("Name", name),
                field("Price", price),
                field("Category", category),
                newCategoryField,
                field("Unit of measure", uom),
                field("Barcode", barcode));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                !isValidCreate(sku.getText(), name.getText(), price.getText()));
        sku.textProperty().addListener((o, a, b) -> revalidate.run());
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        price.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || !isValidCreate(sku.getText(), name.getText(), price.getText())) {
                return null;
            }
            CategoryChoice cc = categoryChoice(category, newCategory, categories);
            return new CreateProductRequest(sku.getText().trim(), name.getText().trim(),
                    cc.code(), cc.name(), parsePrice(price.getText()),
                    null, trimToNull(uom.getText()), trimToNull(barcode.getText()));
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    public static Optional<UpdateProductRequest> promptEdit(ProductAdminView existing,
            List<CategoryView> categories) {
        Dialog<UpdateProductRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit product");
        dialog.setHeaderText("Edit " + existing.sku());
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = configureButtons(dialog, "Save");

        TextField name = textField("name");
        name.setText(existing.name());
        TextField price = textField("price");
        price.setText(existing.unitPrice() == null ? "" : existing.unitPrice().toPlainString());
        TextField uom = textField("unit (e.g. EA)");
        uom.setText(existing.unitOfMeasure() == null ? "" : existing.unitOfMeasure());
        TextField barcode = textField("barcode (optional)");
        barcode.setText(existing.barcode() == null ? "" : existing.barcode());
        ComboBox<String> category = categoryCombo(categories, existing.categoryName());
        TextField newCategory = textField("new category name");
        VBox newCategoryField = field("New category", newCategory);
        bindNewCategoryVisibility(category, newCategoryField);

        VBox box = new VBox(12,
                field("Name", name),
                field("Price", price),
                field("Category", category),
                newCategoryField,
                field("Unit of measure", uom),
                field("Barcode", barcode));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                name.getText().isBlank() || !isValidPrice(price.getText()));
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        price.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || name.getText().isBlank() || !isValidPrice(price.getText())) {
                return null;
            }
            CategoryChoice cc = categoryChoice(category, newCategory, categories);
            return new UpdateProductRequest(name.getText().trim(), cc.code(), cc.name(),
                    parsePrice(price.getText()), existing.currencyCode(),
                    trimToNull(uom.getText()), trimToNull(barcode.getText()));
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Create validity: SKU + name non-blank and a valid non-negative price. */
    static boolean isValidCreate(String sku, String name, String price) {
        return sku != null && !sku.isBlank()
                && name != null && !name.isBlank()
                && isValidPrice(price);
    }

    static boolean isValidPrice(String price) {
        BigDecimal p = parsePrice(price);
        return p != null && p.signum() >= 0;
    }

    /** Parse a price string to BigDecimal, or null if blank/unparseable. */
    static BigDecimal parsePrice(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record CategoryChoice(String code, String name) {}

    private static CategoryChoice categoryChoice(ComboBox<String> combo, TextField newCategory,
            List<CategoryView> categories) {
        String sel = combo.getValue();
        if (sel == null || sel.isBlank()) {
            return new CategoryChoice(null, null);
        }
        if (NEW_CATEGORY.equals(sel)) {
            return new CategoryChoice(null, trimToNull(newCategory.getText()));
        }
        // Existing category selected by name → send its code so the server reuses it (no derivation).
        if (categories != null) {
            for (CategoryView c : categories) {
                if (c.name().equals(sel)) {
                    return new CategoryChoice(c.code(), null);
                }
            }
        }
        return new CategoryChoice(null, trimToNull(sel));
    }

    private static ComboBox<String> categoryCombo(List<CategoryView> categories, String selectedName) {
        List<String> names = new ArrayList<>();
        if (categories != null) {
            for (CategoryView c : categories) {
                names.add(c.name());
            }
        }
        names.add(NEW_CATEGORY);
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(names));
        combo.setPromptText("(none)");
        if (selectedName != null && names.contains(selectedName)) {
            combo.setValue(selectedName);
        }
        return combo;
    }

    private static void bindNewCategoryVisibility(ComboBox<String> combo, VBox newCategoryField) {
        Runnable apply = () -> {
            boolean show = NEW_CATEGORY.equals(combo.getValue());
            newCategoryField.setVisible(show);
            newCategoryField.setManaged(show);
        };
        combo.valueProperty().addListener((o, a, b) -> apply.run());
        apply.run();
    }

    private static <T> ButtonType configureButtons(Dialog<T> dialog, String submitLabel) {
        ButtonType submit = new ButtonType(submitLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);
        return submit;
    }

    private static TextField textField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        return f;
    }

    private static VBox field(String label, Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
