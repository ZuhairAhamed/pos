package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel that takes payment against an estimated total and exposes the authoritative
 * {@link SaleView}. It is checkout-mechanism agnostic: a {@link CheckoutGateway} performs
 * the actual server call (dine-in close or retail /sales), so this class holds only tender
 * accumulation, the short-cash guard, and change/remaining math. Unit-testable without FX;
 * every method runs synchronously on the caller (the controller runs it off the FX thread).
 *
 * <p>Multiple tenders per sale are supported: {@link #addTender} appends a tender and updates
 * {@link #remainingText()}; {@link #finalizeSale()} refuses while any amount remains due.
 * {@link #payFull} is the one-tap path (tender the whole remaining amount, then finalize).
 */
public class PaymentViewModel {

    /** Performs the actual checkout for the collected tenders and returns the authoritative sale. */
    @FunctionalInterface
    public interface CheckoutGateway {
        SaleView checkout(List<TenderInput> tenders);
    }

    private final CheckoutGateway gateway;
    private final SalesApi sales;
    private final BigDecimal estimatedTotal;
    private final Consumer<Runnable> ui;

    private final ObservableList<TenderInput> tenders = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper remainingText;
    private final ReadOnlyStringWrapper changeText = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");
    private final ReadOnlyObjectWrapper<SaleView> sale = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyBooleanWrapper paid = new ReadOnlyBooleanWrapper(false);

    public PaymentViewModel(CheckoutGateway gateway, SalesApi sales, BigDecimal estimatedTotal) {
        this(gateway, sales, estimatedTotal, Runnable::run);
    }

    public PaymentViewModel(CheckoutGateway gateway, SalesApi sales, BigDecimal estimatedTotal,
            Consumer<Runnable> ui) {
        this.gateway = gateway;
        this.sales = sales;
        this.estimatedTotal = estimatedTotal.setScale(2, RoundingMode.HALF_UP);
        this.ui = ui;
        this.remainingText = new ReadOnlyStringWrapper(this.estimatedTotal.toPlainString());
    }

    public ObservableList<TenderInput> tenders() { return tenders; }
    public ReadOnlyStringProperty remainingText() { return remainingText.getReadOnlyProperty(); }
    public ReadOnlyStringProperty changeText() { return changeText.getReadOnlyProperty(); }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }
    public ReadOnlyObjectProperty<SaleView> sale() { return sale.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty paid() { return paid.getReadOnlyProperty(); }

    public BigDecimal remaining() {
        BigDecimal covered = BigDecimal.ZERO;
        for (TenderInput t : tenders) {
            covered = covered.add(t.amount());
        }
        BigDecimal rem = estimatedTotal.subtract(covered);
        return rem.signum() < 0 ? BigDecimal.ZERO : rem;
    }

    /**
     * Appends one tender toward the total. {@code amount} is what this tender covers;
     * for CASH, {@code cashTendered} is the money handed over and must be ≥ amount.
     * Rejects (no append) on non-positive amount or short cash.
     */
    public void addTender(String method, BigDecimal amount, BigDecimal cashTendered) {
        BigDecimal amt = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            ui.accept(() -> errorMessage.set("Enter a tender amount"));
            return;
        }
        BigDecimal tendered = null;
        if ("CASH".equals(method)) {
            tendered = (cashTendered == null ? BigDecimal.ZERO : cashTendered).setScale(2, RoundingMode.HALF_UP);
            if (tendered.compareTo(amt) < 0) {
                ui.accept(() -> errorMessage.set("Insufficient cash tendered"));
                return;
            }
        }
        TenderInput t = new TenderInput(method, amt, tendered);
        String rem = estimatedTotal.subtract(coveredIncluding(amt)).max(BigDecimal.ZERO).toPlainString();
        ui.accept(() -> {
            tenders.add(t);
            remainingText.set(rem);
            errorMessage.set("");
        });
    }

    private BigDecimal coveredIncluding(BigDecimal extra) {
        BigDecimal covered = extra;
        for (TenderInput t : tenders) {
            covered = covered.add(t.amount());
        }
        return covered;
    }

    /** One-tap path: tender the whole remaining amount with {@code method}, then finalize. */
    public void payFull(String method, BigDecimal cashTendered) {
        BigDecimal due = remaining();
        if (due.signum() <= 0) {
            finalizeSale();
            return;
        }
        int before = tenders.size();
        addTender(method, due, cashTendered);
        if (tenders.size() == before) {
            return; // addTender rejected (e.g. short cash); error already set
        }
        finalizeSale();
    }

    /** Runs the gateway checkout once the full amount is tendered; else surfaces remaining due. */
    public void finalizeSale() {
        BigDecimal due = remaining();
        if (due.signum() > 0) {
            ui.accept(() -> errorMessage.set("Remaining due: " + due.toPlainString()));
            return;
        }
        if (tenders.isEmpty()) {
            ui.accept(() -> errorMessage.set("Add a tender first"));
            return;
        }
        ui.accept(() -> errorMessage.set(""));
        try {
            SaleView closed = gateway.checkout(new ArrayList<>(tenders));
            String change = totalChange().toPlainString();
            ui.accept(() -> {
                sale.set(closed);
                paid.set(true);
                changeText.set(change);
            });
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    /** Overall change = Σ over cash tenders of (tendered − amount), never negative. */
    private BigDecimal totalChange() {
        BigDecimal change = BigDecimal.ZERO;
        for (TenderInput t : tenders) {
            if (t.tendered() != null) {
                change = change.add(t.tendered().subtract(t.amount()));
            }
        }
        return change.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** Reprints the closed sale's receipt. No-op if nothing has been paid yet. */
    public void reprint() {
        SaleView current = sale.get();
        if (current == null) {
            return;
        }
        try {
            sales.reprint(current.id());
            ui.accept(() -> errorMessage.set(""));
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
