package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.SalePaymentView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.viewmodel.SplitViewModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Thin controller for the split-bill screen. Three phases on one scene (partition → tender →
 * result), swapped via visible/managed. All state lives in {@link SplitViewModel} (synchronous,
 * plain-field truth); this class only renders and dispatches VM calls off the FX thread via
 * {@link FxTasks}. Every amount shown in phase 2 comes from the quote-split response — the
 * screen computes no money beyond the cash-change preview difference the VM provides.
 */
public class SplitController {

    private static final System.Logger LOG = System.getLogger(SplitController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private final SplitViewModel vm;
    private MenuCache cache;

    @FXML private Label quoteBadge;
    @FXML private Button cancelButton;
    @FXML private VBox partitionBox;
    @FXML private ToggleButton byItemToggle;
    @FXML private ToggleButton evenToggle;
    @FXML private VBox byItemBox;
    @FXML private FlowPane guestTabs;
    @FXML private Button addGuestButton;
    @FXML private VBox lineRows;
    @FXML private Label unassignedLabel;
    @FXML private VBox evenBox;
    @FXML private Button waysMinusButton;
    @FXML private Label waysLabel;
    @FXML private Button waysPlusButton;
    @FXML private Button continueButton;
    @FXML private VBox tenderBox;
    @FXML private VBox guestRows;
    @FXML private Button backButton;
    @FXML private Button closeAllButton;
    @FXML private VBox resultBox;
    @FXML private VBox successBanner;
    @FXML private Label successCheck;
    @FXML private Label paidLabel;
    @FXML private VBox resultRows;
    @FXML private Button doneButton;
    @FXML private Label errorLabel;

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleGroup guestGroup = new ToggleGroup();

    public SplitController(Services services, Navigator navigator, UUID orderId) {
        this.services = services;
        this.navigator = navigator;
        this.orderId = orderId;
        this.vm = new SplitViewModel(services.diningApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        byItemToggle.setToggleGroup(modeGroup);
        evenToggle.setToggleGroup(modeGroup);
        // A ToggleGroup allows deselect-by-reclick; force one mode to stay selected.
        modeGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
                return;
            }
            vm.setMode(now == evenToggle ? SplitViewModel.EVEN : SplitViewModel.BY_ITEM);
            boolean even = now == evenToggle;
            byItemBox.setVisible(!even);
            byItemBox.setManaged(!even);
            evenBox.setVisible(even);
            evenBox.setManaged(even);
            updatePartitionState();
        });

        cancelButton.setOnAction(e -> navigator.toOrder(orderId));
        addGuestButton.setOnAction(e -> { if (vm.addGuest()) renderGuestTabs(); });
        waysMinusButton.setOnAction(e -> { if (vm.setWays(vm.ways() - 1)) updateWays(); });
        waysPlusButton.setOnAction(e -> { if (vm.setWays(vm.ways() + 1)) updateWays(); });
        continueButton.setOnAction(e -> quoteAndShowTender());
        backButton.setOnAction(e -> showPhase(partitionBox));
        closeAllButton.setOnAction(e -> closeAll());
        doneButton.setOnAction(e -> navigator.toTableMap());

        continueButton.setDisable(true);
        updateWays();

        // Catalog (for line names) + order lines, off the FX thread; then render phase 1.
        FxTasks.run(
                () -> {
                    cache = new MenuCache(services.productApi.list());
                    vm.load(orderId);
                },
                () -> {
                    renderGuestTabs();
                    renderLines();
                    updatePartitionState();
                },
                err -> {
                    LOG.log(System.Logger.Level.ERROR, "Failed to load order " + orderId, err);
                    vmSetError();
                });
    }

    private void vmSetError() {
        // Error label is bound to the VM; a load failure has no VM path, so unbind-free
        // fallback: navigate back to the order screen (the order screen re-fetches).
        navigator.toOrder(orderId);
    }

    // --- phase 1: partition ---

    private void renderGuestTabs() {
        guestTabs.getChildren().clear();
        for (int g = 0; g < vm.guestCount(); g++) {
            final int guest = g;
            ToggleButton tab = new ToggleButton("Guest " + (g + 1));
            tab.getStyleClass().add("guest-tab");
            tab.setToggleGroup(guestGroup);
            tab.setSelected(g == vm.activeGuest());
            tab.setOnAction(e -> {
                tab.setSelected(true);              // no deselect on re-tap
                vm.setActiveGuest(guest);
            });
            guestTabs.getChildren().add(tab);
        }
        addGuestButton.setDisable(vm.guestCount() >= 6);
    }

    private void renderLines() {
        lineRows.getChildren().clear();
        for (OrderLineView line : vm.lines()) {
            Button row = new Button();
            row.getStyleClass().add("split-line");
            row.setMaxWidth(Double.MAX_VALUE);
            Label name = new Label(qtyText(line.qty()) + " × " + cache.nameFor(line.sku()));
            Label badge = new Label();
            badge.getStyleClass().add("guest-badge");
            Integer guest = vm.guestOf(line.id());
            badge.setVisible(guest != null);
            if (guest != null) {
                badge.setText("Guest " + (guest + 1));
                row.getStyleClass().add("split-line-assigned");
            }
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox content = new HBox(8, name, gap, badge);
            content.setMaxWidth(Double.MAX_VALUE);
            row.setGraphic(content);
            row.setOnAction(e -> {
                vm.toggleAssign(line.id());
                renderLines();                       // re-render badges + assigned styling
                updatePartitionState();
            });
            lineRows.getChildren().add(row);
        }
    }

    private void updatePartitionState() {
        unassignedLabel.setText("Unassigned: " + vm.unassignedCount());
        continueButton.setDisable(!vm.canContinue());
        // Any partition/mode/ways change invalidated the quote — hide the stale badge.
        quoteBadge.setVisible(false);
        quoteBadge.setManaged(false);
    }

    private void updateWays() {
        waysLabel.setText(String.valueOf(vm.ways()));
        waysMinusButton.setDisable(vm.ways() <= 2);
        waysPlusButton.setDisable(vm.ways() >= 8);
        updatePartitionState();
    }

    // --- phase 2: tender ---

    /** Continue: fetch the authoritative quote-split off-thread; only on success move to the
     *  tender phase (the tender phase can never show a number the server hasn't confirmed). */
    private void quoteAndShowTender() {
        continueButton.setDisable(true);
        final boolean[] ok = new boolean[1];
        FxTasks.run(
                () -> ok[0] = vm.quoteSplit(orderId),
                () -> {
                    continueButton.setDisable(!vm.canContinue());
                    if (ok[0]) {
                        quoteBadge.setVisible(true);
                        quoteBadge.setManaged(true);
                        renderGuestRows();
                        showPhase(tenderBox);
                    }
                },
                err -> {
                    continueButton.setDisable(!vm.canContinue());
                    LOG.log(System.Logger.Level.ERROR, "quote-split failed", err);
                });
    }

    private void renderGuestRows() {
        guestRows.getChildren().clear();
        String cur = vm.currencyCode();
        for (int i = 0; i < vm.billCount(); i++) {
            final int bill = i;
            Label who = new Label(vm.billLabel(i));
            who.getStyleClass().add("pay-line-label");
            Label amount = new Label(money(vm.billAmount(i), cur));
            amount.getStyleClass().addAll("pay-grand-value", "money");

            ToggleGroup methodGroup = new ToggleGroup();
            ToggleButton cash = methodToggle("Cash", "CASH", methodGroup);
            ToggleButton card = methodToggle("Card", "CARD", methodGroup);
            ToggleButton wallet = methodToggle("Wallet", "WALLET", methodGroup);

            TextField tenderedField = new TextField();
            tenderedField.setPromptText("Cash tendered");
            tenderedField.getStyleClass().add("money");
            Label changeLabel = new Label();
            changeLabel.getStyleClass().addAll("pay-change-preview", "money");
            HBox cashRow = new HBox(8, tenderedField, changeLabel);
            cashRow.setVisible(false);
            cashRow.setManaged(false);
            Label exactHint = new Label("exact amount");
            exactHint.getStyleClass().add("estimate-line");
            exactHint.setVisible(false);
            exactHint.setManaged(false);

            tenderedField.textProperty().addListener((o, was, now) -> {
                vm.setCashTendered(bill, parseMoney(now));
                BigDecimal change = vm.changeFor(bill);
                changeLabel.setText(change.signum() >= 0 && parseMoney(now) != null
                        ? "Change: " + change.toPlainString() : "");
                updateCloseAllState();
            });
            methodGroup.selectedToggleProperty().addListener((o, was, now) -> {
                if (now == null && was != null) {
                    was.setSelected(true);          // keep one method selected once chosen
                    return;
                }
                String method = (String) now.getUserData();
                vm.setMethod(bill, method);
                boolean cashChosen = "CASH".equals(method);
                boolean withChange = cashChosen && vm.cashChangeAllowed();
                cashRow.setVisible(withChange);
                cashRow.setManaged(withChange);
                exactHint.setVisible(cashChosen && !vm.cashChangeAllowed());
                exactHint.setManaged(cashChosen && !vm.cashChangeAllowed());
                updateCloseAllState();
            });

            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox header = new HBox(12, who, gap, amount);
            HBox methodRow = new HBox(8, cash, card, wallet, exactHint);
            VBox row = new VBox(8, header, methodRow, cashRow);
            row.getStyleClass().add("split-amount-row");
            row.setMaxWidth(Double.MAX_VALUE);
            guestRows.getChildren().add(row);
        }
        updateCloseAllState();
    }

    private ToggleButton methodToggle(String text, String method, ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.setUserData(method);
        b.setToggleGroup(group);
        b.getStyleClass().add("mode-toggle");
        return b;
    }

    private void updateCloseAllState() {
        closeAllButton.setDisable(!vm.canCloseAll());
    }

    private void closeAll() {
        closeAllButton.setDisable(true);
        backButton.setDisable(true);
        final boolean[] ok = new boolean[1];
        FxTasks.run(
                () -> ok[0] = vm.closeAll(orderId),
                () -> {
                    backButton.setDisable(false);
                    if (ok[0]) {
                        showResults();
                    } else {
                        // Atomic failure: server rolled back everything; phase 2 stays editable.
                        updateCloseAllState();
                    }
                },
                err -> {
                    backButton.setDisable(false);
                    updateCloseAllState();
                    LOG.log(System.Logger.Level.ERROR, "close-split failed", err);
                });
    }

    // --- phase 3: results ---

    private void showResults() {
        List<SaleView> sales = vm.results();
        String cur = sales.isEmpty() ? "" : sales.get(0).currencyCode();
        BigDecimal paid = BigDecimal.ZERO;
        resultRows.getChildren().clear();
        List<String> rows = new ArrayList<>();
        if (sales.size() > 1 || SplitViewModel.BY_ITEM.equals(vm.mode())) {
            // BY_ITEM: one sale per guest, aligned with the bill order.
            for (int i = 0; i < sales.size(); i++) {
                SaleView s = sales.get(i);
                paid = paid.add(s.grandTotal());
                String change = changeText(s);
                rows.add(vm.billLabel(i) + " · Receipt " + s.receiptNumber()
                        + " · " + money(s.grandTotal(), cur) + change);
            }
        } else {
            // EVEN: one sale with one payment per guest (exact amounts, no change).
            SaleView s = sales.get(0);
            paid = s.grandTotal();
            for (int i = 0; i < s.payments().size(); i++) {
                SalePaymentView p = s.payments().get(i);
                rows.add("Guest " + (i + 1) + " · " + p.method() + " · " + money(p.amount(), cur));
            }
            rows.add("Receipt " + s.receiptNumber());
        }
        for (String text : rows) {
            Label row = new Label(text);
            row.getStyleClass().addAll("pay-line-value", "money");
            resultRows.getChildren().add(row);
        }
        paidLabel.setText("Paid · " + money(paid, cur));
        showPhase(resultBox);
        doneButton.setDefaultButton(true);
        if (!services.config.reducedMotion()) {
            ScaleTransition pop = new ScaleTransition(Duration.millis(200), successCheck);
            pop.setFromX(0.6);
            pop.setFromY(0.6);
            pop.setToX(1.0);
            pop.setToY(1.0);
            pop.play();
        }
    }

    private static String changeText(SaleView s) {
        BigDecimal change = BigDecimal.ZERO;
        if (s.payments() != null) {
            for (SalePaymentView p : s.payments()) {
                if (p.changeGiven() != null) {
                    change = change.add(p.changeGiven());
                }
            }
        }
        return change.signum() > 0 ? " · change " + change.toPlainString() : "";
    }

    private void showPhase(VBox phase) {
        for (VBox box : List.of(partitionBox, tenderBox, resultBox)) {
            boolean show = box == phase;
            box.setVisible(show);
            box.setManaged(show);
        }
    }

    private static String qtyText(BigDecimal qty) {
        return (qty == null ? BigDecimal.ZERO : qty).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String money(BigDecimal amount, String currency) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, RoundingMode.HALF_UP);
        return (value.toPlainString() + " " + currency).trim();
    }
}
