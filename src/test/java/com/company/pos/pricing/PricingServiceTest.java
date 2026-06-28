package com.company.pos.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.pricing.api.PricedLine;
import com.company.pos.pricing.api.PricingInput;
import com.company.pos.pricing.application.DefaultPricingService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingServiceTest {

    private final DefaultPricingService pricing = new DefaultPricingService();

    @Test
    void extendsAndRoundsHalfUp() {
        // 4.505 * 3 = 13.515 -> 13.52 (HALF_UP at scale 2)
        List<PricedLine> result = pricing.price(List.of(
                new PricingInput("COLA", "Cola Can", new BigDecimal("3"),
                        new BigDecimal("4.5050"), "SAR")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).extendedPrice()).isEqualByComparingTo("13.52");
        assertThat(result.get(0).sku()).isEqualTo("COLA");
    }

    @Test
    void fractionalQuantitySupported() {
        // 2.50 * 1.5 = 3.75
        List<PricedLine> result = pricing.price(List.of(
                new PricingInput("RICE", "Rice", new BigDecimal("1.5"),
                        new BigDecimal("2.5000"), "SAR")));

        assertThat(result.get(0).extendedPrice()).isEqualByComparingTo("3.75");
    }
}
