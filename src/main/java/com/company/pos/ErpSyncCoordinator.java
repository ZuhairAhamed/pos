package com.company.pos;

import com.company.pos.inventory.api.InventorySync;
import com.company.pos.product.api.ProductSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ErpSyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ErpSyncCoordinator.class);

    private final ProductSync productSync;
    private final InventorySync inventorySync;

    ErpSyncCoordinator(ProductSync productSync, InventorySync inventorySync) {
        this.productSync = productSync;
        this.inventorySync = inventorySync;
    }

    SyncSummary syncAll() {
        log.info("Starting ERP down-sync");
        int products = productSync.sync();
        log.info("Product sync upserted {} (cursor advanced)", products);
        int stock = inventorySync.sync();
        log.info("Stock sync upserted {}", stock);
        log.info("ERP down-sync complete: products={}, stock={}", products, stock);
        return new SyncSummary(products, stock);
    }

    public record SyncSummary(int products, int stock) {
    }
}
