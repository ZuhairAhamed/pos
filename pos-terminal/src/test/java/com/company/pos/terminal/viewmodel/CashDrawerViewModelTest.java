package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CashDrawerApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.CashMovementView;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CashDrawerViewModelTest {

    private DrawerReconciliation sampleActivity() {
        return new DrawerReconciliation(UUID.randomUUID(), new BigDecimal("500.00"),
                new BigDecimal("1200.00"), 37, new BigDecimal("100.00"), new BigDecimal("50.00"),
                new BigDecimal("1750.00"), null, null, "SAR");
    }

    private CashMovementView sampleMovement(String type, BigDecimal amount) {
        return new CashMovementView(UUID.randomUUID(), UUID.randomUUID(), type, amount,
                "till drop", Instant.now());
    }

    @Test
    void loadActivityReturnsReconciliation() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public DrawerReconciliation reconciliation() { return sampleActivity(); }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        DrawerReconciliation a = vm.loadActivity();
        assertNotNull(a);
        assertEquals(37, a.cashSalesCount());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void loadActivitySurfacesErrorAndReturnsNull() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public DrawerReconciliation reconciliation() {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "No open drawer"),
                        "HTTP 409");
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        assertNull(vm.loadActivity());
        assertEquals("No open drawer", vm.errorMessage().get());
    }

    @Test
    void payInValidReturnsMovementAndClearsError() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                assertEquals(0, new BigDecimal("25.00").compareTo(amount));
                assertEquals("float top-up", reason);
                return sampleMovement("PAY_IN", amount);
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        CashMovementView m = vm.payIn(new BigDecimal("25"), "  float top-up  ");
        assertNotNull(m);
        assertEquals("PAY_IN", m.type());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void payInRejectsNonPositiveAmountWithoutCallingApi() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                throw new AssertionError("API must not be called for invalid amount");
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        assertNull(vm.payIn(BigDecimal.ZERO, "reason"));
        assertEquals("Amount must be greater than zero", vm.errorMessage().get());
        assertNull(vm.payIn(new BigDecimal("-5"), "reason"));
    }

    @Test
    void payInRejectsBlankReasonWithoutCallingApi() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                throw new AssertionError("API must not be called for blank reason");
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        assertNull(vm.payIn(new BigDecimal("10"), "   "));
        assertEquals("Enter a reason", vm.errorMessage().get());
    }

    @Test
    void payInSurfacesApiError() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                throw new ApiException(400, new ProblemDetail("Bad", 400, "Amount too large"),
                        "HTTP 400");
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        assertNull(vm.payIn(new BigDecimal("10"), "reason"));
        assertEquals("Amount too large", vm.errorMessage().get());
    }

    @Test
    void payOutValidReturnsMovement() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payOut(BigDecimal amount, String reason) {
                return sampleMovement("PAY_OUT", amount);
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        CashMovementView m = vm.payOut(new BigDecimal("15"), "supplier cash");
        assertNotNull(m);
        assertEquals("PAY_OUT", m.type());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                throw new AssertionError("must not call API for invalid amount");
            }
        };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        CashDrawerViewModel vm = new CashDrawerViewModel(api, queue::add);
        CashMovementView result = vm.payIn(BigDecimal.ZERO, "reason");
        assertNull(result);                          // synchronous return
        assertEquals("", vm.errorMessage().get());   // deferred: error not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("Amount must be greater than zero", vm.errorMessage().get());
    }
}
