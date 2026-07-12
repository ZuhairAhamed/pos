package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.dto.ShiftView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the once-a-day start-shift prompt. Synchronous like the other VMs
 * (the controller runs it off the FX thread via FxTasks); observable writes go through
 * the {@code ui} dispatcher. Also hosts the pure denomination-count math used by the
 * dialog's optional counting helper, so that arithmetic is unit-tested headlessly.
 */
public class StartShiftViewModel {

    /** SAR notes offered by the counting helper, largest first. */
    public static final List<BigDecimal> DENOMINATIONS = List.of(
            new BigDecimal("500"), new BigDecimal("200"), new BigDecimal("100"),
            new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("5"));

    private final ShiftApi api;
    private final Consumer<Runnable> ui;

    private final ReadOnlyObjectWrapper<ShiftView> shift = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public StartShiftViewModel(ShiftApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyObjectProperty<ShiftView> shift() { return shift.getReadOnlyProperty(); }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    /** Σ(denomination × count) at money scale; null or negative counts contribute zero. */
    public static BigDecimal total(Map<BigDecimal, Integer> counts) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, Integer> e : counts.entrySet()) {
            Integer n = e.getValue();
            if (n != null && n > 0) {
                sum = sum.add(e.getKey().multiply(BigDecimal.valueOf(n)));
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Opens a shift with the given float. An empty till (zero) is legal; a negative or
     * missing float is rejected before any server call. Returns true on success; on
     * failure the reason is surfaced via {@link #errorMessage()}.
     */
    public boolean openShift(BigDecimal openingFloat) {
        if (openingFloat == null || openingFloat.signum() < 0) {
            ui.accept(() -> errorMessage.set("Opening float must be zero or more"));
            return false;
        }
        try {
            ShiftView opened = api.openShift(openingFloat.setScale(2, RoundingMode.HALF_UP));
            ui.accept(() -> {
                shift.set(opened);
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
