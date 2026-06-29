package com.company.pos.cashdrawer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.SaleView;
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

/**
 * With an open drawer on the configured terminal, a cash return pays out of it: the reconciliation's
 * pay-out total rises by the refunded amount. Non-@Transactional + DatabaseCleaner + Awaitility.
 * The drawer is opened on "T01", which is the default config TERMINAL_ID the sale/return both use.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnCashRefundTest {

    @Autowired
    SalesService sales;
    @Autowired
    ReturnService returns;
    @Autowired
    CartService carts;
    @Autowired
    CashDrawerService drawer;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
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
    }

    @Test
    void cashReturnPaysOutOfTheOpenDrawer() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "manager");

        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        // Wait until the cash sale has been captured into the drawer.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(drawer.reconcile(session.sessionId()).cashSales()).isEqualByComparingTo(sale.grandTotal()));

        // Return 1 of 2 -> cash refund 5.18 (4.50 net + 0.68 tax) paid out of the drawer.
        returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            DrawerReconciliation rec = drawer.reconcile(session.sessionId());
            assertThat(rec.payOuts()).isEqualByComparingTo("5.18");
        });
    }
}
