package com.company.pos.sales.application;

import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;

/** A priced line after manual discounts: original gross, the line-level discount, and the
 *  post-discount extended amount handed to {@code TaxService}. */
public record DiscountedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
        String currencyCode, BigDecimal grossAmount, BigDecimal lineDiscountAmount,
        DiscountType lineDiscountType, String lineDiscountReason, BigDecimal discountedExtended) {
}
