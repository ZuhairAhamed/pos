package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DashboardApi;
import com.company.pos.terminal.api.dto.DashboardSnapshot;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the manager dashboard. Synchronous (the controller runs it off the FX thread via
 * FxTasks); the only off-thread observable write is {@code errorMessage} inside {@code ui}.
 */
public class DashboardViewModel {

    private final DashboardApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public DashboardViewModel(DashboardApi api) {
        this(api, Runnable::run);
    }

    public DashboardViewModel(DashboardApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Fetches the snapshot; on failure surfaces the reason and returns null. */
    public DashboardSnapshot load() {
        try {
            DashboardSnapshot snapshot = api.snapshot();
            ui.accept(() -> errorMessage.set(""));
            return snapshot;
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
