package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.KitchenTicketApi;
import com.company.pos.terminal.api.dto.KitchenTicketView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel for the Kitchen Display board. Synchronous (the controller runs it off the FX thread
 * via FxTasks); the only off-thread observable write is {@code errorMessage} inside {@code ui}.
 * Aging colour uses the injected {@code clock}; warn/alert thresholds are display constants
 * (see the KDS design spec — {@code /config} is ADMIN-only so kitchen users cannot read them).
 */
public class KitchenDisplayViewModel {

    public enum Aging { GREEN, AMBER, RED }

    private final KitchenTicketApi api;
    private final Consumer<Runnable> ui;
    private final Supplier<Instant> clock;
    private final Duration warnAfter;
    private final Duration alertAfter;
    private final ObservableList<KitchenTicketView> tickets = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public KitchenDisplayViewModel(KitchenTicketApi api) {
        this(api, Runnable::run, Instant::now, Duration.ofSeconds(300), Duration.ofSeconds(600));
    }

    public KitchenDisplayViewModel(KitchenTicketApi api, Consumer<Runnable> ui,
            Supplier<Instant> clock, Duration warnAfter, Duration alertAfter) {
        this.api = api;
        this.ui = ui;
        this.clock = clock;
        this.warnAfter = warnAfter;
        this.alertAfter = alertAfter;
    }

    public ObservableList<KitchenTicketView> tickets() { return tickets; }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    /** Re-reads the active board. Synchronous; side-effects {@link #tickets()}. */
    public void refresh() {
        try {
            List<KitchenTicketView> next = api.list();
            ui.accept(() -> {
                tickets.setAll(next);
                errorMessage.set("");
            });
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    public boolean advance(UUID id, String expectedState) {
        return transition(() -> api.advance(id, expectedState));
    }

    public boolean recall(UUID id, String expectedState) {
        return transition(() -> api.recall(id, expectedState));
    }

    private boolean transition(Supplier<KitchenTicketView> call) {
        try {
            call.get();
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }

    /** GREEN < warnAfter <= AMBER < alertAfter <= RED, measured from firedAt to the clock. */
    public Aging agingOf(KitchenTicketView t) {
        Duration age = Duration.between(t.firedAt(), clock.get());
        if (age.compareTo(alertAfter) >= 0) {
            return Aging.RED;
        }
        if (age.compareTo(warnAfter) >= 0) {
            return Aging.AMBER;
        }
        return Aging.GREEN;
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
