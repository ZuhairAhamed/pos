package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.StockView;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class InventoryServiceTest {

    @Autowired
    StockLevelRepository stock;
    @Autowired
    InventoryService inventory;

    private StockLevel level(String sku, String location, String qty) {
        StockLevel s = new StockLevel(Identifiers.newId(), sku, location);
        s.setQuantityOnHand(new BigDecimal(qty));
        return s;
    }

    @Test
    void onHandSumsAcrossLocations() {
        stock.save(level("COLA", "MAIN", "10"));
        stock.save(level("COLA", "BACK", "5"));

        StockView view = inventory.onHand("COLA").orElseThrow();
        assertThat(view.quantityOnHand()).isEqualByComparingTo("15");
    }

    @Test
    void onHandEmptyForUnknownSku() {
        assertThat(inventory.onHand("NOPE")).isEmpty();
    }
}
