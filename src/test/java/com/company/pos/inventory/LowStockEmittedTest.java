package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.inventory.api.LowStockDetected;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * NOT @Transactional: the stock decrement and the LowStockDetected publish happen in an
 * after-commit async listener. A test-only @ApplicationModuleListener captures the event;
 * DatabaseCleaner keeps the shared in-memory DB isolated. reorder-level forced to 19 so selling
 * 2 of 20 (-> 18) crosses below it.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@TestPropertySource(properties = "pos.inventory.reorder-level=19")
@Import({ DatabaseCleaner.class, LowStockEmittedTest.CapturingListener.class })
class LowStockEmittedTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    InventoryService inventory;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    DatabaseCleaner databaseCleaner;
    @Autowired
    CapturingListener captured;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        captured.clear();
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
        captured.clear();
    }

    @Test
    void crossingReorderLevelEmitsLowStockDetected() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // 20 -> 18, below reorder 19
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(captured.events()).hasSize(1);
            LowStockDetected e = captured.events().get(0);
            assertThat(e.sku()).isEqualTo("COLA");
            assertThat(e.onHand()).isEqualByComparingTo("18");
            assertThat(e.reorderLevel()).isEqualByComparingTo("19");
        });
    }

    @Test
    void stayingAtOrAboveReorderLevelEmitsNothing() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1")); // 20 -> 19, NOT below reorder 19
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        // Wait until the decrement landed (proves the listener ran), then assert no event was emitted.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand())
                        .isEqualByComparingTo("19"));
        assertThat(captured.events()).isEmpty();
    }

    /** Test-only after-commit capture of LowStockDetected. */
    @Component
    static class CapturingListener {
        private final List<LowStockDetected> events = new CopyOnWriteArrayList<>();

        @ApplicationModuleListener
        void on(LowStockDetected event) {
            events.add(event);
        }

        List<LowStockDetected> events() {
            return events;
        }

        void clear() {
            events.clear();
        }
    }
}
