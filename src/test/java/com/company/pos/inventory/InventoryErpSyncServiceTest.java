package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.InventorySync;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class InventoryErpSyncServiceTest {

    @Autowired
    FakeErpClient fake;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    InventoryService inventory;
    @Autowired
    SyncCursorStore cursors;

    @BeforeEach
    void reset() {
        fake.clear();
    }

    @Test
    void syncUpsertsStockAndAdvancesCursor() {
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("12"), 1));

        int upserted = inventorySync.sync();

        assertThat(upserted).isEqualTo(1);
        assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand()).isEqualByComparingTo("12");
        assertThat(cursors.get("stock")).isEqualTo(1);
    }

    @Test
    void newerVersionUpdatesQuantity() {
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("12"), 1));
        inventorySync.sync();

        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("7"), 4));
        inventorySync.sync();

        assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand()).isEqualByComparingTo("7");
        assertThat(cursors.get("stock")).isEqualTo(4);
    }
}
