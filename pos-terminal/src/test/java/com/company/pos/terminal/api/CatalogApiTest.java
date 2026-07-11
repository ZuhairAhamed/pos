package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CatalogApiTest {

    @Test
    void listProductsMapsFields() throws Exception {
        // Mirrors the server's ProductView wire shape: sku, name, categoryName, unitPrice (+ extras ignored).
        String json = "[{\"sku\":\"BURGER\",\"name\":\"Burger\",\"categoryName\":\"Mains\","
                + "\"barcode\":\"123\",\"unitOfMeasure\":\"EACH\",\"unitPrice\":25.00,"
                + "\"currencyCode\":\"SAR\",\"active\":true}]";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            ProductApi api = new ProductApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            List<ProductView> products = api.list();
            assertEquals(1, products.size());
            assertEquals("BURGER", products.get(0).sku());
            assertEquals("Burger", products.get(0).name());
            assertEquals("Mains", products.get(0).categoryName());
            assertEquals(0, new BigDecimal("25.00").compareTo(products.get(0).unitPrice()));
            assertEquals("123", products.get(0).barcode());
            assertEquals("/products", stub.lastPath);
            assertEquals("GET", stub.lastMethod);
        }
    }

    @Test
    void modifierGroupsForSkuMapsOptions() throws Exception {
        String json = "[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Doneness\","
                + "\"minSelections\":1,\"maxSelections\":1,\"options\":["
                + "{\"id\":\"22222222-2222-2222-2222-222222222222\",\"name\":\"Rare\",\"priceDelta\":0.00}]}]";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            MenuApi api = new MenuApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            List<ModifierGroupView> groups = api.modifierGroupsForSku("STEAK");
            assertEquals(1, groups.size());
            ModifierGroupView group = groups.get(0);
            assertEquals(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), group.id());
            assertEquals("Doneness", group.name());
            assertEquals(1, group.minSelections());
            assertEquals(1, group.maxSelections());
            assertEquals(1, group.options().size());
            ModifierOptionView option = group.options().get(0);
            assertEquals(java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"), option.id());
            assertEquals("Rare", option.name());
            assertEquals(0, new BigDecimal("0.00").compareTo(option.priceDelta()));
            assertEquals("/menu/products/STEAK/modifier-groups", stub.lastPath);
            assertEquals("GET", stub.lastMethod);
        }
    }
}
