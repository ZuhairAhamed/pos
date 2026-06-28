package com.company.pos.sales.api;

import java.util.List;
import java.util.UUID;

public record CheckoutCommand(UUID cartId, List<TenderInput> tenders) {
}
