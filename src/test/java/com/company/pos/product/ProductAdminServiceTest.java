package com.company.pos.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.product.api.CategoryView;
import com.company.pos.product.api.CreateProductCommand;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductChangeType;
import com.company.pos.product.api.ProductChanged;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.api.UpdateProductCommand;
import com.company.pos.product.application.ProductAdminService;
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
class ProductAdminServiceTest {

    @Autowired ProductAdminService svc;
    @Autowired ProductCatalog catalog;
    @Autowired Events events;

    @BeforeEach
    void reset() {
        events.clear();
    }

    private CreateProductCommand cola() {
        return new CreateProductCommand("COLA", "Cola", null, "Beverages",
                new BigDecimal("5.00"), "SAR", "EA", "bcCOLA");
    }

    @Test
    void createsProductAndPublishesCreated() {
        ProductView v = svc.createProduct(cola());
        assertThat(v.sku()).isEqualTo("COLA");
        assertThat(v.unitPrice()).isEqualByComparingTo("5.00");
        assertThat(v.active()).isTrue();
        assertThat(v.categoryName()).isEqualTo("Beverages");
        assertThat(events.typesFor("COLA")).contains(ProductChangeType.CREATED);
    }

    @Test
    void rejectsDuplicateSku() {
        svc.createProduct(cola());
        assertThatThrownBy(() -> svc.createProduct(cola())).isInstanceOf(DomainException.class);
    }

    @Test
    void createDefaultsCurrencyAndUom() {
        ProductView v = svc.createProduct(new CreateProductCommand("WATER", "Water", null, null,
                new BigDecimal("2.00"), null, null, null));
        assertThat(v.currencyCode()).isEqualTo("SAR");   // SettingKey.CURRENCY_CODE default
        assertThat(v.unitOfMeasure()).isEqualTo("EA");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> svc.createProduct(new CreateProductCommand("X", "  ", null, null,
                new BigDecimal("1.00"), "SAR", "EA", null))).isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> svc.createProduct(new CreateProductCommand("X", "X", null, null,
                new BigDecimal("-1.00"), "SAR", "EA", null))).isInstanceOf(DomainException.class);
    }

    @Test
    void updatesFieldsAndPublishesPriceChanged() {
        svc.createProduct(cola());
        events.clear();
        ProductView v = svc.updateProduct("COLA", new UpdateProductCommand("Diet Cola", null,
                "Soft Drinks", new BigDecimal("6.50"), "SAR", "EA", "bcCOLA"));
        assertThat(v.name()).isEqualTo("Diet Cola");
        assertThat(v.unitPrice()).isEqualByComparingTo("6.50");
        assertThat(v.categoryName()).isEqualTo("Soft Drinks");
        assertThat(events.typesFor("COLA"))
                .contains(ProductChangeType.UPDATED, ProductChangeType.PRICE_CHANGED);
    }

    @Test
    void updateWithSamePriceDoesNotPublishPriceChanged() {
        svc.createProduct(cola());
        events.clear();
        svc.updateProduct("COLA", new UpdateProductCommand("Cola", null, "Beverages",
                new BigDecimal("5.00"), "SAR", "EA", "bcCOLA"));
        assertThat(events.typesFor("COLA")).contains(ProductChangeType.UPDATED)
                .doesNotContain(ProductChangeType.PRICE_CHANGED);
    }

    @Test
    void inlineCategoryReuseDoesNotDuplicate() {
        svc.createProduct(new CreateProductCommand("A", "A", null, "Beverages",
                new BigDecimal("1.00"), "SAR", "EA", null));
        svc.createProduct(new CreateProductCommand("B", "B", null, "Beverages",
                new BigDecimal("2.00"), "SAR", "EA", null));
        assertThat(svc.listCategories()).extracting(CategoryView::name)
                .filteredOn("Beverages"::equals).hasSize(1);
    }

    @Test
    void picksExistingCategoryByCode() {
        svc.createProduct(new CreateProductCommand("A", "A", null, "Beverages",
                new BigDecimal("1.00"), "SAR", "EA", null));
        // derived code for "Beverages" is BEVERAGES
        ProductView v = svc.createProduct(new CreateProductCommand("C", "C", "BEVERAGES", null,
                new BigDecimal("3.00"), "SAR", "EA", null));
        assertThat(v.categoryName()).isEqualTo("Beverages");
        assertThat(svc.listCategories()).hasSize(1);
    }

    @Test
    void updateWithNoCategoryLeavesExistingCategory() {
        svc.createProduct(cola()); // creates with categoryName "Beverages"
        events.clear();
        ProductView v = svc.updateProduct("COLA",
                new UpdateProductCommand("Cola", null, null, new BigDecimal("5.00"), "SAR", "EA", null));
        assertThat(v.categoryName()).isEqualTo("Beverages");
    }

    @Test
    void updateUnknownSkuThrowsNotFound() {
        assertThatThrownBy(() -> svc.updateProduct("NOPE",
                new UpdateProductCommand("X", null, null, new BigDecimal("1.00"), "SAR", "EA", null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateUnknownSkuThrowsNotFound() {
        assertThatThrownBy(() -> svc.deactivate("NOPE"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void setAvailabilityTogglesFlagAndPersists() {
        svc.createProduct(new CreateProductCommand("SALMON", "Grilled Salmon", null,
                "Mains", new BigDecimal("42.00"), "SAR", "EA", null));

        ProductView off = svc.setAvailability("SALMON", false);
        assertThat(off.available()).isFalse();
        assertThat(catalog.findBySku("SALMON").orElseThrow().available()).isFalse();

        ProductView on = svc.setAvailability("SALMON", true);
        assertThat(on.available()).isTrue();
    }

    @Test
    void deactivateThenReactivate() {
        svc.createProduct(cola());
        events.clear();
        svc.deactivate("COLA");
        assertThat(catalog.findBySku("COLA").orElseThrow().active()).isFalse();
        assertThat(events.typesFor("COLA")).contains(ProductChangeType.DEACTIVATED);
        events.clear();
        svc.reactivate("COLA");
        assertThat(catalog.findBySku("COLA").orElseThrow().active()).isTrue();
        assertThat(events.typesFor("COLA")).contains(ProductChangeType.REACTIVATED);
    }

    interface Events {
        List<ProductChangeType> typesFor(String entityRef);
        void clear();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        Events events() {
            return new EventsBean();
        }

        static class EventsBean implements Events {
            private final List<ProductChanged> captured = new ArrayList<>();

            // @EventListener fires synchronously on publish (within the still-open tx) — correct for
            // asserting DomainEvents.publish(); a @TransactionalEventListener(AFTER_COMMIT) would never
            // fire under this rolled-back test.
            @EventListener
            void on(ProductChanged e) {
                captured.add(e);
            }

            @Override
            public List<ProductChangeType> typesFor(String entityRef) {
                return captured.stream().filter(e -> e.entityRef().equals(entityRef))
                        .map(ProductChanged::type).toList();
            }

            @Override
            public void clear() {
                captured.clear();
            }
        }
    }
}
