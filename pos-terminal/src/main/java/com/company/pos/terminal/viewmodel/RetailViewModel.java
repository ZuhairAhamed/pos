package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CartApi;
import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.CartView;
import com.company.pos.terminal.order.CartSubtotalCalculator;
import com.company.pos.terminal.order.MenuCache;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel for the retail cart. Creates a server cart, mutates its lines, and exposes the lines,
 * a client-side pre-tax {@code est.} subtotal ({@link CartSubtotalCalculator}), an error message,
 * and an item-added counter the screen watches to fire the total-bar pulse. Unit-testable without
 * FX; every mutating call runs synchronously (the controller runs it off the FX thread).
 *
 * <p>Barcode entry resolves client-side against the {@link MenuCache} (the backend {@code ?q=}
 * searches name+SKU only), then adds the resolved sku with quantity 1 and no modifiers.
 */
public class RetailViewModel {

    private final CartApi cart;
    private final MenuCache cache;
    private final Consumer<Runnable> ui;

    private final ObservableList<CartLineView> lines = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper subtotalText = new ReadOnlyStringWrapper("0.00");
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");
    private final ReadOnlyIntegerWrapper itemAddedCount = new ReadOnlyIntegerWrapper(0);

    private UUID cartId;
    private BigDecimal estimatedTotal = BigDecimal.ZERO.setScale(2);

    public RetailViewModel(CartApi cart, MenuCache cache) {
        this(cart, cache, Runnable::run);
    }

    public RetailViewModel(CartApi cart, MenuCache cache, Consumer<Runnable> ui) {
        this.cart = cart;
        this.cache = cache;
        this.ui = ui;
    }

    public ObservableList<CartLineView> lines() { return lines; }
    public ReadOnlyStringProperty subtotalText() { return subtotalText.getReadOnlyProperty(); }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }
    public ReadOnlyIntegerProperty itemAddedCount() { return itemAddedCount.getReadOnlyProperty(); }
    public UUID cartId() { return cartId; }
    public BigDecimal estimatedTotal() { return estimatedTotal; }

    /** Creates a fresh server cart. Call once when the screen opens. */
    public void start() {
        try {
            UUID id = cart.createCart();
            this.cartId = id; // set synchronously so cartId() is usable in tests/next calls
            ui.accept(() -> {
                lines.clear();
                subtotalText.set("0.00");
                errorMessage.set("");
            });
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    public void addBySku(String sku, BigDecimal qty, List<UUID> optionIds) {
        boolean ok = apply(() -> cart.addLine(cartId, sku, qty, optionIds));
        if (ok) {
            ui.accept(() -> itemAddedCount.set(itemAddedCount.get() + 1));
        }
    }

    /**
     * Resolves a barcode to a sku via the cache and adds it (qty 1, no modifiers).
     * @return the resolved sku, or {@code null} if the barcode is unknown (error surfaced).
     */
    public String addByBarcode(String code) {
        String sku = cache.skuForBarcode(code);
        if (sku == null) {
            ui.accept(() -> errorMessage.set("No product for barcode: " + code));
            return null;
        }
        addBySku(sku, BigDecimal.ONE, List.of());
        return sku;
    }

    public void updateQty(CartLineView line, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            removeLine(line);
            return;
        }
        apply(() -> cart.updateLine(cartId, line.lineId(), qty));
    }

    public void removeLine(CartLineView line) {
        apply(() -> cart.removeLine(cartId, line.lineId()));
    }

    /** Runs a cart call, swaps in the returned cart, rebuilds lines + subtotal. @return success. */
    private boolean apply(Supplier<CartView> call) {
        try {
            CartView v = call.get();
            List<CartLineView> next = v.lines() == null ? List.of() : v.lines();
            BigDecimal sub = CartSubtotalCalculator.estimate(v);
            this.estimatedTotal = sub;
            ui.accept(() -> {
                lines.setAll(next);
                subtotalText.set(sub.toPlainString());
                errorMessage.set("");
            });
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
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
