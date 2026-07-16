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
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Thin controller for the dine-in order screen. Binds the FXML controls to an {@link OrderViewModel}
 * (built from {@link Services#diningApi} + a {@link MenuCache}), renders the order lines as inline
 * editable rows and the client-side estimated subtotal on the left, and a touch menu grid (one tab
 * per {@code MenuCache.categories()} category) on the right. All (synchronous) VM/API calls are
 * dispatched off the FX thread via {@link FxTasks}; no business logic lives here.
 *
 * <p><b>Inline rows:</b> each un-fired line row has {@code −}/{@code +} quantity steppers (minus
 * disabled at qty 1 — use {@code ×} to remove), a course dropdown (STARTER/MAIN/DESSERT/DRINK), and
 * a {@code ×} remove button, mirroring the retail cart. A fired line (fired() == true) renders as a
 * muted, locked row with a {@code [fired]} badge and no edit controls; the VM refuses edits on fired
 * lines regardless, but the UI never even dispatches the call.
 *
 * <p><b>Estimate honesty:</b> the subtotal is {@code SubtotalCalculator} (pre-tax,
 * pre-service-charge) and is labelled "(est.)"; the authoritative total appears at payment.
 *
 * <p><b>Error handling:</b> {@code errorLabel.text} is bound to {@code vm.errorMessage()}, so
 * {@code FxTasks} {@code onError} callbacks must never {@code setText} it (that throws on a bound
 * property). Unexpected task failures are logged via {@link System.Logger}; VM-surfaced business
 * errors flow through the bound {@code errorMessage()} property.
 */
public class OrderController {

    private static final System.Logger LOG = System.getLogger(OrderController.class.getName());

    /** Course vocabulary — mirrors the server {@code CourseTag} enum. */
    private static final List<String> COURSES = List.of("STARTER", "MAIN", "DESSERT", "DRINK");

    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private OrderViewModel vm;
    private MenuCache cache;

    @FXML private VBox lineBox;
    @FXML private Label emptyLabel;
    @FXML private Label subtotalLabel;
    @FXML private Label errorLabel;
    @FXML private Button fireButton;
    @FXML private Button splitButton;
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
        // Error banner: hidden (and not laid out) when empty; text is bound in afterCatalogLoaded.
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        fireButton.setOnAction(e -> fire());
        splitButton.setOnAction(e -> navigator.toSplit(orderId));
        payButton.setOnAction(e -> pay());
        backButton.setOnAction(e -> navigator.toTableMap());

        // Nothing is actionable until the catalog + order have loaded.
        payButton.setDisable(true);
        fireButton.setDisable(true);
        splitButton.setDisable(true);

        // Load the product catalog into a MenuCache, off the FX thread; then wire the VM.
        FxTasks.run(
                () -> cache = new MenuCache(services.productApi.list()),
                this::afterCatalogLoaded,
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load catalog", err));
    }

    private void afterCatalogLoaded() {
        vm = new OrderViewModel(services.diningApi, cache, Platform::runLater);

        subtotalLabel
                .textProperty()
                .bind(Bindings.concat("Subtotal (est.): ", vm.subtotalText()));
        errorLabel.textProperty().bind(vm.errorMessage());
        vm.lines()
                .addListener((ListChangeListener<OrderLineView>) c -> {
                    renderLines();
                    splitButton.setDisable(vm.lines().isEmpty());
                });

        buildMenu();

        payButton.setDisable(false);
        fireButton.setDisable(false);

        FxTasks.run(
                () -> vm.load(orderId),
                () -> {
                    renderLines();
                    splitButton.setDisable(vm.lines().isEmpty());
                },
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

    /** Rebuilds the line rows from the VM. Un-fired lines are editable; fired lines are locked. */
    private void renderLines() {
        lineBox.getChildren().clear();
        boolean empty = vm == null || vm.lines().isEmpty();
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
        if (empty) {
            return;
        }
        for (OrderLineView line : vm.lines()) {
            lineBox.getChildren().add(lineRow(line));
        }
    }

    private HBox lineRow(OrderLineView line) {
        Label name = new Label(cache.nameFor(line.sku()) + subText(line));
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        if (line.fired()) {
            Label badge = new Label("[fired]");
            badge.getStyleClass().add("order-line-fired");
            HBox row = new HBox(8, name, badge);
            row.getStyleClass().addAll("cart-line", "order-line-fired");
            row.setMaxWidth(Double.MAX_VALUE);
            return row;
        }

        Button minus = new Button("−"); // − minus sign
        minus.getStyleClass().add("qty-stepper");
        minus.setDisable(line.qty() == null || line.qty().compareTo(BigDecimal.ONE) <= 0);
        minus.setOnAction(e -> step(line, -1));

        Label qty = new Label(qtyText(line.qty()));
        qty.getStyleClass().add("money");

        Button plus = new Button("+");
        plus.getStyleClass().add("qty-stepper");
        plus.setOnAction(e -> step(line, +1));

        // Course dropdown. Set the value BEFORE the action handler so the initial setValue does not
        // fire a spurious server call; a re-render rebuilds a fresh combo, so no update loop.
        ComboBox<String> course = new ComboBox<>(FXCollections.observableArrayList(COURSES));
        course.setValue(line.course() == null ? "MAIN" : line.course());
        course.getStyleClass().add("course-chip");
        course.setOnAction(e -> changeCourse(line, course.getValue()));

        Button remove = new Button("×"); // × multiplication sign
        remove.getStyleClass().add("line-remove");
        remove.setOnAction(e -> remove(line));

        HBox row = new HBox(8, minus, qty, plus, name, course, remove);
        row.getStyleClass().add("cart-line");
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private void step(OrderLineView line, int delta) {
        BigDecimal current = line.qty() == null ? BigDecimal.ZERO : line.qty();
        BigDecimal next = current.add(BigDecimal.valueOf(delta));
        if (next.compareTo(BigDecimal.ONE) < 0) {
            return; // floor at 1; removing a line is the explicit × button
        }
        FxTasks.run(
                () -> vm.updateQty(line, next),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to change quantity", err));
    }

    private void changeCourse(OrderLineView line, String course) {
        FxTasks.run(
                () -> vm.updateCourse(line, course),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to change course", err));
    }

    private void remove(OrderLineView line) {
        FxTasks.run(
                () -> vm.removeLine(line),
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

    private static String qtyText(BigDecimal qty) {
        if (qty == null) {
            return "0";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    /** Modifier names and an optional note, each on its own indented sub-line under the product. */
    private static String subText(OrderLineView line) {
        StringBuilder sb = new StringBuilder();
        String mods = modifierText(line);
        if (!mods.isEmpty()) {
            sb.append("\n   ").append(mods);
        }
        if (line.note() != null && !line.note().isBlank()) {
            sb.append("\n   note: ").append(line.note());
        }
        return sb.toString();
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
