package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.ProductView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the store server's {@code /products} read endpoint. */
public class ProductApi {
    private final ApiClient client;

    public ProductApi(ApiClient client) {
        this.client = client;
    }

    public List<ProductView> list() {
        return client.get("/products", new TypeReference<List<ProductView>>() {});
    }

    public List<ProductView> search(String q) {
        return client.get("/products?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8),
                new TypeReference<List<ProductView>>() {});
    }
}
