package com.company.pos.customer.web;

import com.company.pos.common.exception.DomainException;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.PurchaseHistoryEntry;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.customer.api.UpdateCustomerCommand;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping("/customers")
    CustomerView register(@RequestBody RegisterCustomerCommand body) {
        return customers.register(body);
    }

    @GetMapping("/customers/{id}")
    CustomerView get(@PathVariable UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> DomainException.notFound("No customer " + id));
    }

    @GetMapping("/customers")
    List<CustomerView> search(@RequestParam(name = "q", required = false) String query) {
        return customers.search(query);
    }

    @PutMapping("/customers/{id}")
    CustomerView update(@PathVariable UUID id, @RequestBody UpdateCustomerCommand body) {
        return customers.update(id, body);
    }

    @DeleteMapping("/customers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MANAGER')")
    void deactivate(@PathVariable UUID id) {
        customers.deactivate(id);
    }

    @GetMapping("/customers/{id}/purchases")
    List<PurchaseHistoryEntry> purchases(@PathVariable UUID id) {
        return customers.purchaseHistory(id);
    }
}
