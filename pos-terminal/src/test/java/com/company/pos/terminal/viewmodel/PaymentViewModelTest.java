package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.CloseOrderRequest;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentViewModelTest {

    private final UUID orderId = UUID.randomUUID();

    private static SaleView sale28_75() {
        return new SaleView(UUID.randomUUID(), "S01-T01-1", new BigDecimal("25.00"),
                new BigDecimal("3.75"), BigDecimal.ZERO, new BigDecimal("28.75"), "SAR",
                BigDecimal.ZERO, java.util.List.of(), java.util.List.of());
    }

    @Test
    void shortCashTenderRejectedBeforeClose() {
        boolean[] closeCalled = {false};
        DiningApi dining = new DiningApi(null) {
            @Override
            public SaleView close(UUID id, CloseOrderRequest req) {
                closeCalled[0] = true;
                return null;
            }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, null, orderId, new BigDecimal("28.75"));
        vm.payCash(new BigDecimal("20.00"));
        assertFalse(closeCalled[0], "close must not be called on a short tender");
        assertTrue(vm.errorMessage().get().toLowerCase().contains("insufficient"));
        assertNull(vm.sale().get());
        assertFalse(vm.paid().get());
    }

    @Test
    void cashPaymentClosesAndComputesChange() {
        SaleView returned = sale28_75();
        TenderInput[] captured = new TenderInput[1];
        DiningApi dining = new DiningApi(null) {
            @Override
            public SaleView close(UUID id, CloseOrderRequest req) {
                captured[0] = req.tenders().get(0);
                return returned;
            }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, null, orderId, new BigDecimal("28.75"));
        vm.payCash(new BigDecimal("30.00"));
        assertNotNull(vm.sale().get());
        assertEquals("S01-T01-1", vm.sale().get().receiptNumber());
        assertTrue(vm.changeText().get().contains("1.25"));
        assertTrue(vm.paid().get());
        // authoritative totals exposed
        assertEquals(new BigDecimal("28.75"), vm.sale().get().grandTotal());
        // CASH tender: amount = estimate, tendered = cash handed over
        assertEquals("CASH", captured[0].method());
        assertEquals(new BigDecimal("28.75"), captured[0].amount());
        assertEquals(new BigDecimal("30.00"), captured[0].tendered());
    }

    @Test
    void exactCashTenderCloses() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public SaleView close(UUID id, CloseOrderRequest req) {
                return sale28_75();
            }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, null, orderId, new BigDecimal("28.75"));
        vm.payCash(new BigDecimal("28.75"));
        assertTrue(vm.paid().get());
        assertTrue(vm.changeText().get().contains("0.00"));
    }

    @Test
    void cardPaymentCloses() {
        TenderInput[] captured = new TenderInput[1];
        DiningApi dining = new DiningApi(null) {
            @Override
            public SaleView close(UUID id, CloseOrderRequest req) {
                captured[0] = req.tenders().get(0);
                assertTrue(req.lineDiscounts().isEmpty());
                assertNull(req.transactionDiscount());
                assertFalse(req.waiveServiceCharge());
                return sale28_75();
            }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, null, orderId, new BigDecimal("28.75"));
        vm.payCard();
        assertTrue(vm.paid().get());
        assertNotNull(vm.sale().get());
        assertEquals("CARD", captured[0].method());
        assertEquals(new BigDecimal("28.75"), captured[0].amount());
        assertNull(captured[0].tendered());
    }

    @Test
    void closeFailureSurfacesErrorAndDoesNotMarkPaid() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public SaleView close(UUID id, CloseOrderRequest req) {
                throw new ApiException(409, null, "table already closed");
            }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, null, orderId, new BigDecimal("28.75"));
        vm.payCard();
        assertFalse(vm.paid().get());
        assertNull(vm.sale().get());
        assertEquals("table already closed", vm.errorMessage().get());
    }

    @Test
    void reprintCallsSalesApiWithSaleId() {
        SaleView returned = sale28_75();
        DiningApi dining = new DiningApi(null) {
            @Override
            public SaleView close(UUID id, CloseOrderRequest req) {
                return returned;
            }
        };
        List<UUID> reprinted = new java.util.ArrayList<>();
        SalesApi sales = new SalesApi(null) {
            @Override
            public void reprint(UUID saleId) {
                reprinted.add(saleId);
            }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, sales, orderId, new BigDecimal("28.75"));
        vm.payCard();
        vm.reprint();
        assertEquals(List.of(returned.id()), reprinted);
    }

    @Test
    void reprintFailureSurfacesError() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public SaleView close(UUID id, CloseOrderRequest req) {
                return sale28_75();
            }
        };
        SalesApi sales = new SalesApi(null) {
            @Override
            public void reprint(UUID saleId) {
                throw new ApiException(500, null, "printer offline");
            }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, sales, orderId, new BigDecimal("28.75"));
        vm.payCard();
        vm.reprint();
        assertEquals("printer offline", vm.errorMessage().get());
    }
}
