package com.company.pos.common.util;

import java.math.BigDecimal;
import java.util.Locale;
import javax.money.MonetaryAmount;
import javax.money.format.AmountFormatQueryBuilder;
import javax.money.format.MonetaryAmountFormat;
import javax.money.format.MonetaryFormats;
import org.javamoney.moneta.Money;

public final class Monies {

    private Monies() {
    }

    public static MonetaryAmount of(BigDecimal amount, String currencyCode) {
        return Money.of(amount, currencyCode);
    }

    public static MonetaryAmount zero(String currencyCode) {
        return Money.of(BigDecimal.ZERO, currencyCode);
    }

    public static String format(MonetaryAmount amount, Locale locale) {
        MonetaryAmountFormat format = MonetaryFormats.getAmountFormat(
                AmountFormatQueryBuilder.of(locale).build());
        return format.format(amount);
    }
}
