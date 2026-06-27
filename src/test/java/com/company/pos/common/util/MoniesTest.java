package com.company.pos.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import javax.money.MonetaryAmount;
import javax.money.MonetaryException;
import org.junit.jupiter.api.Test;

class MoniesTest {

    @Test
    void createsAndAddsSameCurrency() {
        MonetaryAmount ten = Monies.of(new BigDecimal("10.00"), "SAR");
        MonetaryAmount five = Monies.of(new BigDecimal("5.00"), "SAR");

        MonetaryAmount sum = ten.add(five);

        assertThat(sum.getNumber().numberValue(BigDecimal.class)).isEqualByComparingTo("15.00");
        assertThat(sum.getCurrency().getCurrencyCode()).isEqualTo("SAR");
    }

    @Test
    void zeroHasGivenCurrency() {
        MonetaryAmount zero = Monies.zero("SAR");

        assertThat(zero.isZero()).isTrue();
        assertThat(zero.getCurrency().getCurrencyCode()).isEqualTo("SAR");
    }

    @Test
    void rejectsAddingDifferentCurrencies() {
        MonetaryAmount sar = Monies.of(new BigDecimal("10.00"), "SAR");
        MonetaryAmount usd = Monies.of(new BigDecimal("10.00"), "USD");

        assertThatThrownBy(() -> sar.add(usd)).isInstanceOf(MonetaryException.class);
    }
}
