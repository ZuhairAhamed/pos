package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CashDrawerApi;
import com.company.pos.terminal.api.dto.CashMovementView;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for mid-shift cash-drawer management. Synchronous like the other VMs (the controller
 * runs it off the FX thread via FxTasks); the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher. Read methods return plain values (or
 * {@code null} on failure) so the controller reads control-flow truth from the return value.
 */
public class CashDrawerViewModel {

    private final CashDrawerApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public CashDrawerViewModel(CashDrawerApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    /** Fetches the current drawer activity; on failure surfaces the reason and returns null. */
    public DrawerReconciliation loadActivity() {
        try {
            DrawerReconciliation a = api.reconciliation();
            ui.accept(() -> errorMessage.set(""));
            return a;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Records a pay-in. Rejects a non-positive amount or blank reason before any server call;
     *  returns the movement on success, or null (with errorMessage set) on validation/API failure. */
    public CashMovementView payIn(BigDecimal amount, String reason) {
        return move(amount, reason, true);
    }

    /** Records a pay-out. Same validation and failure semantics as {@link #payIn}. */
    public CashMovementView payOut(BigDecimal amount, String reason) {
        return move(amount, reason, false);
    }

    private CashMovementView move(BigDecimal amount, String reason, boolean payIn) {
        if (amount == null || amount.signum() <= 0) {
            ui.accept(() -> errorMessage.set("Amount must be greater than zero"));
            return null;
        }
        if (reason == null || reason.isBlank()) {
            ui.accept(() -> errorMessage.set("Enter a reason"));
            return null;
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        String trimmed = reason.trim();
        try {
            CashMovementView m = payIn ? api.payIn(scaled, trimmed) : api.payOut(scaled, trimmed);
            ui.accept(() -> errorMessage.set(""));
            return m;
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
