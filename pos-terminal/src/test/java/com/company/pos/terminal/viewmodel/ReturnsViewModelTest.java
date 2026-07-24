package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ReturnApi;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.ManagerAuth;
import com.company.pos.terminal.api.dto.ReturnCommand;
import com.company.pos.terminal.api.dto.ReturnLineRequest;
import com.company.pos.terminal.api.dto.ReturnView;
import com.company.pos.terminal.api.dto.SaleLineView;
import com.company.pos.terminal.api.dto.SaleView;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ReturnsViewModelTest {

    private static final UUID SALE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private SaleView sale() {
        return new SaleView(SALE_ID, "S01-T01-9", new BigDecimal("4.50"), new BigDecimal("0.68"),
                BigDecimal.ZERO, new BigDecimal("5.18"), "SAR", BigDecimal.ZERO,
                List.of(new SaleLineView(1, "COLA", "Cola Can", BigDecimal.ONE, new BigDecimal("4.50"),
                        new BigDecimal("5.18"), List.of())),
                List.of());
    }

    private ReturnView returnView() {
        return new ReturnView(UUID.randomUUID(), "CN-1", SALE_ID, "COMPLETED", "SAR",
                new BigDecimal("4.50"), new BigDecimal("0.68"), new BigDecimal("5.18"), null,
                List.of(), List.of());
    }

    @Test
    void lookupReturnsSale() {
        SalesApi sales = new SalesApi(null) {
            @Override public SaleView getSaleByReceipt(String r) { return sale(); }
        };
        ReturnsViewModel vm = new ReturnsViewModel(sales, new ReturnApi(null), new AuthApi(null, null));
        SaleView s = vm.lookup("S01-T01-9");
        assertEquals("S01-T01-9", s.receiptNumber());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void lookupMissSetsFriendlyMessage() {
        SalesApi sales = new SalesApi(null) {
            @Override public SaleView getSaleByReceipt(String r) {
                throw new ApiException(404, new ProblemDetail("Not Found", 404, "No sale with receipt X"),
                        "HTTP 404");
            }
        };
        ReturnsViewModel vm = new ReturnsViewModel(sales, new ReturnApi(null), new AuthApi(null, null));
        assertNull(vm.lookup("BOGUS"));
        assertEquals("No sale found for that receipt", vm.errorMessage().get());
    }

    @Test
    void processHappyPathReturnsView() {
        ReturnApi returns = new ReturnApi(null) {
            @Override public ReturnView process(ReturnCommand c, String token) { return returnView(); }
        };
        AuthApi auth = new AuthApi(null, null) {
            @Override public ManagerAuth pinLoginForToken(String code, String pin) {
                return new ManagerAuth("tok", "mgr", Set.of("MANAGER"));
            }
        };
        ReturnsViewModel vm = new ReturnsViewModel(new SalesApi(null), returns, auth);
        ReturnView v = vm.process(SALE_ID, List.of(new ReturnLineRequest(1, BigDecimal.ONE)), "m1", "1234");
        assertEquals("CN-1", v.creditNoteNumber());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void processRejectsNonManagerWithoutCallingReturns() {
        boolean[] called = {false};
        ReturnApi returns = new ReturnApi(null) {
            @Override public ReturnView process(ReturnCommand c, String token) {
                called[0] = true; return returnView();
            }
        };
        AuthApi auth = new AuthApi(null, null) {
            @Override public ManagerAuth pinLoginForToken(String code, String pin) {
                return new ManagerAuth("tok", "cashier", Set.of("CASHIER"));
            }
        };
        ReturnsViewModel vm = new ReturnsViewModel(new SalesApi(null), returns, auth);
        assertNull(vm.process(SALE_ID, List.of(new ReturnLineRequest(1, BigDecimal.ONE)), "c1", "0000"));
        assertEquals("This account is not a manager", vm.errorMessage().get());
        org.junit.jupiter.api.Assertions.assertEquals(false, called[0]);
    }

    @Test
    void processSurfacesErrorUnderDeferredDispatcher() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public ManagerAuth pinLoginForToken(String code, String pin) {
                return new ManagerAuth("tok", "mgr", Set.of("MANAGER"));
            }
        };
        ReturnApi returns = new ReturnApi(null) {
            @Override public ReturnView process(ReturnCommand c, String token) {
                throw new ApiException(422, new ProblemDetail("Unprocessable", 422,
                        "return quantity exceeds sold"), "HTTP 422");
            }
        };
        Deque<Runnable> queue = new ArrayDeque<>();
        Consumer<Runnable> deferred = queue::add;
        ReturnsViewModel vm = new ReturnsViewModel(new SalesApi(null), returns, auth, deferred);

        ReturnView v = vm.process(SALE_ID, List.of(new ReturnLineRequest(1, BigDecimal.ONE)), "m1", "1234");

        assertNull(v);                              // synchronous truth: failed
        assertEquals("", vm.errorMessage().get());  // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("return quantity exceeds sold", vm.errorMessage().get());
    }
}
