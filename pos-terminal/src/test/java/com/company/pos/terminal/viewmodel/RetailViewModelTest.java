package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.CartApi;
import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.CartView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.order.MenuCache;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetailViewModelTest {

    private static final UUID CART_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static MenuCache cache() {
        return new MenuCache(List.of(
                new ProductView("LATTE", "Latte", "Drinks", "6291041500213", new BigDecimal("14.00"))));
    }

    private static CartView cartWith(String sku, String qty, String unit) {
        return new CartView(CART_ID, "OPEN", "SAR", null, List.of(
                new CartLineView(UUID.randomUUID(), sku, sku, new BigDecimal(qty),
                        new BigDecimal(unit), new BigDecimal(unit), "SAR", List.of())));
    }

    /** Stub CartApi that records calls and returns a canned cart. */
    private static final class StubCartApi extends CartApi {
        CartView next;
        String addedSku;
        StubCartApi() { super(null); }
        @Override public UUID createCart() { return CART_ID; }
        @Override public CartView addLine(UUID cartId, String sku, BigDecimal qty, List<UUID> ids) {
            addedSku = sku; return next;
        }
    }

    @Test
    void startCreatesCartAndStartsEmpty() {
        StubCartApi api = new StubCartApi();
        RetailViewModel vm = new RetailViewModel(api, cache());
        vm.start();
        assertEquals(CART_ID, vm.cartId());
        assertTrue(vm.lines().isEmpty());
        assertEquals("0.00", vm.subtotalText().get());
    }

    @Test
    void addBySkuUpdatesLinesSubtotalAndPulse() {
        StubCartApi api = new StubCartApi();
        api.next = cartWith("LATTE", "2", "14.00");
        RetailViewModel vm = new RetailViewModel(api, cache());
        vm.start();
        int before = vm.itemAddedCount().get();
        vm.addBySku("LATTE", BigDecimal.ONE, List.of());
        assertEquals(1, vm.lines().size());
        assertEquals("28.00", vm.subtotalText().get());
        assertEquals(before + 1, vm.itemAddedCount().get(), "item-added pulse signal fires");
    }

    @Test
    void addByBarcodeResolvesToSku() {
        StubCartApi api = new StubCartApi();
        api.next = cartWith("LATTE", "1", "14.00");
        RetailViewModel vm = new RetailViewModel(api, cache());
        vm.start();
        String sku = vm.addByBarcode("6291041500213");
        assertEquals("LATTE", sku);
        assertEquals("LATTE", api.addedSku);
    }

    @Test
    void addByUnknownBarcodeSetsErrorAndDoesNotAdd() {
        StubCartApi api = new StubCartApi();
        RetailViewModel vm = new RetailViewModel(api, cache());
        vm.start();
        String sku = vm.addByBarcode("0000000000000");
        assertNull(sku);
        assertNull(api.addedSku);
        assertTrue(vm.errorMessage().get().toLowerCase().contains("barcode"));
    }
}
