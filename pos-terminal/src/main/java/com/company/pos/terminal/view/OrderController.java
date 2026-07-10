package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.OrderLineModifierView;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.order.SubtotalCalculator;
import com.company.pos.terminal.viewmodel.OrderViewModel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.FlowPane;

/**
 * Thin controller for the dine-in order screen. It binds the FXML controls to an
 * {@link OrderViewModel} (built from {@link Services#diningApi} + a {@link MenuCache}), renders the
 * order lines and the client-side estimated subtotal on the left, and a touch menu grid (one tab per
 * {@code MenuCache.categories()} category) on the right. All (synchronous) VM/API calls are dispatched
 * off the FX thread via {@link FxTasks}; no business logic lives here.
 *
 * <p><b>Fired-line lock:</b> a line whose {@code fired()} is true is visibly badged {@code [fired]} in
 * its cell and its edit path is closed — {@code Remove line} is disabled whenever the selected line is
 * fired ({@code !vm.canEdit}). The VM refuses edits on fired lines regardless, but the UI never even
 * dispatches the call.
 *
 * <p><b>Estimate honesty:</b> the subtotal is {@code SubtotalCalculator} (pre-tax, pre-service-charge)
 * and is labelled "(est.)"; the authoritative total appears at payment.
 *
 * <p><b>Error handling:</b> {@code errorLabel.text} is bound to {@code vm.errorMessage()}, so
 * {@code FxTasks} {@code onError} callbacks must never {@code setText} it (that throws on a bound
 * property). Unexpected task failures are logged via {@link System.Logger}; VM-surfaced business
 * errors flow through the bound {@code errorMessage()} property.
 */
public class OrderController {

    private static final System.Logger LOG = System.getLogger(OrderController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private OrderViewModel vm;
    private MenuCache cache;

    @FXML private ListView<OrderLineView> lineList;
    @FXML private Label subtotalLabel;
    @FXML private Label errorLabel;
    @FXML private Button removeButton;
    @FXML private Button fireButton;
    @FXML private Button payButton;
    @FXML private Button backButton;
    @FXML private TabPane categoryTabs;

    public OrderController(Services services, Navigator navigator, UUID orderId) {
        this.services = services;
        this.navigator = navigator;
        this.orderId = orderId;
    }

    @FXML
    public void initialize() {
        lineList.setCellFactory(v -> new OrderLineCell());

        // Error banner: hidden (and not laid out) when empty; text is bound in afterCatalogLoaded.
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        removeButton.setOnAction(e -> removeSelected());
        fireButton.setOnAction(e -> fire());
        payButton.setOnAction(e -> pay());
        backButton.setOnAction(e -> navigator.toTableMap());

        // Remove is disabled unless a currently-editable (un-fired) line is selected.
        removeButton.setDisable(true);
        lineList.getSelectionModel()
                .selectedItemProperty()
                .addListener((obs, was, now) -> removeButton.setDisable(now == null || now.fired()));

        // Nothing is actionable until the catalog + order have loaded.
        payButton.setDisable(true);
        fireButton.setDisable(true);

        // Load the product catalog into a MenuCache, off the FX thread; then wire the VM.
        FxTasks.run(
                () -> cache = new MenuCache(services.productApi.list()),
                this::afterCatalogLoaded,
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load catalog", err));
    }

    private void afterCatalogLoaded() {
        vm = new OrderViewModel(services.diningApi, cache);

        subtotalLabel
                .textProperty()
                .bind(Bindings.concat("Subtotal (est.): ", vm.subtotalText()));
        errorLabel.textProperty().bind(vm.errorMessage());
        vm.lines()
                .addListener((ListChangeListener<OrderLineView>) c -> lineList.getItems().setAll(vm.lines()));

        buildMenu();

        payButton.setDisable(false);
        fireButton.setDisable(false);

        FxTasks.run(
                () -> vm.load(orderId),
                () -> lineList.getItems().setAll(vm.lines()),
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load order " + orderId, err));
    }

    /** One tab per category (MenuCache already folds null/blank categories into "Other"). */
    private void buildMenu() {
        categoryTabs.getTabs().clear();
        for (String category : cache.categories()) {
            FlowPane grid = new FlowPane(12, 12);
            grid.getStyleClass().add("menu-grid");
            for (ProductView p : cache.productsInCategory(category)) {
                Button b = new Button(p.name() + "\n" + priceText(p));
                b.getStyleClass().add("menu-button");
                b.setWrapText(true);
                b.setOnAction(e -> addProduct(p));
                grid.getChildren().add(b);
            }
            ScrollPane scroll = new ScrollPane(grid);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("menu-scroll");
            Tab tab = new Tab(category, scroll);
            tab.setClosable(false);
            categoryTabs.getTabs().add(tab);
        }
    }

    /** Tap a product: fetch its modifier groups off-thread, then open the picker or add directly. */
    private void addProduct(ProductView p) {
        FxTasks.run(
                () -> {
                    List<ModifierGroupView> groups = services.menuApi.modifierGroupsForSku(p.sku());
                    Platform.runLater(() -> addWithGroups(p, groups));
                },
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load modifiers for " + p.sku(), err));
    }

    /** On the FX thread: open the picker if the product has groups, else add with no modifiers. */
    private void addWithGroups(ProductView p, List<ModifierGroupView> groups) {
        List<UUID> optionIds = List.of();
        if (groups != null && !groups.isEmpty()) {
            Optional<List<UUID>> chosen = ModifierPickerDialog.pickFor(p.name(), groups);
            if (chosen.isEmpty()) {
                return; // cancelled — do not add
            }
            optionIds = chosen.get();
        }
        final List<UUID> ids = optionIds;
        FxTasks.run(
                () -> vm.addLine(p.sku(), BigDecimal.ONE, null, "MAIN", ids),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to add line " + p.sku(), err));
    }

    private void removeSelected() {
        OrderLineView sel = lineList.getSelectionModel().getSelectedItem();
        if (sel == null || sel.fired()) {
            return; // fired lines are locked; VM would refuse anyway
        }
        FxTasks.run(
                () -> vm.removeLine(sel),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to remove line", err));
    }

    private void fire() {
        FxTasks.run(
                vm::fire,
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to fire order", err));
    }

    private void pay() {
        BigDecimal estimatedTotal = SubtotalCalculator.estimate(vm.currentOrder(), cache);
        navigator.toPayment(orderId, estimatedTotal);
    }

    private static String priceText(ProductView p) {
        BigDecimal price = p.unitPrice() == null ? BigDecimal.ZERO : p.unitPrice();
        return price.toPlainString();
    }

    /**
     * Renders one order line: {@code qty × name}, modifier names as sub-text, an optional note, and a
     * {@code [fired]} badge (via the {@code order-line-fired} style class) when locked.
     */
    private final class OrderLineCell extends ListCell<OrderLineView> {
        @Override
        protected void updateItem(OrderLineView line, boolean empty) {
            super.updateItem(line, empty);
            getStyleClass().remove("order-line-fired");
            if (empty || line == null) {
                setText(null);
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(qtyText(line.qty())).append(" × ").append(nameFor(line.sku()));
            if (line.fired()) {
                sb.append("   [fired]");
                getStyleClass().add("order-line-fired");
            }
            String mods = modifierText(line);
            if (!mods.isEmpty()) {
                sb.append("\n   ").append(mods);
            }
            if (line.note() != null && !line.note().isBlank()) {
                sb.append("\n   note: ").append(line.note());
            }
            setText(sb.toString());
        }
    }

    private String nameFor(String sku) {
        for (String cat : cache.categories()) {
            for (ProductView p : cache.productsInCategory(cat)) {
                if (p.sku().equals(sku)) {
                    return p.name();
                }
            }
        }
        return sku;
    }

    private static String qtyText(BigDecimal qty) {
        if (qty == null) {
            return "0";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    private static String modifierText(OrderLineView line) {
        if (line.modifiers() == null || line.modifiers().isEmpty()) {
            return "";
        }
        return line.modifiers().stream()
                .filter(m -> m != null && m.name() != null)
                .map(OrderLineModifierView::name)
                .collect(Collectors.joining(", "));
    }
}
