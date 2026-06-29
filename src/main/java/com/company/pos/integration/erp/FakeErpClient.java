package com.company.pos.integration.erp;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.api.ReturnUpload;
import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.api.StockMovementUpload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** In-memory ERP stand-in. Down-sync for Phase 1; up-sync (uploads) added in Phase 3b. */
@Component
public class FakeErpClient implements ErpClient {

    private final List<ErpProduct> products = new ArrayList<>();
    private final List<ErpStockLevel> stockLevels = new ArrayList<>();
    private final Map<UUID, SaleUpload> uploadedSales = new LinkedHashMap<>();
    private final Map<String, List<StockMovementUpload>> movementBatches = new LinkedHashMap<>();
    private final Map<UUID, ReturnUpload> uploadedReturns = new LinkedHashMap<>();
    private volatile boolean available = true;

    public void addProduct(ErpProduct product) {
        products.add(product);
    }

    public void addStockLevel(ErpStockLevel level) {
        stockLevels.add(level);
    }

    /** Simulate the ERP link being up (true) or down (false). When down, uploads throw. */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    public List<SaleUpload> uploadedSales() {
        return new ArrayList<>(uploadedSales.values());
    }

    public List<ReturnUpload> uploadedReturns() {
        return new ArrayList<>(uploadedReturns.values());
    }

    public Map<String, List<StockMovementUpload>> uploadedMovementBatches() {
        return new LinkedHashMap<>(movementBatches);
    }

    public void clear() {
        products.clear();
        stockLevels.clear();
        uploadedSales.clear();
        movementBatches.clear();
        uploadedReturns.clear();
        available = true;
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

    @Override
    public void uploadSale(SaleUpload sale) {
        requireAvailable();
        uploadedSales.putIfAbsent(sale.saleId(), sale); // idempotent on saleId
    }

    @Override
    public void uploadStockMovements(String saleId, List<StockMovementUpload> movements) {
        requireAvailable();
        movementBatches.putIfAbsent(saleId, List.copyOf(movements)); // idempotent on saleId
    }

    @Override
    public void uploadReturn(ReturnUpload ret) {
        requireAvailable();
        uploadedReturns.putIfAbsent(ret.returnId(), ret); // idempotent on returnId
    }

    private void requireAvailable() {
        if (!available) {
            throw new RuntimeException("ERP offline");
        }
    }
}
