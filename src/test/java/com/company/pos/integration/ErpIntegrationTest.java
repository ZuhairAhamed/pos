package com.company.pos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.integration.erp.FakeErpClient;
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
class ErpIntegrationTest {

    @Autowired
    ErpClient erpClient;
    @Autowired
    FakeErpClient fake;
    @Autowired
    SyncCursorStore cursors;

    @BeforeEach
    void reset() {
        fake.clear();
    }

    private ErpProduct product(String sku, long version) {
        return new ErpProduct(sku, "Name " + sku, "BEV", "Beverages", "bc" + sku,
                "EA", new BigDecimal("1.00"), "SAR", version, true);
    }

    @Test
    void fetchProductsSinceReturnsOnlyNewerVersionsAscending() {
        fake.addProduct(product("A", 1));
        fake.addProduct(product("B", 3));
        fake.addProduct(product("C", 2));

        assertThat(erpClient.fetchProductsSince(0)).extracting(ErpProduct::sku)
                .containsExactly("A", "C", "B");
        assertThat(erpClient.fetchProductsSince(2)).extracting(ErpProduct::sku)
                .containsExactly("B");
    }

    @Test
    void cursorDefaultsToZeroThenPersists() {
        assertThat(cursors.get("products")).isZero();
        cursors.set("products", 7);
        assertThat(cursors.get("products")).isEqualTo(7);
    }
}
