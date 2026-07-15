package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.TableView;
import com.company.pos.terminal.viewmodel.TableCell.TableState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel for the dining table-map screen. Holds all logic and exposes JavaFX observable
 * properties; unit-testable without the FX toolkit.
 *
 * <p>{@link #refresh()}, {@link #openOrResume(TableCell)} and {@link #openTakeaway()} run
 * <b>synchronously</b> on the calling thread and side-effect the properties. The controller runs
 * them off the FX thread inside a {@code Task}.
 *
 * <p>Dine-in tables (label not starting with {@code counterPrefix}) become {@link #cells()};
 * open QUICK_SERVICE orders become {@link #takeawayOrders()}. Counter tables never appear in the
 * grid. State and dwell are derived purely from the {@code GET /dining/orders} summary; no
 * per-order fetch is issued.
 */
public class TableMapViewModel {

    private final DiningApi dining;
    private final Consumer<Runnable> ui;
    private final String counterPrefix;
    private final Duration dwellThreshold;
    private final Supplier<Instant> clock;
    private final ObservableList<TableCell> cells = FXCollections.observableArrayList();
    private final ObservableList<TakeawayRow> takeawayOrders = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public TableMapViewModel(DiningApi dining) {
        this(dining, Runnable::run);
    }

    public TableMapViewModel(DiningApi dining, Consumer<Runnable> ui) {
        this(dining, ui, "Counter ", Duration.ofMinutes(45), Instant::now);
    }

    public TableMapViewModel(DiningApi dining, Consumer<Runnable> ui, String counterPrefix,
            Duration dwellThreshold, Supplier<Instant> clock) {
        this.dining = dining;
        this.ui = ui;
        this.counterPrefix = counterPrefix;
        this.dwellThreshold = dwellThreshold;
        this.clock = clock;
    }

    public ObservableList<TableCell> cells() {
        return cells;
    }

    public ObservableList<TakeawayRow> takeawayOrders() {
        return takeawayOrders;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Loads tables + open orders and rebuilds both {@link #cells()} and {@link #takeawayOrders()}. */
    public void refresh() {
        try {
            Map<UUID, OpenOrderView> orderByTable = new HashMap<>();
            List<TakeawayRow> takeaway = new ArrayList<>();
            for (OpenOrderView o : dining.openOrders()) {
                orderByTable.put(o.tableId(), o);
                if ("QUICK_SERVICE".equals(o.serviceType())) {
                    takeaway.add(new TakeawayRow(o.orderId(), o.tableLabel(),
                            openMinutes(o.openedAt()), o.lineCount(), attention(o.openedAt())));
                }
            }
            List<TableCell> next = new ArrayList<>();
            for (TableView t : dining.tables()) {
                if (!t.active() || isCounter(t)) {
                    continue;
                }
                OpenOrderView o = orderByTable.get(t.id());
                TableState state = o == null ? TableState.FREE
                        : (o.lineCount() == 0 ? TableState.SEATED : TableState.ACTIVE);
                int minutes = o == null ? 0 : openMinutes(o.openedAt());
                boolean attn = o != null && attention(o.openedAt());
                next.add(new TableCell(t.id(), t.label(), state, minutes, attn,
                        o == null ? null : o.orderId()));
            }
            ui.accept(() -> {
                cells.setAll(next);
                takeawayOrders.setAll(takeaway);
                errorMessage.set("");
            });
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    /**
     * Occupied table → its existing open order id. Free table → opens a new DINE_IN order and
     * returns its id. Returns {@code null} and sets {@link #errorMessage()} on {@link ApiException}.
     */
    public UUID openOrResume(TableCell cell) {
        if (cell.occupied()) {
            ui.accept(() -> errorMessage.set(""));
            return cell.orderId();
        }
        try {
            OrderView opened = dining.openOrder(cell.tableId());
            ui.accept(() -> errorMessage.set(""));
            return opened.id();
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /**
     * Opens a new QUICK_SERVICE order against the first active counter table (label starting with
     * {@code counterPrefix}) that has no open order, and returns its id. Sets
     * {@link #errorMessage()} to "All counters are busy" and returns {@code null} when none is
     * free, or to the server message on {@link ApiException}.
     */
    public UUID openTakeaway() {
        try {
            Set<UUID> busy = new HashSet<>();
            for (OpenOrderView o : dining.openOrders()) {
                busy.add(o.tableId());
            }
            for (TableView t : dining.tables()) {
                if (t.active() && isCounter(t) && !busy.contains(t.id())) {
                    OrderView opened = dining.openOrder(t.id(), "QUICK_SERVICE");
                    ui.accept(() -> errorMessage.set(""));
                    return opened.id();
                }
            }
            ui.accept(() -> errorMessage.set("All counters are busy"));
            return null;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    private boolean isCounter(TableView t) {
        return t.label() != null && t.label().startsWith(counterPrefix);
    }

    private int openMinutes(Instant openedAt) {
        if (openedAt == null) {
            return 0;
        }
        return (int) Duration.between(openedAt, clock.get()).toMinutes();
    }

    private boolean attention(Instant openedAt) {
        return openedAt != null
                && Duration.between(openedAt, clock.get()).compareTo(dwellThreshold) > 0;
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
