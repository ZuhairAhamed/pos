package com.company.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.PurchaseHistoryEntry;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class PurchaseHistoryListenerTest {

    @Autowired
    DomainEvents events;
    @Autowired
    CustomerService customers;
    @Autowired
    DatabaseCleaner cleaner;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private SaleCompleted saleFor(UUID customerId, UUID saleId) {
        return new SaleCompleted(saleId, "R-" + saleId, "T1", "MAIN", "SAR",
                new BigDecimal("42.00"), new BigDecimal("42.00"),
                List.of(new SaleCompleted.SoldLine("SKU1", BigDecimal.ONE)),
                customerId, Instant.now());
    }

    @Test
    void recordsHistoryForSaleWithCustomer() {
        CustomerView c = customers.register(new RegisterCustomerCommand("Aisha", "0501", null, null, null));
        UUID saleId = UUID.randomUUID();

        tx.executeWithoutResult(s -> events.publish(saleFor(c.id(), saleId)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<PurchaseHistoryEntry> history = customers.purchaseHistory(c.id());
            assertThat(history).hasSize(1);
            assertThat(history.get(0).saleId()).isEqualTo(saleId);
            assertThat(history.get(0).grandTotal()).isEqualByComparingTo("42.00");
        });
    }

    @Test
    void isIdempotentOnRedelivery() {
        CustomerView c = customers.register(new RegisterCustomerCommand("Bilal", "0502", null, null, null));
        UUID saleId = UUID.randomUUID();

        tx.executeWithoutResult(s -> events.publish(saleFor(c.id(), saleId)));
        tx.executeWithoutResult(s -> events.publish(saleFor(c.id(), saleId))); // same saleId — redelivery

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(customers.purchaseHistory(c.id())).hasSize(1));
        // Give any second insert a chance, then re-confirm still one.
        assertThat(customers.purchaseHistory(c.id())).hasSize(1);
    }

    @Test
    void ignoresSaleWithoutCustomer() {
        CustomerView c = customers.register(new RegisterCustomerCommand("Zoya", "0503", null, null, null));

        tx.executeWithoutResult(s -> events.publish(saleFor(null, UUID.randomUUID())));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(customers.purchaseHistory(c.id())).isEmpty());
    }
}
