package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves offline resilience: with the ERP "down", a completed sale still commits but its upload
 * fails and stays as an incomplete publication; once the ERP is back and the outbox is drained, the
 * sale uploads — exactly once (idempotent), despite the retry.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ErpUploadOfflineThenDrainsTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    IncompleteEventPublications incomplete;
    @Autowired
    EventPublicationRegistry registry;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        fake.clear();
    }

    @Test
    void saleUploadedWhenErpRecoversAndIsNotDoublePosted() {
        fake.setAvailable(false); // ERP offline

        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        UUID saleId = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))),
                "cashier").id();

        // The upload failed -> an incomplete publication is retained; nothing uploaded yet.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.findIncompletePublications()).isNotEmpty());
        assertThat(fake.uploadedSales()).isEmpty();

        // ERP recovers; drain the outbox (twice, to prove idempotency under repeated replay).
        fake.setAvailable(true);
        incomplete.resubmitIncompletePublications(p -> true);
        incomplete.resubmitIncompletePublications(p -> true);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(fake.uploadedSales()).hasSize(1);
            assertThat(fake.uploadedSales().get(0).saleId()).isEqualTo(saleId);
            assertThat(registry.findIncompletePublications()).isEmpty();
        });
    }
}
