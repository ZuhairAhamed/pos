package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.AddLineRequest;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.order.SubtotalCalculator;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel for the open dining order. Holds the current {@link OrderView}, its lines, a
 * client-side pre-tax subtotal estimate, and an error message; it is unit-testable without the FX
 * toolkit.
 *
 * <p>Every mutating call ({@link #addLine}, {@link #updateQty}, {@link #removeLine}, {@link #fire})
 * runs <b>synchronously</b> on the calling thread, re-reads the {@link OrderView} the server
 * returns, and rebuilds {@link #lines()} + {@link #subtotalText()}. The controller is responsible
 * for running these off the FX thread inside a {@code Task}.
 *
 * <p>A line whose {@code firedAt != null} is <b>locked</b>: {@link #updateQty} / {@link #removeLine}
 * on it are no-ops that set {@link #errorMessage()} and leave state intact. The subtotal is a
 * PRE-tax, PRE-service-charge estimate only ({@link SubtotalCalculator}); the server owns the
 * authoritative total at checkout.
 */
public class OrderViewModel {

    private final DiningApi dining;
    private final MenuCache cache;
    private final Consumer<Runnable> ui;
    private final ObservableList<OrderLineView> lines = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper subtotalText = new ReadOnlyStringWrapper("0.00");
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");
    private OrderView order;

    public OrderViewModel(DiningApi dining, MenuCache cache) {
        this(dining, cache, Runnable::run);
    }

    public OrderViewModel(DiningApi dining, MenuCache cache, Consumer<Runnable> ui) {
        this.dining = dining;
        this.cache = cache;
        this.ui = ui;
    }

    public ObservableList<OrderLineView> lines() {
        return lines;
    }

    public ReadOnlyStringProperty subtotalText() {
        return subtotalText.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public UUID orderId() {
        return order == null ? null : order.id();
    }

    public OrderView currentOrder() {
        return order;
    }

    /** A fired line (firedAt != null) is locked and cannot be edited or removed. */
    public boolean canEdit(OrderLineView line) {
        return !line.fired();
    }

    public void load(UUID orderId) {
        apply(() -> dining.order(orderId));
    }

    public void addLine(
            String sku, BigDecimal qty, String note, String course, List<UUID> optionIds) {
        apply(() -> dining.addLine(order.id(), new AddLineRequest(sku, qty, note, course, optionIds)));
    }

    public void updateQty(OrderLineView line, BigDecimal qty) {
        if (!canEdit(line)) {
            ui.accept(() -> errorMessage.set("Fired lines cannot be changed"));
            return;
        }
        apply(() -> dining.updateLine(order.id(), line.id(), qty));
    }

    public void removeLine(OrderLineView line) {
        if (!canEdit(line)) {
            ui.accept(() -> errorMessage.set("Fired lines cannot be changed"));
            return;
        }
        apply(() -> dining.removeLine(order.id(), line.id()));
    }

    /** Fires all un-fired lines to the kitchen, then re-reads the order. */
    public void fire() {
        try {
            dining.fire(order.id());
            ui.accept(() -> errorMessage.set(""));
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return;
        }
        apply(() -> dining.order(order.id()));
    }

    /**
     * Runs the server call, and on success swaps in the returned order and rebuilds lines +
     * subtotal. On {@link ApiException} it surfaces the message and leaves the current state intact.
     */
    private void apply(Supplier<OrderView> call) {
        try {
            OrderView refreshed = call.get();
            order = refreshed;
            List<OrderLineView> nextLines =
                    refreshed.lines() == null ? List.of() : refreshed.lines();
            String subtotal = SubtotalCalculator.estimate(refreshed, cache).toPlainString();
            ui.accept(() -> {
                lines.setAll(nextLines);
                subtotalText.set(subtotal);
                errorMessage.set("");
            });
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
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
