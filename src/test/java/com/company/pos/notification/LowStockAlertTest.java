package com.company.pos.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.notification.api.AlertType;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Drives a real sale that crosses the reorder level and asserts a LOW_STOCK alert reaches the
 * in-memory Notifier (exercises inventory emission -> notification listener). Non-@Transactional
 * + DatabaseCleaner + Awaitility; the singleton InMemoryNotifier is reset around each test.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@TestPropertySource(properties = "pos.inventory.reorder-level=19")
@Import(DatabaseCleaner.class)
class LowStockAlertTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    InMemoryNotifier notifier;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        notifier.clear();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        notifier.clear();
    }

    @Test
    void crossingReorderRaisesLowStockAlert() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // 20 -> 18, below reorder 19
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<com.company.pos.notification.api.Alert> lowStock = notifier.alerts().stream()
                    .filter(a -> a.type() == AlertType.LOW_STOCK)
                    .toList();
            assertThat(lowStock).hasSize(1);
            assertThat(lowStock.get(0).message()).contains("COLA");
        });
    }
}
