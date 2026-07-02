package com.company.pos.customer.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.PurchaseHistoryEntry;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.customer.api.UpdateCustomerCommand;
import com.company.pos.customer.domain.Customer;
import com.company.pos.customer.domain.CustomerPurchase;
import com.company.pos.customer.infrastructure.CustomerPurchaseRepository;
import com.company.pos.customer.infrastructure.CustomerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultCustomerService implements CustomerService {

    private final CustomerRepository customers;
    private final CustomerPurchaseRepository purchases;

    DefaultCustomerService(CustomerRepository customers, CustomerPurchaseRepository purchases) {
        this.customers = customers;
        this.purchases = purchases;
    }

    @Override
    public CustomerView register(RegisterCustomerCommand command) {
        requireName(command.name());
        Customer customer = new Customer(Identifiers.newId(), command.name().trim(), Instant.now());
        customer.setPhone(command.phone());
        customer.setEmail(command.email());
        customer.setAddress(command.address());
        customer.setNotes(command.notes());
        return toView(customers.save(customer));
    }

    @Override
    public CustomerView update(UUID id, UpdateCustomerCommand command) {
        requireName(command.name());
        Customer customer = load(id);
        customer.setName(command.name().trim());
        customer.setPhone(command.phone());
        customer.setEmail(command.email());
        customer.setAddress(command.address());
        customer.setNotes(command.notes());
        customer.setUpdatedAt(Instant.now());
        return toView(customer);
    }

    @Override
    public void deactivate(UUID id) {
        Customer customer = load(id);
        customer.setActive(false);
        customer.setUpdatedAt(Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerView> findById(UUID id) {
        return customers.findById(id).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerView> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return customers.search(query.trim()).stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseHistoryEntry> purchaseHistory(UUID customerId) {
        return purchases.findByCustomerIdOrderByOccurredAtDesc(customerId).stream()
                .map(this::toEntry)
                .toList();
    }

    private Customer load(UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> DomainException.notFound("No customer " + id));
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw DomainException.validation("Customer name is required");
        }
    }

    private CustomerView toView(Customer c) {
        return new CustomerView(c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                c.getAddress(), c.getNotes(), c.isActive(), c.getCreatedAt());
    }

    private PurchaseHistoryEntry toEntry(CustomerPurchase p) {
        return new PurchaseHistoryEntry(p.getSaleId(), p.getReceiptNumber(), p.getOccurredAt(),
                p.getGrandTotal(), p.getCurrencyCode());
    }
}
