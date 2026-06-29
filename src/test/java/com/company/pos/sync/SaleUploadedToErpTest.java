package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.api.StockMovementUpload;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * NOT @Transactional: the upload runs in an after-commit async listener, so the sale must really
 * commit and the assertion polls until the listener has run. Uses DatabaseCleaner because committed
 * rows would otherwise leak across the shared in-memory DB (see Phase 3a). FakeErpClient is a
 * singleton bean, so its upload state is reset via fake.clear() in @BeforeEach/@AfterEach.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SaleUploadedToErpTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
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
    void completedSaleAndItsMovementsAreUploaded() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        UUID saleId = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))),
                "cashier").id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(fake.uploadedSales()).hasSize(1);
            SaleUpload uploaded = fake.uploadedSales().get(0);
            assertThat(uploaded.saleId()).isEqualTo(saleId);
            assertThat(uploaded.grandTotal()).isEqualByComparingTo("10.35");
            assertThat(uploaded.lines()).hasSize(1);
            assertThat(uploaded.lines().get(0).sku()).isEqualTo("COLA");
            assertThat(uploaded.payments()).hasSize(1);
            assertThat(uploaded.payments().get(0).method()).isEqualTo("CASH");

            assertThat(fake.uploadedMovementBatches()).containsKey(saleId.toString());
            List<StockMovementUpload> deltas = fake.uploadedMovementBatches().get(saleId.toString());
            assertThat(deltas).hasSize(1);
            assertThat(deltas.get(0).sku()).isEqualTo("COLA");
            assertThat(deltas.get(0).quantityDelta()).isEqualByComparingTo("-2"); // sold 2 -> -2
            assertThat(deltas.get(0).reason()).isEqualTo("SALE");
        });
    }
}
