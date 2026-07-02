package com.company.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// NOTE: The static @Container is shared for the lifetime of this class and is seeded by the single
// @Test method below, so saleCount==1 is deterministic. A second @Test here would see accumulated
// data because DatabaseCleaner is SQLite-only and cannot reset the Testcontainers PostgreSQL instance.
@SpringBootTest
@ActiveProfiles("store-server")
@Testcontainers
@TestPropertySource(properties = "pos.auth.jwt.secret=test-only-secret-not-for-production-use-abc123")
class ReportingPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ReportingService reports;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;

    @Test
    void salesSummaryWorksOnPostgres() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();

        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2"));
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        SalesSummaryReport all = reports.salesSummary(LocalDate.parse("2000-01-01"), LocalDate.parse("2100-01-01"));
        assertThat(all.saleCount()).isEqualTo(1);
        assertThat(all.grossSales()).isEqualByComparingTo("10.35");

        SalesSummaryReport past = reports.salesSummary(LocalDate.parse("2019-01-01"), LocalDate.parse("2019-12-31"));
        assertThat(past.saleCount()).isZero();
    }
}
