package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.TableAdminApi;
import com.company.pos.terminal.api.dto.TableView;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the dining-tables admin screen. Synchronous like the other VMs — the controller runs
 * it off the FX thread via FxTasks and reads the return value; the only observable written off-thread
 * is {@code errorMessage}, inside the {@code ui} dispatcher.
 */
public class TablesViewModel {

    private final TableAdminApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public TablesViewModel(TableAdminApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<TableView> loadTables() {
        try {
            List<TableView> list = api.list();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public TableView create(String label, Integer seats) {
        String err = validate(label, seats);
        if (err != null) {
            ui.accept(() -> errorMessage.set(err));
            return null;
        }
        try {
            TableView v = api.create(label.trim(), seats);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public TableView update(UUID id, String label, Integer seats) {
        String err = validate(label, seats);
        if (err != null) {
            ui.accept(() -> errorMessage.set(err));
            return null;
        }
        try {
            TableView v = api.update(id, label.trim(), seats);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean deactivate(UUID id) {
        try {
            api.deactivate(id);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    public TableView reactivate(UUID id) {
        try {
            TableView v = api.reactivate(id);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    private static String validate(String label, Integer seats) {
        if (label == null || label.isBlank()) {
            return "Table label is required";
        }
        if (seats == null || seats < 1) {
            return "Seats must be a positive number";
        }
        return null;
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
