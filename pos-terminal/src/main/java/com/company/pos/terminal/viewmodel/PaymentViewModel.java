package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.CloseOrderRequest;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel that takes payment on an open dining order by closing it server-side, then exposes the
 * authoritative {@link SaleView} the server returns. Unit-testable without the FX toolkit; every
 * call runs <b>synchronously</b> on the calling thread (the controller runs it off the FX thread).
 *
 * <p>The terminal pays the <b>estimated</b> total supplied at construction (the pre-close client
 * estimate). For this slice the store default has service charge off and no discounts are sent, so
 * the server's computed {@link SaleView#grandTotal()} equals the estimate; the {@code SaleView} is
 * still the authoritative source once {@link #paid()} is set.
 *
 * <p>Cash short-tender guard: {@link #payCash(BigDecimal)} rejects {@code tendered < estimatedTotal}
 * <b>before any server call</b> (sets {@link #errorMessage()}, makes no {@code close} call). Change
 * is {@code tendered - estimatedTotal}. Card ({@link #payCard()}) has no short-tender concept.
 */
public class PaymentViewModel {

    private final DiningApi dining;
    private final SalesApi sales;
    private final UUID orderId;
    private final BigDecimal estimatedTotal;
    private final Consumer<Runnable> ui;

    private final ReadOnlyStringWrapper changeText = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");
    private final ReadOnlyObjectWrapper<SaleView> sale = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyBooleanWrapper paid = new ReadOnlyBooleanWrapper(false);

    public PaymentViewModel(
            DiningApi dining, SalesApi sales, UUID orderId, BigDecimal estimatedTotal) {
        this(dining, sales, orderId, estimatedTotal, Runnable::run);
    }

    public PaymentViewModel(
            DiningApi dining,
            SalesApi sales,
            UUID orderId,
            BigDecimal estimatedTotal,
            Consumer<Runnable> ui) {
        this.dining = dining;
        this.sales = sales;
        this.orderId = orderId;
        this.estimatedTotal = estimatedTotal.setScale(2, RoundingMode.HALF_UP);
        this.ui = ui;
    }

    public ReadOnlyStringProperty changeText() {
        return changeText.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<SaleView> sale() {
        return sale.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty paid() {
        return paid.getReadOnlyProperty();
    }

    /**
     * Closes the order with a single CASH tender. Rejects a short tender before any server call. On
     * success, exposes the returned {@link SaleView}, marks {@link #paid()}, and sets
     * {@link #changeText()} to {@code tendered - estimatedTotal}.
     */
    public void payCash(BigDecimal tendered) {
        BigDecimal cash = (tendered == null ? BigDecimal.ZERO : tendered).setScale(2, RoundingMode.HALF_UP);
        if (cash.compareTo(estimatedTotal) < 0) {
            ui.accept(() -> errorMessage.set("Insufficient cash tendered"));
            return;
        }
        if (close(new TenderInput("CASH", estimatedTotal, cash))) {
            String change = cash.subtract(estimatedTotal).toPlainString();
            ui.accept(() -> changeText.set(change));
        }
    }

    /** Closes the order with a single CARD tender (no short-tender concept, {@code tendered} null). */
    public void payCard() {
        close(new TenderInput("CARD", estimatedTotal, null));
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

    /** @return true if the close succeeded (sale set + paid), false if an ApiException surfaced. */
    private boolean close(TenderInput tender) {
        ui.accept(() -> errorMessage.set(""));
        try {
            CloseOrderRequest req =
                    new CloseOrderRequest(List.of(tender), Map.of(), null, false);
            SaleView closed = dining.close(orderId, req);
            ui.accept(() -> {
                sale.set(closed);
                paid.set(true);
            });
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }

    /** Prefer the server's ProblemDetail (detail, then title), else the exception message. */
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
