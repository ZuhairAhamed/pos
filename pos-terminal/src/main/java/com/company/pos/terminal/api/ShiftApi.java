package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CloseShiftRequest;
import com.company.pos.terminal.api.dto.OpenShiftRequest;
import com.company.pos.terminal.api.dto.ShiftSummary;
import com.company.pos.terminal.api.dto.ShiftView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.util.UUID;

/** Typed client for the store server's {@code /shifts} endpoints. */
public class ShiftApi {

    private final ApiClient client;

    public ShiftApi(ApiClient client) {
        this.client = client;
    }

    /**
     * GET /shifts/open — this terminal's open shift, or {@code null} when none exists.
     * The server models "no open shift" as a 404; that is an expected state here, so it
     * maps to null rather than an exception. Any other failure is rethrown.
     */
    public ShiftView findOpenShift() {
        try {
            return client.get("/shifts/open", new TypeReference<ShiftView>() {});
        } catch (ApiException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** POST /shifts — opens a shift for this terminal with the given opening cash float. */
    public ShiftView openShift(BigDecimal openingFloat) {
        return client.post("/shifts", new OpenShiftRequest(openingFloat),
                new TypeReference<ShiftView>() {});
    }

    /** POST /shifts/{shiftId}/close — closes the shift and returns the drawer reconciliation. */
    public ShiftSummary closeShift(UUID shiftId, BigDecimal countedCash) {
        return client.post("/shifts/" + shiftId + "/close", new CloseShiftRequest(countedCash),
                new TypeReference<ShiftSummary>() {});
    }
}
