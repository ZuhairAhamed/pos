package com.company.pos.tax.api;

import java.math.BigDecimal;
import java.util.List;

public interface TaxService {

    TaxedCart applyTax(List<TaxLineInput> lines, BigDecimal rate, boolean inclusive, String currencyCode);
}
