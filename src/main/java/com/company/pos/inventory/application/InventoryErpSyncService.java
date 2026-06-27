package com.company.pos.inventory.application;

import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class InventoryErpSyncService implements InventorySync {

    private static final String STREAM = "stock";

    private final ErpClient erpClient;
    private final SyncCursorStore cursors;
    private final StockLevelRepository stock;

    InventoryErpSyncService(ErpClient erpClient, SyncCursorStore cursors, StockLevelRepository stock) {
        this.erpClient = erpClient;
        this.cursors = cursors;
        this.stock = stock;
    }

    @Override
    public int sync() {
        long cursor = cursors.get(STREAM);
        List<ErpStockLevel> batch = erpClient.fetchStockLevelsSince(cursor);
        long maxVersion = cursor;
        int upserted = 0;

        for (ErpStockLevel e : batch) {
            if (e.version() > maxVersion) {
                maxVersion = e.version();
            }
            StockLevel existing = stock.findBySkuAndLocationCode(e.sku(), e.locationCode()).orElse(null);
            if (existing != null && existing.getErpVersion() >= e.version()) {
                continue;
            }
            StockLevel level = existing != null
                    ? existing
                    : new StockLevel(Identifiers.newId(), e.sku(), e.locationCode());
            level.setQuantityOnHand(e.quantityOnHand());
            level.setErpVersion(e.version());
            stock.save(level);
            upserted++;
        }

        if (maxVersion > cursor) {
            cursors.set(STREAM, maxVersion);
        }
        return upserted;
    }
}
