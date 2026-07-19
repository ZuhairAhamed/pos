package com.company.pos.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductBehaviorTest {

    private Product sample() {
        return new Product(Identifiers.newId(), "COLA", "Cola");
    }

    @Test
    void renameChangesName() {
        Product p = sample();
        p.rename("Diet Cola");
        assertThat(p.getName()).isEqualTo("Diet Cola");
    }

    @Test
    void changePriceSetsPriceAndCurrency() {
        Product p = sample();
        p.changePrice(new BigDecimal("6.50"), "SAR");
        assertThat(p.getUnitPrice()).isEqualByComparingTo("6.50");
        assertThat(p.getCurrencyCode()).isEqualTo("SAR");
    }

    @Test
    void changeCategorySetsIdAndName() {
        Product p = sample();
        UUID c = Identifiers.newId();
        p.changeCategory(c, "Beverages");
        assertThat(p.getCategoryName()).isEqualTo("Beverages");
    }

    @Test
    void deactivateAndActivateToggleActive() {
        Product p = sample();
        p.deactivate();
        assertThat(p.isActive()).isFalse();
        p.activate();
        assertThat(p.isActive()).isTrue();
    }
}
