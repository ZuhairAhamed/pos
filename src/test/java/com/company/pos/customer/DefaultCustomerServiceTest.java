package com.company.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.customer.api.UpdateCustomerCommand;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class DefaultCustomerServiceTest {

    @Autowired
    CustomerService customers;

    @Test
    void registersAndFindsCustomer() {
        CustomerView created = customers.register(
                new RegisterCustomerCommand("Aisha Khan", "0501234567", "aisha@example.com", "12 Palm St", "VIP"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Aisha Khan");
        assertThat(created.active()).isTrue();

        CustomerView found = customers.findById(created.id()).orElseThrow();
        assertThat(found.phone()).isEqualTo("0501234567");
        assertThat(found.email()).isEqualTo("aisha@example.com");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> customers.register(
                new RegisterCustomerCommand("  ", "0500000000", null, null, null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updatesMutableFields() {
        CustomerView created = customers.register(
                new RegisterCustomerCommand("Bilal", "0511111111", null, null, null));

        CustomerView updated = customers.update(created.id(),
                new UpdateCustomerCommand("Bilal Ahmed", "0512222222", "bilal@example.com", "9 Cedar Rd", "note"));

        assertThat(updated.name()).isEqualTo("Bilal Ahmed");
        assertThat(updated.phone()).isEqualTo("0512222222");
        assertThat(updated.email()).isEqualTo("bilal@example.com");
    }

    @Test
    void deactivateIsSoftAndHidesFromSearch() {
        CustomerView created = customers.register(
                new RegisterCustomerCommand("Zoya Malik", "0533333333", "zoya@example.com", null, null));

        customers.deactivate(created.id());

        // Row still exists (soft delete)...
        assertThat(customers.findById(created.id()).orElseThrow().active()).isFalse();
        // ...but is excluded from search.
        assertThat(customers.search("Zoya")).extracting(CustomerView::id).doesNotContain(created.id());
    }

    @Test
    void searchMatchesNamePhoneAndEmail() {
        CustomerView c = customers.register(
                new RegisterCustomerCommand("Omar Farouk", "0549998877", "omar@shop.com", null, null));

        assertThat(customers.search("Omar")).extracting(CustomerView::id).contains(c.id());
        assertThat(customers.search("99988")).extracting(CustomerView::id).contains(c.id());
        assertThat(customers.search("omar@shop")).extracting(CustomerView::id).contains(c.id());
    }

    @Test
    void updateUnknownIdThrows() {
        assertThatThrownBy(() -> customers.update(UUID.randomUUID(),
                new UpdateCustomerCommand("X", null, null, null, null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void purchaseHistoryIsEmptyForNewCustomer() {
        CustomerView c = customers.register(
                new RegisterCustomerCommand("New Person", "0500001111", null, null, null));
        assertThat(customers.purchaseHistory(c.id())).isEmpty();
    }
}
