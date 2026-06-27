package com.company.pos.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductSync;
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
class ProductErpSyncServiceTest {

    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    ProductCatalog catalog;
    @Autowired
    SyncCursorStore cursors;

    @BeforeEach
    void reset() {
        fake.clear();
    }

    private ErpProduct erpProduct(String sku, String name, long version) {
        return new ErpProduct(sku, name, "BEV", "Beverages", "bc" + sku, "EA",
                new BigDecimal("2.00"), "SAR", version, true);
    }

    @Test
    void syncUpsertsNewProductsAndAdvancesCursor() {
        fake.addProduct(erpProduct("COLA", "Cola Can", 1));
        fake.addProduct(erpProduct("WATER", "Spring Water", 2));

        int upserted = productSync.sync();

        assertThat(upserted).isEqualTo(2);
        assertThat(catalog.findBySku("COLA")).isPresent();
        assertThat(cursors.get("products")).isEqualTo(2);
    }

    @Test
    void resyncIsIdempotentWhenNothingNew() {
        fake.addProduct(erpProduct("COLA", "Cola Can", 1));
        productSync.sync();

        assertThat(productSync.sync()).isZero();
    }

    @Test
    void newerVersionUpdatesExistingProduct() {
        fake.addProduct(erpProduct("COLA", "Cola Can", 1));
        productSync.sync();

        fake.addProduct(erpProduct("COLA", "Cola Can 330ml", 3));
        int upserted = productSync.sync();

        assertThat(upserted).isEqualTo(1);
        assertThat(catalog.findBySku("COLA").orElseThrow().name()).isEqualTo("Cola Can 330ml");
        assertThat(cursors.get("products")).isEqualTo(3);
    }
}
