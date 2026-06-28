package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.company.pos.support.DatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the transactional-outbox guarantee: a failing after-commit consumer does NOT roll back
 * the sale, its publication stays incomplete in the registry, and resubmitting it (after the
 * fault clears) drains it. The failing consumer is a test-only bean toggled by a flag.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import({OutboxResilienceTest.FailingConsumer.class, DatabaseCleaner.class})
class OutboxResilienceTest {

    @Autowired
    DatabaseCleaner databaseCleaner;
    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    EventPublicationRegistry registry;
    @Autowired
    IncompleteEventPublications incomplete;
    @Autowired
    FailingConsumer failing;
    @Autowired
    StockMovementRepository stockMovements;
    @Autowired
    StockLevelRepository stockLevels;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        failing.reset();
        stockMovements.deleteAll();
        stockLevels.deleteAll();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
    }

    @Test
    void failedConsumerLeavesReplayablePublicationWithoutAffectingTheSale() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));

        // The sale commits even though a consumer will fail on it.
        var sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00")))), "cashier");
        assertThat(sale).isNotNull();

        // The failing consumer is invoked (once) and throws -> its publication stays incomplete.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(failing.attempts()).isGreaterThanOrEqualTo(1));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.findIncompletePublications()).isNotEmpty());

        // Clear the fault and resubmit every incomplete publication.
        int attemptsBeforeReplay = failing.attempts();
        failing.stopFailing();
        incomplete.resubmitIncompletePublications(p -> true);

        // The consumer now succeeds and the registry drains.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(failing.attempts()).isGreaterThan(attemptsBeforeReplay));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(registry.findIncompletePublications()).isEmpty());
    }

    /** Test-only consumer that fails until {@link #stopFailing()} is called. */
    @Component
    static class FailingConsumer {

        private final AtomicInteger attempts = new AtomicInteger();
        private volatile boolean fail = true;

        @ApplicationModuleListener
        void on(SaleCompleted event) {
            attempts.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("forced failure for outbox resilience test");
            }
        }

        int attempts() {
            return attempts.get();
        }

        void stopFailing() {
            this.fail = false;
        }

        void reset() {
            this.fail = true;
            this.attempts.set(0);
        }
    }
}
