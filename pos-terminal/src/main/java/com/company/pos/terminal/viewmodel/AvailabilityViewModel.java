package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AvailabilityApi;
import com.company.pos.terminal.api.dto.ProductView;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the 86 board. Synchronous like the other VMs (the controller runs it off the FX
 * thread via FxTasks); the only observable written off-thread is {@code errorMessage}, inside the
 * {@code ui} dispatcher. Methods return plain values (or null on failure) so the controller reads
 * control-flow truth from the return value.
 */
public class AvailabilityViewModel {

    private final AvailabilityApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public AvailabilityViewModel(AvailabilityApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Fetches active products only; on failure surfaces the reason and returns null. */
    public List<ProductView> load() {
        try {
            List<ProductView> rows = api.list().stream()
                    .filter(p -> p.active() == null || p.active())
                    .toList();
            ui.accept(() -> errorMessage.set(""));
            return rows;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Toggles a SKU's availability; returns the updated view or null (with errorMessage set). */
    public ProductView setAvailability(String sku, boolean available) {
        try {
            ProductView v = api.setAvailability(sku, available);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
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
