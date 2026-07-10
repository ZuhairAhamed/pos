package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.PaymentViewModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Thin controller for the payment screen. Binds the FXML controls to a {@link PaymentViewModel}
 * (built from {@link Services#diningApi} + {@link Services#salesApi}) and runs the (synchronous) VM
 * calls off the FX thread via {@link FxTasks}. No business logic lives here.
 *
 * <p><b>Short-tender guard:</b> the VM's {@link PaymentViewModel#payCash(BigDecimal)} rejects
 * {@code tendered < estimatedTotal} <b>before any server {@code close} call</b>, surfacing the
 * rejection through the bound {@code errorMessage()} property. The controller parses the cash field
 * (a UI concern); an empty/invalid entry is passed as {@link BigDecimal#ZERO}, so the same guard
 * rejects it with a clear inline error and no close is attempted. Card pay has no short-tender field.
 *
 * <p><b>Authoritative totals:</b> after a successful close the controller reveals the confirmation
 * block populated <i>only</i> from the returned {@link SaleView} — subtotal, tax, service charge
 * (shown only when {@code > 0}) and the authoritative grand total — plus change due for cash. Reprint
 * routes through {@link PaymentViewModel#reprint()} and "Done" returns to the table map.
 *
 * <p><b>Error handling:</b> {@code errorLabel.text} is bound to {@code vm.errorMessage()}, so
 * {@code FxTasks} {@code onError} callbacks must never {@code setText} it (that throws on a bound
 * property). Unexpected task failures are logged via {@link System.Logger}; VM-surfaced business
 * errors flow through the bound {@code errorMessage()} property.
 */
public class PaymentController {

    private static final System.Logger LOG = System.getLogger(PaymentController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private final BigDecimal estimatedTotal;
    private final PaymentViewModel vm;

    @FXML private Label totalLabel;
    @FXML private Label errorLabel;
    @FXML private VBox tenderBox;
    @FXML private TextField tenderedField;
    @FXML private Label changePreviewLabel;
    @FXML private Button payCashButton;
    @FXML private Button payCardButton;
    @FXML private Button cancelButton;
    @FXML private VBox resultBox;
    @FXML private Label receiptLabel;
    @FXML private Label subtotalValue;
    @FXML private Label taxValue;
    @FXML private HBox serviceChargeRow;
    @FXML private Label serviceChargeValue;
    @FXML private Label grandTotalValue;
    @FXML private Label changeLabel;
    @FXML private Button reprintButton;
    @FXML private Button doneButton;

    public PaymentController(Services services, Navigator navigator, UUID orderId, BigDecimal estimatedTotal) {
        this.services = services;
        this.navigator = navigator;
        this.orderId = orderId;
        this.estimatedTotal = estimatedTotal.setScale(2, RoundingMode.HALF_UP);
        this.vm = new PaymentViewModel(services.diningApi, services.salesApi, orderId, estimatedTotal);
    }

    @FXML
    public void initialize() {
        totalLabel.setText("Total due (est.): " + estimatedTotal.toPlainString());

        // Error banner: text from the VM; hidden (and not laid out) when empty.
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        // Live change preview as the cashier types (display only; the VM is authoritative).
        tenderedField.textProperty().addListener((o, was, now) -> updateChangePreview(now));
        updateChangePreview(tenderedField.getText());

        payCashButton.setOnAction(e -> payCash());
        payCardButton.setOnAction(e -> pay(vm::payCard));
        cancelButton.setOnAction(e -> navigator.toOrder(orderId));
        reprintButton.setOnAction(e -> reprint());
        doneButton.setOnAction(e -> navigator.toTableMap());

        // Reveal the confirmation block once the server returns an authoritative sale.
        vm.sale().addListener((o, was, now) -> {
            if (now != null) {
                showResult(now);
            }
        });
    }

    /** Parse the cash field (empty/invalid → ZERO so the VM's short-tender guard rejects it). */
    private void payCash() {
        pay(() -> vm.payCash(parseTendered()));
    }

    private BigDecimal parseTendered() {
        String raw = tenderedField.getText();
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    /** Dispatch a VM close call off the FX thread; business errors surface via the bound banner. */
    private void pay(Runnable action) {
        setBusy(true);
        FxTasks.run(
                action,
                () -> setBusy(false),
                err -> {
                    setBusy(false);
                    LOG.log(System.Logger.Level.ERROR, "Unexpected error taking payment", err);
                });
    }

    private void reprint() {
        FxTasks.run(
                vm::reprint,
                () -> { },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error reprinting receipt", err));
    }

    private void setBusy(boolean busy) {
        payCashButton.setDisable(busy);
        payCardButton.setDisable(busy);
        tenderedField.setDisable(busy);
    }

    /** Display-only change preview; blank unless a valid amount ≥ estimate is entered. */
    private void updateChangePreview(String raw) {
        if (raw == null || raw.isBlank()) {
            changePreviewLabel.setText("");
            return;
        }
        try {
            BigDecimal cash = new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
            if (cash.compareTo(estimatedTotal) >= 0) {
                changePreviewLabel.setText("Change: " + cash.subtract(estimatedTotal).toPlainString());
            } else {
                changePreviewLabel.setText("");
            }
        } catch (NumberFormatException ex) {
            changePreviewLabel.setText("");
        }
    }

    /** Populate the confirmation block from the authoritative SaleView and swap panes. */
    private void showResult(SaleView sale) {
        receiptLabel.setText("Receipt " + sale.receiptNumber());
        subtotalValue.setText(money(sale.subtotal(), sale.currencyCode()));
        taxValue.setText(money(sale.taxTotal(), sale.currencyCode()));

        BigDecimal serviceCharge = sale.serviceChargeAmount();
        boolean hasServiceCharge = serviceCharge != null && serviceCharge.compareTo(BigDecimal.ZERO) > 0;
        serviceChargeRow.setVisible(hasServiceCharge);
        serviceChargeRow.setManaged(hasServiceCharge);
        if (hasServiceCharge) {
            serviceChargeValue.setText(money(serviceCharge, sale.currencyCode()));
        }

        grandTotalValue.setText(money(sale.grandTotal(), sale.currencyCode()));

        String change = vm.changeText().get();
        boolean showChange = change != null && !change.isBlank();
        changeLabel.setVisible(showChange);
        changeLabel.setManaged(showChange);
        if (showChange) {
            changeLabel.setText("Change due: " + change + " " + sale.currencyCode());
        }

        // Swap tender entry out for the confirmation block.
        tenderBox.setVisible(false);
        tenderBox.setManaged(false);
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private static String money(BigDecimal amount, String currency) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        return value.toPlainString() + " " + currency;
    }
}
