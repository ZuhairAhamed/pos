package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.TableView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel for the dining table-map screen. Holds all logic and exposes JavaFX observable
 * properties; it is unit-testable without the FX toolkit.
 *
 * <p>{@link #refresh()} and {@link #openOrResume(TableCell)} run <b>synchronously</b> on the
 * calling thread and side-effect the properties. The controller is responsible for running them
 * off the FX thread inside a {@code Task}.
 *
 * <p>Occupancy is derived purely from the {@code GET /dining/orders} summary list (open orders
 * only): a table is occupied when its id appears as some {@link OpenOrderView#tableId()}. No
 * per-table {@code order(id)} fetch is issued just to determine occupancy.
 */
public class TableMapViewModel {

    private final DiningApi dining;
    private final Consumer<Runnable> ui;
    private final ObservableList<TableCell> cells = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public TableMapViewModel(DiningApi dining) {
        this(dining, Runnable::run);
    }

    public TableMapViewModel(DiningApi dining, Consumer<Runnable> ui) {
        this.dining = dining;
        this.ui = ui;
    }

    public ObservableList<TableCell> cells() {
        return cells;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Loads tables + open orders and rebuilds {@link #cells()}, marking occupied tables. */
    public void refresh() {
        try {
            Map<UUID, UUID> orderByTable = new HashMap<>();
            for (OpenOrderView o : dining.openOrders()) {
                orderByTable.put(o.tableId(), o.orderId());
            }
            List<TableCell> next = new ArrayList<>();
            for (TableView t : dining.tables()) {
                if (!t.active()) {
                    continue;
                }
                UUID orderId = orderByTable.get(t.id());
                next.add(new TableCell(t.id(), t.label(), orderId != null, orderId));
            }
            ui.accept(() -> {
                cells.setAll(next);
                errorMessage.set("");
            });
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    /**
     * Occupied table → returns its existing open order id. Free table → opens a new DINE_IN order
     * via {@link DiningApi#openOrder(UUID)} and returns the new order id. Returns {@code null} and
     * sets {@link #errorMessage()} on {@link ApiException}.
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
