package com.company.pos.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductPriceChanged;
import com.company.pos.product.api.ProductSync;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ProductPriceChangedTest {

    @Autowired
    FakeErpClient fake;

    @Autowired
    ProductSync productSync;

    @Autowired
    EventCapture eventCapture;

    @BeforeEach
    void reset() {
        fake.clear();
        eventCapture.clear();
    }

    private ErpProduct erpProduct(String sku, BigDecimal price, long version) {
        return new ErpProduct(sku, "Product " + sku, "BEV", "Beverages", "bc" + sku, "EA",
                price, "SAR", version, true);
    }

    @Test
    void priceChangeOnExistingSkuPublishesEvent() {
        // ARRANGE: stage SKU "P1" @ 5.00 (v1) and sync to establish the product
        fake.addProduct(erpProduct("P1", new BigDecimal("5.00"), 1));
        productSync.sync();
        eventCapture.clear(); // reset after initial sync

        // Stage the same SKU at v2 with a new price
        fake.addProduct(erpProduct("P1", new BigDecimal("7.00"), 2));

        // ACT
        productSync.sync();

        // ASSERT
        List<ProductPriceChanged> published = eventCapture.getEvents();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).sku()).isEqualTo("P1");
        assertThat(published.get(0).newPrice()).isEqualByComparingTo(new BigDecimal("7.00"));
        assertThat(published.get(0).oldPrice()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(published.get(0).erpVersion()).isEqualTo(2L);
    }

    @Test
    void brandNewSkuDoesNotPublishEvent() {
        // ARRANGE: brand-new SKU "NEWSKU" that has never been synced
        fake.addProduct(erpProduct("NEWSKU", new BigDecimal("3.00"), 1));

        // ACT
        productSync.sync();

        // ASSERT: no price-changed event for a new product
        assertThat(eventCapture.getEvents()).isEmpty();
    }

    @Test
    void samePriceResynchDoesNotPublishEvent() {
        // ARRANGE: sync SKU "P2" at 4.00 (v1) first
        fake.addProduct(erpProduct("P2", new BigDecimal("4.00"), 1));
        productSync.sync();
        eventCapture.clear(); // reset after initial sync

        // Re-stage same SKU with SAME price but higher version (e.g. name change, not price change)
        fake.addProduct(erpProduct("P2", new BigDecimal("4.00"), 2));

        // ACT
        productSync.sync();

        // ASSERT: no price-changed event when price is unchanged
        assertThat(eventCapture.getEvents()).isEmpty();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        EventCapture eventCapture() {
            return new EventCaptureBean();
        }

        static class EventCaptureBean implements EventCapture {
            private final List<ProductPriceChanged> events = new ArrayList<>();

            // Fires synchronously within the publisher's transaction — correct for DomainEvents.publish();
            // a @TransactionalEventListener(AFTER_COMMIT) would never fire under this rolled-back test
            // and silently invert these assertions.
            @EventListener
            public void on(ProductPriceChanged event) {
                events.add(event);
            }

            @Override
            public List<ProductPriceChanged> getEvents() {
                return List.copyOf(events);
            }

            @Override
            public void clear() {
                events.clear();
            }
        }
    }

    interface EventCapture {
        List<ProductPriceChanged> getEvents();
        void clear();
    }
}
