package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.CheckoutRequest;
import com.company.pos.terminal.api.dto.CloseOrderRequest;
import com.company.pos.terminal.api.dto.QuoteView;
import com.company.pos.terminal.api.dto.SaleLineModifierView;
import com.company.pos.terminal.api.dto.SaleLineView;
import com.company.pos.terminal.api.dto.SalePaymentView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.PaymentViewModel;
import com.company.pos.terminal.viewmodel.PaymentViewModel.CheckoutGateway;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Thin controller for the payment screen, in DINE_IN or RETAIL mode. Builds the
 * {@link PaymentViewModel} with a {@link CheckoutGateway} bound to the right server call
 * ({@code dining.close} vs {@code sales.checkout}) and runs the VM off the FX thread via
 * {@link FxTasks}. Cash/Card/Wallet buttons take the whole remaining amount (one-tap);
 * "Add partial tender" appends a split tender. The receipt renders only from the authoritative
 * {@link SaleView}. Error text is bound to {@code vm.errorMessage()}.
 */
public class PaymentController {

    /** Which backend closes the sale, and where cancel/done navigate. */
    public enum Mode { DINE_IN, RETAIL }

    private static final System.Logger LOG = System.getLogger(PaymentController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final Mode mode;
    private final UUID id; // orderId (DINE_IN) or cartId (RETAIL)
    private final BigDecimal estimatedTotal;
    private final PaymentViewModel vm;
    private boolean quoteLoaded = false;

    @FXML private Label estimateLabel;
    @FXML private Label quoteBadge;
    @FXML private Label totalLabel;
    @FXML private Label remainingLabel;
    @FXML private Label errorLabel;
    @FXML private VBox tenderBox;
    @FXML private VBox tenderChips;
    @FXML private TextField amountField;
    @FXML private TextField tenderedField;
    @FXML private TextField panField;
    @FXML private Label changePreviewLabel;
    @FXML private Button payCashButton;
    @FXML private Button payCardButton;
    @FXML private Button payWalletButton;
    @FXML private Button addTenderButton;
    @FXML private Button cancelButton;
    @FXML private VBox resultBox;
    @FXML private Label receiptLabel;
    @FXML private VBox receiptLines;
    @FXML private Label subtotalValue;
    @FXML private HBox discountRow;
    @FXML private Label discountValue;
    @FXML private Label taxValue;
    @FXML private HBox serviceChargeRow;
    @FXML private Label serviceChargeValue;
    @FXML private Label grandTotalValue;
    @FXML private VBox paymentsBox;
    @FXML private Label changeLabel;
    @FXML private Button reprintButton;
    @FXML private Button doneButton;

    public PaymentController(Services services, Navigator navigator, Mode mode, UUID id,
            BigDecimal estimatedTotal) {
        this.services = services;
        this.navigator = navigator;
        this.mode = mode;
        this.id = id;
        this.estimatedTotal = estimatedTotal.setScale(2, RoundingMode.HALF_UP);
        this.vm = new PaymentViewModel(gatewayFor(mode, id), services.salesApi, estimatedTotal,
                Platform::runLater);
    }

    private CheckoutGateway gatewayFor(Mode m, UUID id) {
        if (m == Mode.RETAIL) {
            return tenders -> services.salesApi.checkout(
                    new CheckoutRequest(id, tenders, Map.of(), null, false));
        }
        return tenders -> services.diningApi.close(id,
                new CloseOrderRequest(tenders, Map.of(), null, false));
    }

    @FXML
    public void initialize() {
        estimateLabel.setText("Estimate at order: " + estimatedTotal.toPlainString());
        remainingLabel.textProperty().bind(
                javafx.beans.binding.Bindings.concat("Remaining: ", vm.remainingText()));

        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        tenderedField.textProperty().addListener((o, was, now) -> updateChangePreview(now));

        payCashButton.setOnAction(e -> pay(() -> vm.payFull("CASH", parse(tenderedField))));
        payCardButton.setOnAction(e -> pay(() -> vm.payFull("CARD", null)));
        payWalletButton.setOnAction(e -> pay(() -> vm.payFull("WALLET", null)));
        addTenderButton.setOnAction(e -> addPartial());
        cancelButton.setOnAction(e -> cancel());
        reprintButton.setOnAction(e -> reprint());
        doneButton.setOnAction(e -> done());

        vm.tenders().addListener((javafx.collections.ListChangeListener<Object>) c -> renderChips());
        vm.sale().addListener((o, was, now) -> { if (now != null) showResult(now); });

        // Tenders are disabled until the authoritative total loads (never tender a stale estimate).
        setTendersEnabled(false);
        totalLabel.setText("Fetching total…");
        loadQuote();
    }

    private void setTendersEnabled(boolean enabled) {
        this.quoteLoaded = enabled;
        payCashButton.setDisable(!enabled);
        payCardButton.setDisable(!enabled);
        payWalletButton.setDisable(!enabled);
        addTenderButton.setDisable(!enabled);
    }

    /** Fetch the authoritative quote off the FX thread (retail cart or dine-in order). */
    private void loadQuote() {
        final QuoteView[] holder = new QuoteView[1];
        FxTasks.run(
                () -> holder[0] = (mode == Mode.RETAIL)
                        ? services.salesApi.quote(id)
                        : services.diningApi.quoteOrder(id),
                () -> onQuoteLoaded(holder[0]),
                err -> {
                    vm.setError("Couldn't load the total — go back and try again");
                    LOG.log(System.Logger.Level.ERROR, "Quote fetch failed", err);
                });
    }

    private void onQuoteLoaded(QuoteView q) {
        String cur = q.currencyCode();
        totalLabel.setText("Total due: " + money(q.grandTotal(), cur));
        quoteBadge.setVisible(true);
        quoteBadge.setManaged(true);
        vm.setAuthoritativeTotal(q.grandTotal());
        setTendersEnabled(true);
    }

    /** "Add partial tender": tender the typed amount with the chosen method, without finalizing. */
    private void addPartial() {
        BigDecimal amount = parse(amountField);
        String method = panField.getText() != null && !panField.getText().isBlank() ? "CARD" : "CASH";
        // Cash partial uses the tendered field; card/wallet partials pass null tendered.
        BigDecimal cash = "CASH".equals(method) ? parse(tenderedField) : null;
        pay(() -> vm.addTender(method, amount, cash));
    }

    private void renderChips() {
        tenderChips.getChildren().clear();
        vm.tenders().forEach(t -> {
            Label chip = new Label(t.toString());
            chip.getStyleClass().addAll("tender-chip", "money");
            tenderChips.getChildren().add(chip);
        });
    }

    private void cancel() {
        if (mode == Mode.RETAIL) {
            navigator.toRetail();
        } else {
            navigator.toOrder(id);
        }
    }

    private void done() {
        if (mode == Mode.RETAIL) {
            navigator.toHome();
        } else {
            navigator.toTableMap();
        }
    }

    private BigDecimal parse(TextField f) {
        String raw = f.getText();
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private void pay(Runnable action) {
        setBusy(true);
        FxTasks.run(action, () -> setBusy(false), err -> {
            setBusy(false);
            LOG.log(System.Logger.Level.ERROR, "Unexpected error taking payment", err);
        });
    }

    private void reprint() {
        FxTasks.run(vm::reprint, () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error reprinting receipt", err));
    }

    private void setBusy(boolean busy) {
        payCashButton.setDisable(busy || !quoteLoaded);
        payCardButton.setDisable(busy || !quoteLoaded);
        payWalletButton.setDisable(busy || !quoteLoaded);
        addTenderButton.setDisable(busy || !quoteLoaded);
        amountField.setDisable(busy);
        tenderedField.setDisable(busy);
        panField.setDisable(busy);
    }

    private void updateChangePreview(String raw) {
        if (raw == null || raw.isBlank()) {
            changePreviewLabel.setText("");
            return;
        }
        try {
            BigDecimal cash = new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal due = parseMoney(vm.remainingText().get());
            if (cash.compareTo(due) >= 0) {
                changePreviewLabel.setText("Change: " + cash.subtract(due).toPlainString());
            } else {
                changePreviewLabel.setText("");
            }
        } catch (NumberFormatException ex) {
            changePreviewLabel.setText("");
        }
    }

    /** Parse a plain-number string defensively (blank/invalid → ZERO). */
    private static BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private void showResult(SaleView sale) {
        String cur = sale.currencyCode();
        receiptLabel.setText("Receipt " + sale.receiptNumber());

        receiptLines.getChildren().clear();
        if (sale.lines() != null) {
            for (SaleLineView line : sale.lines()) {
                receiptLines.getChildren().add(lineRow(line, cur));
            }
        }

        subtotalValue.setText(money(sale.subtotal(), cur));

        BigDecimal discount = sale.discountTotal();
        boolean hasDiscount = discount != null && discount.compareTo(BigDecimal.ZERO) > 0;
        discountRow.setVisible(hasDiscount);
        discountRow.setManaged(hasDiscount);
        if (hasDiscount) {
            discountValue.setText("-" + money(discount, cur));
        }

        taxValue.setText(money(sale.taxTotal(), cur));

        BigDecimal sc = sale.serviceChargeAmount();
        boolean hasSc = sc != null && sc.compareTo(BigDecimal.ZERO) > 0;
        serviceChargeRow.setVisible(hasSc);
        serviceChargeRow.setManaged(hasSc);
        if (hasSc) {
            serviceChargeValue.setText(money(sc, cur));
        }

        grandTotalValue.setText(money(sale.grandTotal(), cur));

        paymentsBox.getChildren().clear();
        if (sale.payments() != null) {
            for (SalePaymentView p : sale.payments()) {
                Label row = new Label(p.method() + "  " + money(p.amount(), cur)
                        + (p.changeGiven() != null && p.changeGiven().signum() > 0
                            ? "  (change " + p.changeGiven().toPlainString() + ")" : ""));
                row.getStyleClass().addAll("pay-line-value", "money");
                paymentsBox.getChildren().add(row);
            }
        }

        String change = vm.changeText().get();
        boolean showChange = parseMoney(change).signum() > 0;
        changeLabel.setVisible(showChange);
        changeLabel.setManaged(showChange);
        if (showChange) {
            changeLabel.setText("Change due: " + change + " " + cur);
        }

        doneButton.setDefaultButton(true);
        tenderBox.setVisible(false);
        tenderBox.setManaged(false);
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private HBox lineRow(SaleLineView line, String cur) {
        String mods = "";
        if (line.modifiers() != null && !line.modifiers().isEmpty()) {
            mods = " (" + line.modifiers().stream()
                    .map(SaleLineModifierView::name).collect(Collectors.joining(", ")) + ")";
        }
        Label name = new Label(qty(line.quantity()) + " × " + line.name() + mods);
        name.getStyleClass().add("pay-line-label");
        Label total = new Label(money(line.lineTotal(), cur));
        total.getStyleClass().addAll("pay-line-value", "money");
        HBox row = new HBox(name, new javafx.scene.layout.Region(), total);
        HBox.setHgrow(row.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static String qty(BigDecimal q) {
        return (q == null ? BigDecimal.ZERO : q).stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal amount, String currency) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        return value.toPlainString() + " " + currency;
    }
}
