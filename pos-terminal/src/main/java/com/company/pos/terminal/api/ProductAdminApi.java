package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the store server's product admin endpoints (writes ADMIN-gated) plus the
 *  read-only {@code /products} list and {@code /categories}. Methods are non-final so view-model
 *  tests can subclass with fakes. */
public class ProductAdminApi {

    private final ApiClient client;

    public ProductAdminApi(ApiClient client) {
        this.client = client;
    }

    /** GET /products — all products (active and inactive); the screen filters inactive client-side. */
    public List<ProductAdminView> list() {
        return client.get("/products", new TypeReference<List<ProductAdminView>>() {});
    }

    /** GET /categories — existing categories for the form dropdown. */
    public List<CategoryView> listCategories() {
        return client.get("/categories", new TypeReference<List<CategoryView>>() {});
    }

    /** POST /products — create a product. */
    public ProductAdminView create(CreateProductRequest req) {
        return client.post("/products", req, new TypeReference<ProductAdminView>() {});
    }

    /** PUT /products/{sku} — edit a product. */
    public ProductAdminView update(String sku, UpdateProductRequest req) {
        return client.put("/products/" + sku, req, new TypeReference<ProductAdminView>() {});
    }

    /** POST /products/{sku}/deactivate. */
    public void deactivate(String sku) {
        client.post("/products/" + sku + "/deactivate", null, null);
    }

    /** POST /products/{sku}/reactivate. */
    public void reactivate(String sku) {
        client.post("/products/" + sku + "/reactivate", null, null);
    }
}
