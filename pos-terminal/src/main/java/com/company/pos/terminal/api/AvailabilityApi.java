package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.AvailabilityRequest;
import com.company.pos.terminal.api.dto.ProductView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the 86-board: reads the product list and toggles per-SKU availability. */
public class AvailabilityApi {

    private final ApiClient client;

    public AvailabilityApi(ApiClient client) {
        this.client = client;
    }

    /** GET /products — the full catalogue (each carries active + available). */
    public List<ProductView> list() {
        return client.get("/products", new TypeReference<List<ProductView>>() {});
    }

    /** PUT /products/{sku}/availability — 86 (false) or restore (true) an item. */
    public ProductView setAvailability(String sku, boolean available) {
        return client.put("/products/" + sku + "/availability", new AvailabilityRequest(available),
                new TypeReference<ProductView>() {});
    }
}
