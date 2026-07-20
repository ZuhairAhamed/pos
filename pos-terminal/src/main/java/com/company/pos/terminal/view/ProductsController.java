package com.company.pos.terminal.view;

import com.company.pos.terminal.api.CategoryView;
import com.company.pos.terminal.api.CreateProductRequest;
import com.company.pos.terminal.api.ProductAdminView;
import com.company.pos.terminal.api.UpdateProductRequest;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.ProductAdminViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * ADMIN-only product catalogue screen. Loads products + categories off the FX thread via FxTasks,
 * edits through the I/O-free {@link ProductFormDialog} (the controller performs the HTTP), and
 * reloads after every mutation. The include-inactive toggle filters the cached list client-side.
 */
public class ProductsController {

    private static final System.Logger LOG = System.getLogger(ProductsController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final ProductAdminViewModel vm;

    private List<ProductAdminView> allProducts = new ArrayList<>();
    private List<CategoryView> categories = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private CheckBox includeInactive;
    @FXML private Button backButton;
    @FXML private Button newButton;
    @FXML private Button editButton;
    @FXML private Button toggleActiveButton;
    @FXML private TableView<ProductAdminView> table;
    @FXML private TableColumn<ProductAdminView, String> skuCol;
    @FXML private TableColumn<ProductAdminView, String> nameCol;
    @FXML private TableColumn<ProductAdminView, String> categoryCol;
    @FXML private TableColumn<ProductAdminView, String> priceCol;
    @FXML private TableColumn<ProductAdminView, String> statusCol;

    public ProductsController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new ProductAdminViewModel(services.productAdminApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        skuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sku()));
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        categoryCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().categoryName() == null ? "" : c.getValue().categoryName()));
        priceCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().unitPrice() == null ? "" : c.getValue().unitPrice().toPlainString()));
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().active() ? "Active" : "Inactive"));

        errorLabel.textProperty().bind(vm.errorMessage());
        includeInactive.selectedProperty().addListener((o, a, b) -> applyFilter());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        newButton.setOnAction(e -> createProduct());
        editButton.setOnAction(e -> editSelected());
        toggleActiveButton.setOnAction(e -> toggleActiveSelected());

        refreshButtons(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        final List<ProductAdminView>[] ph = new List[1];
        final List<CategoryView>[] ch = new List[1];
        FxTasks.run(
                () -> {
                    ph[0] = vm.load();
                    ch[0] = vm.loadCategories();
                },
                () -> {
                    if (ph[0] != null) {
                        allProducts = ph[0];
                        applyFilter();
                    }
                    if (ch[0] != null) {
                        categories = ch[0];
                    }
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load products failed", err));
    }

    private void applyFilter() {
        boolean incl = includeInactive.isSelected();
        List<ProductAdminView> shown = allProducts.stream()
                .filter(p -> incl || p.active())
                .toList();
        table.setItems(FXCollections.observableArrayList(shown));
    }

    private void refreshButtons(ProductAdminView sel) {
        boolean has = sel != null;
        editButton.setDisable(!has);
        toggleActiveButton.setDisable(!has);
        toggleActiveButton.setText(has && !sel.active() ? "Reactivate" : "Deactivate");
    }

    private void createProduct() {
        Optional<CreateProductRequest> req = ProductFormDialog.promptCreate(categories);
        req.ifPresent(r -> {
            final ProductAdminView[] holder = new ProductAdminView[1];
            FxTasks.run(() -> holder[0] = vm.create(r),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Create product failed", err));
        });
    }

    private void editSelected() {
        ProductAdminView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Optional<UpdateProductRequest> req = ProductFormDialog.promptEdit(sel, categories);
        req.ifPresent(r -> {
            final ProductAdminView[] holder = new ProductAdminView[1];
            FxTasks.run(() -> holder[0] = vm.update(sel.sku(), r),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Update product failed", err));
        });
    }

    private void toggleActiveSelected() {
        ProductAdminView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        boolean reactivating = !sel.active();
        final boolean[] holder = {false};
        FxTasks.run(
                () -> holder[0] = reactivating ? vm.reactivate(sel.sku()) : vm.deactivate(sel.sku()),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Toggle active failed", err));
    }
}
