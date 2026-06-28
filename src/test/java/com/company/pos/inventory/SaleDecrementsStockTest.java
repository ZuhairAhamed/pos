package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * NOT @Transactional: the stock decrement now runs in an after-commit async listener, so the
 * sale must really commit and the assertion polls until the listener has run. @AfterEach clears
 * the inventory tables because, without a rolling-back test transaction, rows would otherwise
 * leak across tests in the shared in-memory database.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class SaleDecrementsStockTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    InventoryService inventory;
    @Autowired
    StockMovementRepository movements;
    @Autowired
    StockLevelRepository stockLevels;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;

    @BeforeEach
    void seed() {
        movements.deleteAll();
        stockLevels.deleteAll();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void cleanup() {
        movements.deleteAll();
        stockLevels.deleteAll();
    }

    @Test
    void completingSaleDecrementsOnHandAndWritesMovement() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("3"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand())
                    .isEqualByComparingTo("17");   // 20 - 3
            assertThat(movements.findBySku("COLA")).hasSize(1);
            assertThat(movements.findBySku("COLA").get(0).getQuantityDelta())
                    .isEqualByComparingTo("-3");
        });
    }
}
