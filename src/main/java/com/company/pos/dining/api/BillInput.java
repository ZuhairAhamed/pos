package com.company.pos.dining.api;

import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.TenderInput;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One bill of a by-item split: the order lines it covers, plus its own tenders and optional discounts. */
public record BillInput(List<UUID> lineIds, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) {
}
