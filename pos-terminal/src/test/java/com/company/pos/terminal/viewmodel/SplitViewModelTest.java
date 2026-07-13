package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.QuoteSplitRequest;
import com.company.pos.terminal.api.dto.QuoteView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.SplitCloseRequest;
import com.company.pos.terminal.api.dto.SplitQuoteView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SplitViewModelTest {

    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID L1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID L2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID L3 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static OrderLineView line(UUID id, String sku) {
        return new OrderLineView(id, sku, BigDecimal.ONE, null, "MAIN", null, List.of());
    }

    private static OrderView order(OrderLineView... lines) {
        return new OrderView(ORDER_ID, UUID.randomUUID(), "DINE_IN", "OPEN", "alice",
                null, null, null, List.of(lines));
    }

    private static QuoteView quote(String grand) {
        return new QuoteView("SAR", new BigDecimal("30.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("4.50"), new BigDecimal(grand));
    }

    private static SaleView sale(String receipt, String grand) {
        return new SaleView(UUID.randomUUID(), receipt, new BigDecimal("30.00"),
                new BigDecimal("4.50"), BigDecimal.ZERO, new BigDecimal(grand), "SAR",
                BigDecimal.ZERO, List.of(), List.of());
    }

    /** Records requests, returns canned results. DiningApi methods are non-final instance
     *  methods, so a null-client subclass works (pattern: PaymentViewModelTest's SalesApi). */
    private static final class FakeDiningApi extends DiningApi {
        OrderView orderResult;
        SplitQuoteView quoteResult;
        List<SaleView> closeResult;
        RuntimeException closeError;
        QuoteSplitRequest lastQuoteReq;
        SplitCloseRequest lastCloseReq;

        FakeDiningApi() { super(null); }

        @Override public OrderView order(UUID id) { return orderResult; }

        @Override public SplitQuoteView quoteSplit(UUID id, QuoteSplitRequest req) {
            lastQuoteReq = req;
            return quoteResult;
        }

        @Override public List<SaleView> closeSplit(UUID id, SplitCloseRequest req) {
            lastCloseReq = req;
            if (closeError != null) throw closeError;
            return closeResult;
        }
    }

    private static SplitViewModel loadedVm(FakeDiningApi api) {
        api.orderResult = order(line(L1, "BURGER"), line(L2, "FRIES"), line(L3, "WATER"));
        SplitViewModel vm = new SplitViewModel(api);
        vm.load(ORDER_ID);
        return vm;
    }

    @Test
    void toggleAssignAssignsToActiveGuestAndTogglesOff() {
        SplitViewModel vm = loadedVm(new FakeDiningApi());
        assertEquals(3, vm.unassignedCount());
        vm.toggleAssign(L1);                       // active guest 0
        assertEquals(0, vm.guestOf(L1));
        assertEquals(2, vm.unassignedCount());
        vm.setActiveGuest(1);
        vm.toggleAssign(L1);                       // different guest: reassign, not unassign
        assertEquals(1, vm.guestOf(L1));
        vm.toggleAssign(L1);                       // same guest: unassign
        assertNull(vm.guestOf(L1));
        assertEquals(3, vm.unassignedCount());
    }

    @Test
    void canContinueOnlyWhenFullyPartitioned() {
        SplitViewModel vm = loadedVm(new FakeDiningApi());
        assertFalse(vm.canContinue());
        vm.toggleAssign(L1);
        vm.toggleAssign(L2);
        assertFalse(vm.canContinue());
        vm.toggleAssign(L3);
        assertTrue(vm.canContinue());
    }

    @Test
    void quoteRequestDropsEmptyGuestsAndKeepsGuestOrder() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.addGuest();                              // 3 guests: 0,1,2
        vm.setActiveGuest(2);
        vm.toggleAssign(L1);
        vm.toggleAssign(L2);
        vm.setActiveGuest(0);
        vm.toggleAssign(L3);                        // guest 1 stays empty
        api.quoteResult = new SplitQuoteView(List.of(quote("34.50"), quote("19.55")), null, null);

        assertTrue(vm.quoteSplit(ORDER_ID));

        assertEquals("BY_ITEM", api.lastQuoteReq.mode());
        assertEquals(2, api.lastQuoteReq.bills().size(), "empty guest dropped");
        assertEquals(List.of(L3), api.lastQuoteReq.bills().get(0).lineIds());
        assertEquals(List.of(L1, L2), api.lastQuoteReq.bills().get(1).lineIds());
        assertEquals(2, vm.billCount());
        assertEquals("Guest 1", vm.billLabel(0));
        assertEquals("Guest 3", vm.billLabel(1));
        assertEquals(0, new BigDecimal("34.50").compareTo(vm.billAmount(0)));
        assertEquals(0, new BigDecimal("19.55").compareTo(vm.billAmount(1)));
    }

    @Test
    void evenQuoteUsesWaysAndShares() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.setMode("EVEN");
        assertTrue(vm.setWays(3));
        api.quoteResult = new SplitQuoteView(null, quote("40.25"),
                List.of(new BigDecimal("13.42"), new BigDecimal("13.42"), new BigDecimal("13.41")));

        assertTrue(vm.quoteSplit(ORDER_ID));

        assertEquals("EVEN", api.lastQuoteReq.mode());
        assertEquals(3, api.lastQuoteReq.even().ways());
        assertNull(api.lastQuoteReq.bills());
        assertEquals(3, vm.billCount());
        assertEquals("Guest 3", vm.billLabel(2));
        assertEquals(0, new BigDecimal("13.41").compareTo(vm.billAmount(2)));
        assertFalse(vm.cashChangeAllowed(), "EVEN cash is exact-amount");
    }

    @Test
    void waysAreBounded2To8() {
        SplitViewModel vm = loadedVm(new FakeDiningApi());
        vm.setMode("EVEN");
        assertFalse(vm.setWays(1));
        assertFalse(vm.setWays(9));
        assertEquals(2, vm.ways());
        assertTrue(vm.setWays(8));
        assertEquals(8, vm.ways());
    }

    @Test
    void guestCountBounded2To6() {
        SplitViewModel vm = loadedVm(new FakeDiningApi());
        assertEquals(2, vm.guestCount());
        assertTrue(vm.addGuest());  // 3
        assertTrue(vm.addGuest());  // 4
        assertTrue(vm.addGuest());  // 5
        assertTrue(vm.addGuest());  // 6
        assertFalse(vm.addGuest()); // capped
        assertEquals(6, vm.guestCount());
    }

    @Test
    void partitionChangeInvalidatesTheQuote() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.toggleAssign(L1);
        vm.setActiveGuest(1);
        vm.toggleAssign(L2);
        vm.toggleAssign(L3);
        api.quoteResult = new SplitQuoteView(List.of(quote("34.50"), quote("19.55")), null, null);
        assertTrue(vm.quoteSplit(ORDER_ID));
        assertTrue(vm.quoted());

        vm.toggleAssign(L3);                        // partition changed
        assertFalse(vm.quoted(), "any partition change invalidates the quote");

        vm.toggleAssign(L3);
        assertTrue(vm.quoteSplit(ORDER_ID));
        vm.setMode("EVEN");                         // mode change invalidates too
        assertFalse(vm.quoted());
    }

    @Test
    void cashShortRejectedPerGuestAndCloseBuildsByItemRequest() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.toggleAssign(L1);
        vm.setActiveGuest(1);
        vm.toggleAssign(L2);
        vm.toggleAssign(L3);
        api.quoteResult = new SplitQuoteView(List.of(quote("34.50"), quote("19.55")), null, null);
        assertTrue(vm.quoteSplit(ORDER_ID));

        vm.setMethod(0, "CASH");
        vm.setCashTendered(0, new BigDecimal("20.00"));  // short
        vm.setMethod(1, "CARD");
        assertFalse(vm.canCloseAll(), "short cash blocks close");
        assertEquals(0, new BigDecimal("0").compareTo(vm.changeFor(0).max(BigDecimal.ZERO)));

        vm.setCashTendered(0, new BigDecimal("50.00"));
        assertTrue(vm.canCloseAll());
        assertEquals(0, new BigDecimal("15.50").compareTo(vm.changeFor(0)));

        api.closeResult = List.of(sale("S01-T01-1", "34.50"), sale("S01-T01-2", "19.55"));
        assertTrue(vm.closeAll(ORDER_ID));

        SplitCloseRequest req = api.lastCloseReq;
        assertEquals("BY_ITEM", req.mode());
        assertFalse(req.waiveServiceCharge());
        assertEquals(List.of(L1), req.bills().get(0).lineIds());
        assertEquals("CASH", req.bills().get(0).tenders().get(0).method());
        assertEquals(0, new BigDecimal("34.50").compareTo(req.bills().get(0).tenders().get(0).amount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(req.bills().get(0).tenders().get(0).tendered()));
        assertEquals("CARD", req.bills().get(1).tenders().get(0).method());
        assertNull(req.bills().get(1).tenders().get(0).tendered());
        assertTrue(req.bills().get(0).lineDiscounts().isEmpty());
        assertNull(req.bills().get(0).transactionDiscount());
        assertEquals(2, vm.results().size());
    }

    @Test
    void closeBuildsEvenRequestWithOneMethodPerShare() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.setMode("EVEN");
        vm.setWays(3);
        api.quoteResult = new SplitQuoteView(null, quote("40.25"),
                List.of(new BigDecimal("13.42"), new BigDecimal("13.42"), new BigDecimal("13.41")));
        assertTrue(vm.quoteSplit(ORDER_ID));

        vm.setMethod(0, "CASH");
        vm.setMethod(1, "CARD");
        assertFalse(vm.canCloseAll(), "every share needs a method");
        vm.setMethod(2, "WALLET");
        assertTrue(vm.canCloseAll(), "EVEN cash needs no tendered amount (exact)");

        api.closeResult = List.of(sale("S01-T01-1", "40.25"));
        assertTrue(vm.closeAll(ORDER_ID));
        assertEquals("EVEN", api.lastCloseReq.mode());
        assertEquals(3, api.lastCloseReq.even().ways());
        assertEquals(List.of("CASH", "CARD", "WALLET"), api.lastCloseReq.even().methods());
        assertNull(api.lastCloseReq.bills());
    }

    @Test
    void closeFailureSurfacesErrorAndKeepsNoResults() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.setMode("EVEN");
        api.quoteResult = new SplitQuoteView(null, quote("40.25"),
                List.of(new BigDecimal("20.13"), new BigDecimal("20.12")));
        assertTrue(vm.quoteSplit(ORDER_ID));
        vm.setMethod(0, "CARD");
        vm.setMethod(1, "CARD");
        api.closeError = new ApiException(422, null, "Insufficient tender for bill 2");

        assertFalse(vm.closeAll(ORDER_ID));
        assertTrue(vm.results().isEmpty());
        assertEquals("Insufficient tender for bill 2", vm.errorMessage().get());
    }

    @Test
    void closeAllWorksUnderDeferredDispatcher() {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        FakeDiningApi api = new FakeDiningApi();
        api.orderResult = order(line(L1, "BURGER"), line(L2, "FRIES"), line(L3, "WATER"));
        SplitViewModel vm = new SplitViewModel(api, queue::add);   // defer, don't run
        vm.load(ORDER_ID);
        vm.toggleAssign(L1);
        vm.setActiveGuest(1);
        vm.toggleAssign(L2);
        vm.toggleAssign(L3);
        api.quoteResult = new SplitQuoteView(List.of(quote("34.50"), quote("19.55")), null, null);
        assertTrue(vm.quoteSplit(ORDER_ID), "quoteSplit must read plain fields, not observables");
        vm.setMethod(0, "CARD");
        vm.setMethod(1, "CARD");
        assertTrue(vm.canCloseAll());
        api.closeResult = List.of(sale("S01-T01-1", "34.50"), sale("S01-T01-2", "19.55"));

        assertTrue(vm.closeAll(ORDER_ID),
                "closeAll must build the request from synchronous state before any UI runnable runs");
        assertEquals(2, api.lastCloseReq.bills().size());
        assertEquals(2, vm.results().size(), "results is a plain field, readable pre-drain");

        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("", vm.errorMessage().get());
    }
}
