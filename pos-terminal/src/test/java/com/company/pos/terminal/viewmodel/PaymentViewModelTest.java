package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import com.company.pos.terminal.viewmodel.PaymentViewModel.CheckoutGateway;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentViewModelTest {

    private static SaleView sale28_75() {
        return new SaleView(UUID.randomUUID(), "S01-T01-1", new BigDecimal("25.00"),
                new BigDecimal("3.75"), BigDecimal.ZERO, new BigDecimal("28.75"), "SAR",
                BigDecimal.ZERO, List.of(), List.of());
    }

    /** Records tenders and returns a fixed sale. */
    private static final class RecordingGateway implements CheckoutGateway {
        List<TenderInput> received;
        int calls;
        @Override public SaleView checkout(List<TenderInput> tenders) {
            received = new ArrayList<>(tenders);
            calls++;
            return sale28_75();
        }
    }

    @Test
    void shortCashRejectedBeforeCheckout() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CASH", new BigDecimal("20.00"));
        assertEquals(0, gw.calls, "checkout must not run on a short tender");
        assertTrue(vm.errorMessage().get().toLowerCase().contains("insufficient"));
        assertNull(vm.sale().get());
        assertFalse(vm.paid().get());
    }

    @Test
    void fullCashCheckoutComputesChange() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CASH", new BigDecimal("30.00"));
        assertTrue(vm.paid().get());
        assertEquals(1, gw.calls);
        assertEquals("CASH", gw.received.get(0).method());
        assertEquals(new BigDecimal("28.75"), gw.received.get(0).amount());
        assertEquals(new BigDecimal("30.00"), gw.received.get(0).tendered());
        assertTrue(vm.changeText().get().contains("1.25"));
    }

    @Test
    void walletFullCheckout() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("WALLET", null);
        assertTrue(vm.paid().get());
        assertEquals("WALLET", gw.received.get(0).method());
        assertEquals(new BigDecimal("28.75"), gw.received.get(0).amount());
        assertNull(gw.received.get(0).tendered());
    }

    @Test
    void splitCardThenCashFinalizesWhenCovered() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.addTender("CARD", new BigDecimal("20.00"), null);
        assertFalse(vm.paid().get(), "not covered yet");
        assertTrue(vm.remainingText().get().contains("8.75"));
        assertEquals(0, gw.calls);
        vm.addTender("CASH", new BigDecimal("8.75"), new BigDecimal("10.00"));
        vm.finalizeSale();
        assertTrue(vm.paid().get());
        assertEquals(2, gw.received.size());
        assertTrue(vm.changeText().get().contains("1.25"));
    }

    @Test
    void finalizeRejectedWhenUnderTendered() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.addTender("CARD", new BigDecimal("10.00"), null);
        vm.finalizeSale();
        assertFalse(vm.paid().get());
        assertEquals(0, gw.calls);
        assertTrue(vm.errorMessage().get().toLowerCase().contains("remaining"));
    }

    @Test
    void checkoutFailureSurfacesErrorAndDoesNotMarkPaid() {
        CheckoutGateway gw = tenders -> { throw new ApiException(409, null, "cart already closed"); };
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        assertFalse(vm.paid().get());
        assertNull(vm.sale().get());
        assertEquals("cart already closed", vm.errorMessage().get());
    }

    @Test
    void reprintCallsSalesApiWithSaleId() {
        RecordingGateway gw = new RecordingGateway();
        List<UUID> reprinted = new ArrayList<>();
        SalesApi sales = new SalesApi(null) {
            @Override public void reprint(UUID saleId) { reprinted.add(saleId); }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        vm.reprint();
        assertEquals(1, reprinted.size());
        assertEquals(vm.sale().get().id(), reprinted.get(0));
    }

    @Test
    void reprintFailureSurfacesError() {
        RecordingGateway gw = new RecordingGateway();
        SalesApi sales = new SalesApi(null) {
            @Override public void reprint(java.util.UUID saleId) {
                throw new ApiException(500, null, "printer offline");
            }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        vm.reprint();
        assertEquals("printer offline", vm.errorMessage().get());
    }

    @Test
    void payFullFinalizesUnderDeferredDispatcher() {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        java.util.function.Consumer<Runnable> deferred = queue::add;   // defer, don't run
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"), deferred);
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);                 // runs "off-thread": observable writes are queued
        // Gateway MUST have been called even though no queued UI runnable has executed yet:
        assertEquals(1, gw.calls, "payFull must finalize using synchronous committed state, not deferred observable");
        assertEquals(new BigDecimal("28.75"), gw.received.get(0).amount());
        // Drain the UI queue and confirm observable state caught up:
        while (!queue.isEmpty()) queue.poll().run();
        assertTrue(vm.paid().get());
        assertEquals(1, vm.tenders().size());
    }

    @Test
    void tenderRejectedBeforeAuthoritativeTotalLoaded() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        // no setAuthoritativeTotal — the quote hasn't loaded
        vm.payFull("CARD", null);
        assertEquals(0, gw.calls, "must not checkout before the authoritative total is known");
        assertTrue(vm.errorMessage().get().toLowerCase().contains("total"));
        assertFalse(vm.paid().get());
    }

    @Test
    void tendersAgainstAuthoritativeTotalNotEstimate() {
        RecordingGateway gw = new RecordingGateway();
        // estimate 39.00 (pre-tax) but authoritative 44.85 (tax-in)
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("39.00"));
        vm.setAuthoritativeTotal(new BigDecimal("44.85"));
        assertTrue(vm.remainingText().get().contains("44.85"));
        vm.payFull("CARD", null);
        assertEquals(1, gw.calls);
        assertEquals(new BigDecimal("44.85"), gw.received.get(0).amount());
    }

    @Test
    void emailReceiptCallsSalesApiWithSaleIdAndAddress() {
        RecordingGateway gw = new RecordingGateway();
        List<UUID> ids = new ArrayList<>();
        List<String> addrs = new ArrayList<>();
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) { ids.add(saleId); addrs.add(email); }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        assertTrue(vm.emailReceipt("guest@example.com"));
        assertEquals(1, ids.size());
        assertEquals(vm.sale().get().id(), ids.get(0));
        assertEquals("guest@example.com", addrs.get(0));
    }

    @Test
    void emailReceiptRejectsBlankAndMalformedWithoutCallingApi() {
        RecordingGateway gw = new RecordingGateway();
        int[] calls = {0};
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) { calls[0]++; }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        assertFalse(vm.emailReceipt("   "));
        assertFalse(vm.emailReceipt("not-an-email"));
        assertEquals(0, calls[0]);
        assertTrue(vm.errorMessage().get().toLowerCase().contains("email"));
    }

    @Test
    void emailReceiptReturnsFalseBeforeAnySale() {
        RecordingGateway gw = new RecordingGateway();
        int[] calls = {0};
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) { calls[0]++; }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        assertFalse(vm.emailReceipt("guest@example.com"));
        assertEquals(0, calls[0]);
    }

    @Test
    void emailReceiptFailureSurfacesError() {
        RecordingGateway gw = new RecordingGateway();
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) {
                throw new ApiException(400, null, "invalid email address");
            }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        assertFalse(vm.emailReceipt("x@y.com"));
        assertEquals("invalid email address", vm.errorMessage().get());
    }

    @Test
    void emailReceiptSucceedsUnderDeferredDispatcher() {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        RecordingGateway gw = new RecordingGateway();
        List<String> addrs = new ArrayList<>();
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) { addrs.add(email); }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"), queue::add);
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        while (!queue.isEmpty()) queue.poll().run();          // drain payFull's deferred writes → sale set
        assertTrue(vm.emailReceipt("guest@example.com"),
                "emailReceipt returns synchronously regardless of the UI dispatcher");
        assertEquals(1, addrs.size());
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("", vm.errorMessage().get());
    }
}
