package com.company.pos.integration.erp;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** In-memory ERP stand-in for Phase 1. Replaced by a concrete vendor adapter later. */
@Component
public class FakeErpClient implements ErpClient {

    private final List<ErpProduct> products = new ArrayList<>();
    private final List<ErpStockLevel> stockLevels = new ArrayList<>();

    public void addProduct(ErpProduct product) {
        products.add(product);
    }

    public void addStockLevel(ErpStockLevel level) {
        stockLevels.add(level);
    }

    public void clear() {
        products.clear();
        stockLevels.clear();
    }

    @Override
    public List<ErpProduct> fetchProductsSince(long version) {
        return products.stream()
                .filter(p -> p.version() > version)
                .sorted(Comparator.comparingLong(ErpProduct::version))
                .toList();
    }

    @Override
    public List<ErpStockLevel> fetchStockLevelsSince(long version) {
        return stockLevels.stream()
                .filter(s -> s.version() > version)
                .sorted(Comparator.comparingLong(ErpStockLevel::version))
                .toList();
    }
}
