package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.inventory.api.LowStockItem;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
@TestPropertySource(properties = "pos.inventory.reorder-level=100")
class InventoryLowStockTest {

    @Autowired InventoryService inventory;
    @Autowired InventorySync inventorySync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addStockLevel(new ErpStockLevel("LOW", "MAIN", new BigDecimal("5"), 1));
        fake.addStockLevel(new ErpStockLevel("OK", "MAIN", new BigDecimal("500"), 2));
        inventorySync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void listsOnlyRowsBelowReorderLevel() {
        List<LowStockItem> low = inventory.listLowStock();

        assertThat(low).hasSize(1);
        assertThat(low.get(0).sku()).isEqualTo("LOW");
        assertThat(low.get(0).onHand()).isEqualByComparingTo("5");
        assertThat(low.get(0).reorderLevel()).isEqualByComparingTo("100");
    }
}
