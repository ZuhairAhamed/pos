package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.dto.ShiftSummary;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for closing the terminal's shift. Synchronous like the other VMs (the controller runs
 * it off the FX thread via FxTasks); the only observables written off-thread are {@code summary}
 * and {@code errorMessage}, inside the {@code ui} dispatcher. On success the resulting
 * {@link ShiftSummary} is stored for the reconciliation result screen.
 */
public class CloseShiftViewModel {

    private final ShiftApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyObjectWrapper<ShiftSummary> summary = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public CloseShiftViewModel(ShiftApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyObjectProperty<ShiftSummary> summary() { return summary.getReadOnlyProperty(); }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    /**
     * Closes the shift with the counted cash. Returns true on success (and stores the summary);
     * on ApiException surfaces the message via errorMessage and returns false.
     */
    public boolean closeShift(UUID shiftId, BigDecimal countedCash) {
        try {
            ShiftSummary closed = api.closeShift(shiftId, countedCash);
            ui.accept(() -> {
                summary.set(closed);
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
