package com.company.pos.dining.api;

import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.TenderInput;
import java.util.List;
import java.util.Map;

/**
 * Payment for the whole table as one bill. {@code lineDiscounts} is keyed by SKU (to match the
 * existing {@link com.company.pos.sales.api.CheckoutCommand} contract); {@code transactionDiscount}
 * may be null.
 */
public record CloseOrderCommand(List<TenderInput> tenders, Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount) {

    public CloseOrderCommand {
        if (lineDiscounts == null) {
            lineDiscounts = Map.of();
        }
    }
}
