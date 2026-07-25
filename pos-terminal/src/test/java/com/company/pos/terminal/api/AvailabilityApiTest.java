package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.company.pos.terminal.api.dto.ProductView;
import org.junit.jupiter.api.Test;

class AvailabilityApiTest {

    @Test
    void setAvailabilityPutsToTheRightPathWithBody() throws Exception {
        String body = "{\"sku\":\"SALMON\",\"name\":\"Grilled Salmon\",\"categoryName\":\"Mains\","
                + "\"barcode\":null,\"unitPrice\":42.00,\"active\":true,\"available\":false}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            AvailabilityApi api = new AvailabilityApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ProductView v = api.setAvailability("SALMON", false);
            assertFalse(v.available());
            assertEquals("PUT", stub.lastMethod);
            assertEquals("/products/SALMON/availability", stub.lastPath);
            assertEquals("{\"available\":false}", stub.lastBody);
        }
    }

    @Test
    void listGetsProducts() throws Exception {
        String body = "[{\"sku\":\"SALMON\",\"name\":\"Grilled Salmon\",\"categoryName\":\"Mains\","
                + "\"barcode\":null,\"unitPrice\":42.00,\"active\":true,\"available\":true}]";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            AvailabilityApi api = new AvailabilityApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            assertEquals(1, api.list().size());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/products", stub.lastPath);
        }
    }
}
