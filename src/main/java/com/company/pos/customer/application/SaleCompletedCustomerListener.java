package com.company.pos.customer.application;

import com.company.pos.common.util.Identifiers;
import com.company.pos.customer.domain.CustomerPurchase;
import com.company.pos.customer.infrastructure.CustomerPurchaseRepository;
import com.company.pos.sales.api.SaleCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class SaleCompletedCustomerListener {

    private final CustomerPurchaseRepository purchases;

    SaleCompletedCustomerListener(CustomerPurchaseRepository purchases) {
        this.purchases = purchases;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        if (event.customerId() == null) {
            return;
        }
        if (purchases.existsBySaleId(event.saleId())) {
            return; // idempotent: redelivered event
        }
        purchases.save(new CustomerPurchase(Identifiers.newId(), event.customerId(),
                event.saleId(), event.receiptNumber(), event.occurredAt(),
                event.grandTotal(), event.currencyCode()));
    }
}
