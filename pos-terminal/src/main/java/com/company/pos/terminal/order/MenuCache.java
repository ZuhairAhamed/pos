package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Terminal-side, read-only index over the store's product catalogue. Built once from a
 * {@code List<ProductView>} and used to look up base prices and browse products by category
 * for the client-side order estimate. Money is {@link BigDecimal}, never double.
 */
public final class MenuCache {

    static final String OTHER = "Other";

    private final Map<String, ProductView> bySku = new LinkedHashMap<>();
    private final Map<String, List<ProductView>> byCategory = new LinkedHashMap<>();

    public MenuCache(List<ProductView> products) {
        if (products != null) {
            for (ProductView p : products) {
                if (p == null) {
                    continue;
                }
                bySku.put(p.sku(), p);
                String cat = (p.categoryName() == null || p.categoryName().isBlank()) ? OTHER : p.categoryName();
                byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(p);
            }
        }
    }

    /** Base unit price for the sku, or {@link BigDecimal#ZERO} for an unknown sku. */
    public BigDecimal basePrice(String sku) {
        ProductView p = bySku.get(sku);
        return (p == null || p.unitPrice() == null) ? BigDecimal.ZERO : p.unitPrice();
    }

    /**
     * O(1) product-name lookup by sku, backed by the {@code bySku} index. Falls back to the sku
     * itself for an unknown/absent sku (or a product with a null name) so callers always render
     * something sensible.
     */
    public String nameFor(String sku) {
        ProductView p = bySku.get(sku);
        return (p == null || p.name() == null) ? sku : p.name();
    }

    /** Distinct category names in first-encounter order; null/blank collapse to {@code "Other"}. */
    public List<String> categories() {
        return new ArrayList<>(byCategory.keySet());
    }

    public List<ProductView> productsInCategory(String category) {
        return Collections.unmodifiableList(byCategory.getOrDefault(category, List.of()));
    }
}
