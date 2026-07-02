package com.company.pos.customer.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerService {

    CustomerView register(RegisterCustomerCommand command);

    CustomerView update(UUID id, UpdateCustomerCommand command);

    void deactivate(UUID id);

    Optional<CustomerView> findById(UUID id);

    List<CustomerView> search(String query);

    List<PurchaseHistoryEntry> purchaseHistory(UUID customerId);
}
