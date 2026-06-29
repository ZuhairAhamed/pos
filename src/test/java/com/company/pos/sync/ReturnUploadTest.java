package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.api.ReturnUpload;
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
 * A completed return is uploaded to the ERP as a credit note (after-commit async). Non-@Transactional
 * + DatabaseCleaner + Awaitility; the FakeErpClient is reset around each test.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnUploadTest {

    @Autowired
    SalesService sales;
    @Autowired
    ReturnService returns;
    @Autowired
    CartService carts;
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
    void completedReturnUploadsCreditNote() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        var ret = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<ReturnUpload> uploaded = fake.uploadedReturns();
            assertThat(uploaded).hasSize(1);
            assertThat(uploaded.get(0).returnId()).isEqualTo(ret.id());
            assertThat(uploaded.get(0).lines()).hasSize(1);
        });
    }
}
