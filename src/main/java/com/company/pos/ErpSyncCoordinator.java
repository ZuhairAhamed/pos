package com.company.pos;

import com.company.pos.inventory.api.InventorySync;
import com.company.pos.product.api.ProductSync;
import org.springframework.stereotype.Component;

@Component
class ErpSyncCoordinator {

    private final ProductSync productSync;
    private final InventorySync inventorySync;

    ErpSyncCoordinator(ProductSync productSync, InventorySync inventorySync) {
        this.productSync = productSync;
        this.inventorySync = inventorySync;
    }

    SyncSummary syncAll() {
        int products = productSync.sync();
        int stock = inventorySync.sync();
        return new SyncSummary(products, stock);
    }

    public record SyncSummary(int products, int stock) {
    }
}
