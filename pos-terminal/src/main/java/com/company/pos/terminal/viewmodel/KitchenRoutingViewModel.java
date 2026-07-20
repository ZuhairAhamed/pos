package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.KitchenApi;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.StationAssignmentView;
import com.company.pos.terminal.api.dto.ProductView;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the kitchen-routing screen. Synchronous like the other VMs — the controller runs it
 * off the FX thread via FxTasks and reads the return value; the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher.
 */
public class KitchenRoutingViewModel {

    private final KitchenApi kitchenApi;
    private final ProductApi productApi;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public KitchenRoutingViewModel(KitchenApi kitchenApi, ProductApi productApi, Consumer<Runnable> ui) {
        this.kitchenApi = kitchenApi;
        this.productApi = productApi;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<ProductView> loadProducts() {
        try {
            List<ProductView> list = productApi.list();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public List<StationAssignmentView> loadAssignments() {
        try {
            List<StationAssignmentView> list = kitchenApi.listAssignments();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public StationAssignmentView assign(String sku, String stationName) {
        if (stationName == null || stationName.isBlank()) {
            ui.accept(() -> errorMessage.set("Station name is required"));
            return null;
        }
        try {
            StationAssignmentView v = kitchenApi.assign(sku, stationName.trim());
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean unassign(String sku) {
        try {
            kitchenApi.unassign(sku);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    private void fail(ApiException e) {
        String msg = messageOf(e);
        ui.accept(() -> errorMessage.set(msg));
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
