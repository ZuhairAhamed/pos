package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SaleUploadCarriesDiscountTest {

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
    void uploadedSaleCarriesLineAndTransactionDiscounts() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00
        // 10% line discount (0.90), tendered cash; grand 9.32
        UUID saleId = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null), "cashier", false).id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(fake.uploadedSales()).hasSize(1);
            SaleUpload up = fake.uploadedSales().get(0);
            assertThat(up.saleId()).isEqualTo(saleId);
            assertThat(up.discountTotal()).isEqualByComparingTo("0.90");
            assertThat(up.txnDiscountAmount()).isEqualByComparingTo("0.00");
            assertThat(up.lines()).hasSize(1);
            SaleUpload.Line line = up.lines().get(0);
            assertThat(line.grossAmount()).isEqualByComparingTo("9.00");
            assertThat(line.lineDiscountAmount()).isEqualByComparingTo("0.90");
            assertThat(line.lineDiscountReason()).isEqualTo("LOYALTY");
        });
    }
}
