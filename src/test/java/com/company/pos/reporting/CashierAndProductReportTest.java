package com.company.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.reporting.api.CashierReport;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
class CashierAndProductReportTest {

    private static final LocalDate ALL_FROM = LocalDate.parse("2000-01-01");
    private static final LocalDate ALL_TO = LocalDate.parse("2100-01-01");

    @Autowired ReportingService reports;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("CHIP", "Chips", "SNK", "Snacks", "bcCHIP",
                "EA", new BigDecimal("2.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private void sell(String cashier, String sku, String qty) {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, sku, new BigDecimal(qty));
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("1000.00")))), cashier);
    }

    @Test
    void cashierReportGroupsByCashier() {
        sell("alice", "COLA", "2"); // grand 10.35
        sell("alice", "CHIP", "1"); // 2.00 net, 0.30 tax, 2.30 grand
        sell("bob", "COLA", "1");   // 4.50 net, 0.675->0.68 tax, ~5.18 grand

        CashierReport r = reports.cashierReport(ALL_FROM, ALL_TO);

        CashierReport.CashierLine alice = r.lines().stream()
                .filter(l -> l.cashierUsername().equals("alice")).findFirst().orElseThrow();
        assertThat(alice.saleCount()).isEqualTo(2);
        assertThat(alice.totalSales()).isEqualByComparingTo("12.65"); // 10.35 + 2.30
        // Ordered by totalSales desc: alice (12.65) before bob.
        assertThat(r.lines().get(0).cashierUsername()).isEqualTo("alice");
    }

    @Test
    void productPerformanceRanksByRevenue() {
        sell("alice", "COLA", "2"); // COLA revenue 10.35 (line_total incl tax)
        sell("bob", "CHIP", "1");   // CHIP revenue 2.30

        ProductPerformanceReport r = reports.productPerformance(ALL_FROM, ALL_TO, 50);

        assertThat(r.lines().get(0).sku()).isEqualTo("COLA");
        assertThat(r.lines().get(0).quantitySold()).isEqualByComparingTo("2");
        assertThat(r.lines()).anySatisfy(l -> assertThat(l.sku()).isEqualTo("CHIP"));
    }

    @Test
    void productLimitClampsToAtLeastOne() {
        sell("alice", "COLA", "1");
        sell("bob", "CHIP", "1");
        assertThat(reports.productPerformance(ALL_FROM, ALL_TO, 1).lines()).hasSize(1);
        assertThat(reports.productPerformance(ALL_FROM, ALL_TO, 0).lines()).hasSize(1); // clamped to 1
    }
}
