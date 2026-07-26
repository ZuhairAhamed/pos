package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.CartLineModifierView;
import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.viewmodel.RetailViewModel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Thin controller for the retail sales screen. Binds FXML to a {@link RetailViewModel}
 * (cart on {@code Services.cartApi}) and a {@link MenuCache} (from {@code Services.productApi}).
 * Left/centre: a category-tabbed touch grid; right: the live cart with quantity steppers and the
 * pinned ink total bar that pulses amber on each item-add (static highlight when
 * {@code ui.reduced-motion} is set). Fast entry: a search box (server {@code ?q=}) and a barcode
 * box (client-side match). All VM calls run off the FX thread via {@link FxTasks}.
 */
public class RetailController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(RetailController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private RetailViewModel vm;
    private MenuCache cache;

    @FXML private TextField searchField;
    @FXML private TextField barcodeField;
    @FXML private Button homeButton;
    @FXML private Label errorLabel;
    @FXML private TabPane categoryTabs;
    @FXML private VBox cartLines;
    @FXML private Label emptyCartLabel;
    @FXML private HBox totalBar;
    @FXML private Label totalValue;
    @FXML private Button chargeButton;

    public RetailController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
    }

    @FXML
    public void initialize() {
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        homeButton.setOnAction(e -> navigator.toHome());
        chargeButton.setOnAction(e -> charge());
        chargeButton.setDisable(true);

        barcodeField.setOnAction(e -> onBarcode());
        searchField.setOnAction(e -> onSearch());

        FxTasks.run(
                () -> cache = new MenuCache(services.productApi.list()),
                this::afterCatalogLoaded,
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load catalog", err));
    }

    private void afterCatalogLoaded() {
        vm = new RetailViewModel(services.cartApi, cache, Platform::runLater);

        errorLabel.textProperty().bind(vm.errorMessage());
        totalValue.textProperty().bind(vm.subtotalText());
        vm.lines().addListener((ListChangeListener<CartLineView>) c -> renderCart());
        vm.itemAddedCount().addListener((o, was, now) -> pulseTotalBar());

        buildMenuFrom(cache);
        renderCart();

        FxTasks.run(
                () -> vm.start(),
                () -> chargeButton.setDisable(false),
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to create cart", err));
    }

    private void buildMenuFrom(MenuCache source) {
        categoryTabs.getTabs().clear();
        for (String category : source.categories()) {
            FlowPane grid = new FlowPane(12, 12);
            grid.getStyleClass().add("menu-grid");
            for (ProductView p : source.productsInCategory(category)) {
                Button b = new Button(p.name() + "\n" + priceText(p));
                b.getStyleClass().addAll("menu-button", categoryClass(category));
                b.setWrapText(true);
                boolean available = p.available() == null || p.available();
                b.setDisable(!available);
                if (!available) {
                    b.getStyleClass().add("product-unavailable");
                }
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

    /** Map a category name to one of the four semantic edge classes (else neutral). */
    private static String categoryClass(String category) {
        String c = category == null ? "" : category.toLowerCase();
        if (c.contains("drink")) return "cat-drinks";
        if (c.contains("food")) return "cat-food";
        if (c.contains("merch") || c.contains("retail")) return "cat-merch";
        if (c.contains("side")) return "cat-sides";
        return "cat-other";
    }

    private void addProduct(ProductView p) {
        FxTasks.run(
                () -> {
                    List<ModifierGroupView> groups = services.menuApi.modifierGroupsForSku(p.sku());
                    Platform.runLater(() -> addWithGroups(p, groups));
                },
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load modifiers for " + p.sku(), err));
    }

    private void addWithGroups(ProductView p, List<ModifierGroupView> groups) {
        List<UUID> optionIds = List.of();
        if (groups != null && !groups.isEmpty()) {
            Optional<List<UUID>> chosen = ModifierPickerDialog.pickFor(p.name(), groups);
            if (chosen.isEmpty()) {
                return;
            }
            optionIds = chosen.get();
        }
        final List<UUID> ids = optionIds;
        FxTasks.run(
                () -> vm.addBySku(p.sku(), BigDecimal.ONE, ids),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to add " + p.sku(), err));
    }

    private void onBarcode() {
        String code = barcodeField.getText();
        barcodeField.clear();
        FxTasks.run(
                () -> vm.addByBarcode(code),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to add by barcode", err));
    }

    /** Server search: rebuild the grid from the query hits (one "Results" tab). */
    private void onSearch() {
        String q = searchField.getText();
        if (q == null || q.isBlank()) {
            buildMenuFrom(cache);  // restore the immutable full-catalogue cache
            return;
        }
        FxTasks.run(
                () -> {
                    List<ProductView> hits = services.productApi.search(q);
                    MenuCache resultCache = new MenuCache(hits);
                    Platform.runLater(() -> buildMenuFrom(resultCache));  // never clobbers this.cache
                },
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Search failed", err));
    }

    private void renderCart() {
        cartLines.getChildren().clear();
        boolean empty = vm == null || vm.lines().isEmpty();
        emptyCartLabel.setVisible(empty);
        emptyCartLabel.setManaged(empty);
        if (empty) {
            return;
        }
        for (CartLineView line : vm.lines()) {
            cartLines.getChildren().add(cartRow(line));
        }
    }

    private HBox cartRow(CartLineView line) {
        Button minus = new Button("−");
        minus.getStyleClass().add("qty-stepper");
        minus.setOnAction(e -> step(line, -1));
        Label qty = new Label(qtyText(line.quantity()));
        qty.getStyleClass().add("money");
        Button plus = new Button("+");
        plus.getStyleClass().add("qty-stepper");
        plus.setOnAction(e -> step(line, +1));

        Label name = new Label(line.name() + modifierSuffix(line));
        name.setWrapText(true);
        Label lineTotal = new Label(lineTotalText(line));
        lineTotal.getStyleClass().add("money");

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, minus, qty, plus, name, spacer, lineTotal);
        row.getStyleClass().add("cart-line");
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private void step(CartLineView line, int delta) {
        BigDecimal current = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
        BigDecimal next = current.add(BigDecimal.valueOf(delta));
        FxTasks.run(
                () -> vm.updateQty(line, next),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to change quantity", err));
    }

    private void charge() {
        UUID cartId = vm.cartId();
        if (cartId == null || vm.lines().isEmpty()) {
            return;
        }
        navigator.toRetailPayment(cartId, vm.estimatedTotal());
    }

    /** Amber pulse on item-add; shorter hold in reduced-motion mode but still a per-add flash. */
    private void pulseTotalBar() {
        if (!totalBar.getStyleClass().contains("total-bar-pulse")) {
            totalBar.getStyleClass().add("total-bar-pulse");
        }
        // reduced-motion: shorter hold, no smooth animation, but still a per-add flash (not permanent).
        javafx.util.Duration hold = services.config.reducedMotion()
                ? javafx.util.Duration.millis(120) : javafx.util.Duration.millis(220);
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(hold);
        pause.setOnFinished(e -> totalBar.getStyleClass().remove("total-bar-pulse"));
        pause.play();
    }

    @Override
    public void onLeave() {
        // No polling timers on this screen; nothing to release. Present for symmetry/future use.
    }

    private static String priceText(ProductView p) {
        return (p.unitPrice() == null ? BigDecimal.ZERO : p.unitPrice()).toPlainString();
    }

    private static String qtyText(BigDecimal qty) {
        return (qty == null ? BigDecimal.ZERO : qty).stripTrailingZeros().toPlainString();
    }

    private static String lineTotalText(CartLineView line) {
        BigDecimal unit = line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice();
        BigDecimal qty = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
        return unit.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String modifierSuffix(CartLineView line) {
        if (line.modifiers() == null || line.modifiers().isEmpty()) {
            return "";
        }
        return "  (" + line.modifiers().stream()
                .map(CartLineModifierView::name).collect(Collectors.joining(", ")) + ")";
    }
}
