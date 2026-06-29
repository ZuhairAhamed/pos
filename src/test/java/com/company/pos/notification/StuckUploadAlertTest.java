package com.company.pos.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.notification.api.Alert;
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.application.StuckUploadMonitor;
import com.company.pos.notification.infrastructure.InMemoryNotifier;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * With the ERP "offline", a completed sale leaves an incomplete upload publication. With min-age
 * forced to 0, scan() alerts on it exactly once (deduped on re-scan). Non-@Transactional +
 * DatabaseCleaner + Awaitility; FakeErpClient and InMemoryNotifier reset around each test.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@TestPropertySource(properties = "pos.notification.stuck-upload.min-age-ms=0")
@Import(DatabaseCleaner.class)
class StuckUploadAlertTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryNotifier notifier;
    @Autowired
    StuckUploadMonitor monitor;
    @Autowired
    EventPublicationRegistry registry;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        notifier.clear();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        notifier.clear();
    }

    @Test
    void stuckUploadRaisesOneSyncErrorAlert() {
        fake.setAvailable(false); // ERP offline -> the sale's upload publication stays incomplete

        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10")))), "cashier");

        // Wait until the failed upload has left an incomplete publication in the outbox.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.findIncompletePublications()).isNotEmpty());

        monitor.scan();
        monitor.scan(); // second scan must NOT add a duplicate alert (dedup by publication id)

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Alert> syncErrors = notifier.alerts().stream()
                    .filter(a -> a.type() == AlertType.SYNC_ERROR)
                    .toList();
            assertThat(syncErrors).hasSize(1);
        });
    }
}
