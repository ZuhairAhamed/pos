package com.company.pos.terminal.view;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.RealtimeClient;
import com.company.pos.terminal.api.dto.ManagerAuth;
import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderLineModifierView;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.api.dto.TableView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.order.MergeTargets;
import com.company.pos.terminal.order.MoveTargets;
import com.company.pos.terminal.order.SubtotalCalculator;
import com.company.pos.terminal.viewmodel.OrderViewModel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
public class OrderController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(OrderController.class.getName());

    /** Course vocabulary — mirrors the server {@code CourseTag} enum. */
    private static final List<String> COURSES = List.of("STARTER", "MAIN", "DESSERT", "DRINK");

    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private OrderViewModel vm;
    private MenuCache cache;
    private RealtimeClient realtime;
    private final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private volatile boolean stale;

    @FXML private VBox lineBox;
    @FXML private Label emptyLabel;
    @FXML private Label subtotalLabel;
    @FXML private Label errorLabel;
    @FXML private Label staleBanner;
    @FXML private Button fireButton;
    @FXML private Button splitButton;
    @FXML private Button payButton;
    @FXML private Button backButton;
    @FXML private Button voidButton;
    @FXML private Button moveButton;
    @FXML private Button mergeButton;
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
        voidButton.setOnAction(e -> voidOrder());
        voidButton.setDisable(true);
        moveButton.setOnAction(e -> moveTable());
        moveButton.setDisable(true);
        mergeButton.setOnAction(e -> mergeTable());
        mergeButton.setDisable(true);

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
        voidButton.setDisable(false);
        moveButton.setDisable(false);
        mergeButton.setDisable(false);

        FxTasks.run(
                () -> vm.load(orderId),
                () -> {
                    renderLines();
                    splitButton.setDisable(vm.lines().isEmpty());
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load order " + orderId, err));

        realtime = services.newRealtimeClient();
        realtime.connect(this::onFloorPing);
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

    /** Void the whole order: confirm (+optional reason) → manager PIN → one-shot void → tables. */
    private void voidOrder() {
        Optional<String> reason = VoidConfirmDialog.promptForReason();
        if (reason.isEmpty()) {
            return;
        }
        Optional<ManagerPinDialog.Credentials> creds =
                ManagerPinDialog.promptForApproval("Manager approval required to void this order");
        if (creds.isEmpty()) {
            return;
        }
        String r = reason.get();
        ManagerPinDialog.Credentials c = creds.get();
        boolean[] holder = {false};
        FxTasks.run(
                () -> {
                    ManagerAuth auth = services.authApi.pinLoginForToken(c.cashierCode(), c.pin());
                    if (!auth.isManager()) {
                        throw new ApiException(403, null, "This account is not a manager");
                    }
                    holder[0] = vm.voidOrder(r, auth.token());
                },
                () -> {
                    if (holder[0]) {
                        navigator.toTableMap();
                    }
                },
                err -> {
                    String msg = err.getMessage();
                    vm.setError(msg == null || msg.isBlank() ? "Manager approval failed" : msg);
                    LOG.log(System.Logger.Level.ERROR, "Void approval failed", err);
                });
    }

    /** Move the order to another table: fetch free tables → pick one → transfer → tables. */
    private void moveTable() {
        AtomicReference<List<TableView>> free = new AtomicReference<>();
        FxTasks.run(
                () -> {
                    List<TableView> tables = services.diningApi.tables();
                    List<OpenOrderView> open = services.diningApi.openOrders();
                    free.set(MoveTargets.freeTargets(tables, open, vm.currentOrder().tableId()));
                },
                () -> {
                    List<TableView> targets = free.get();
                    if (targets.isEmpty()) {
                        vm.setError("No free tables available");
                        return;
                    }
                    Optional<UUID> target = MoveTableDialog.promptForTarget(targets);
                    if (target.isEmpty()) {
                        return;
                    }
                    boolean[] holder = {false};
                    FxTasks.run(
                            () -> holder[0] = vm.transfer(target.get()),
                            () -> {
                                if (holder[0]) {
                                    navigator.toTableMap();
                                }
                            },
                            err -> LOG.log(System.Logger.Level.ERROR, "Failed to transfer order", err));
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load tables for move", err));
    }

    /** Merge another occupied table's order into this one: fetch targets → pick → merge → reload. */
    private void mergeTable() {
        AtomicReference<List<OpenOrderView>> candidates = new AtomicReference<>();
        FxTasks.run(
                () -> {
                    List<TableView> tables = services.diningApi.tables();
                    List<OpenOrderView> open = services.diningApi.openOrders();
                    candidates.set(MergeTargets.occupiedTargets(tables, open, vm.currentOrder().tableId()));
                },
                () -> {
                    List<OpenOrderView> targets = candidates.get();
                    if (targets.isEmpty()) {
                        vm.setError("No other occupied tables to merge");
                        return;
                    }
                    Optional<UUID> absorbed = MergeTableDialog.promptForTarget(targets);
                    if (absorbed.isEmpty()) {
                        return;
                    }
                    FxTasks.run(
                            () -> {
                                if (vm.merge(absorbed.get())) {
                                    vm.load(orderId);
                                }
                            },
                            () -> {},
                            err -> LOG.log(System.Logger.Level.ERROR, "Failed to merge orders", err));
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load orders for merge", err));
    }

    /** A floor ping arrived (on the WebSocket thread): re-check THIS order's status off the FX
     *  thread. Coalesced — at most one check in flight, and none once the screen is stale. */
    private void onFloorPing() {
        if (stale || !checkInFlight.compareAndSet(false, true)) {
            return;
        }
        OrderViewModel.RecheckResult[] holder = { OrderViewModel.RecheckResult.UNKNOWN };
        FxTasks.run(
                () -> holder[0] = vm.recheck(),
                () -> {
                    checkInFlight.set(false);
                    if (holder[0] == OrderViewModel.RecheckResult.STALE) {
                        lockStale();
                    }
                },
                err -> {
                    checkInFlight.set(false);
                    LOG.log(System.Logger.Level.ERROR, "Order re-check failed", err);
                });
    }

    /** Lock every editing control; keep "Back to tables" live; show the stale banner. */
    private void lockStale() {
        stale = true;
        lineBox.setDisable(true);
        categoryTabs.setDisable(true);
        fireButton.setDisable(true);
        voidButton.setDisable(true);
        moveButton.setDisable(true);
        mergeButton.setDisable(true);
        splitButton.setDisable(true);
        payButton.setDisable(true);
        String status = vm.currentOrder() == null ? null : vm.currentOrder().status();
        staleBanner.setText(staleMessage(status));
        staleBanner.setManaged(true);
        staleBanner.setVisible(true);
    }

    private static String staleMessage(String status) {
        String what = "changed";
        if ("VOIDED".equals(status)) {
            what = "voided";
        } else if ("CLOSED".equals(status)) {
            what = "paid/closed";
        }
        return "This order was " + what + " on another terminal. Return to tables.";
    }

    @Override
    public void onLeave() {
        if (realtime != null) {
            realtime.close();
        }
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
