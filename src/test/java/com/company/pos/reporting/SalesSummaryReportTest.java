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
import com.company.pos.sales.api.SaleView;
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
class SalesSummaryReportTest {

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
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private SaleView sellTwoColas() {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2")); // 2 x 4.50 = 9.00 net, tax 1.35, grand 10.35
        return salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");
    }

    @Test
    void summarisesSalesInRange() {
        sellTwoColas();
        sellTwoColas();

        SalesSummaryReport r = reports.salesSummary(ALL_FROM, ALL_TO);

        assertThat(r.saleCount()).isEqualTo(2);
        assertThat(r.grossSales()).isEqualByComparingTo("20.70"); // 2 x 10.35
        assertThat(r.taxTotal()).isEqualByComparingTo("2.70");     // 2 x 1.35
        assertThat(r.subtotal()).isEqualByComparingTo("18.00");    // 2 x 9.00
        assertThat(r.returnCount()).isZero();
        assertThat(r.refundTotal()).isEqualByComparingTo("0");
        assertThat(r.netSales()).isEqualByComparingTo("20.70");
        assertThat(r.currencyCode()).isEqualTo("SAR");
    }

    @Test
    void emptyRangeYieldsZeros() {
        sellTwoColas();

        // A past window that cannot contain a just-now sale — this PROVES the created_at
        // binding actually filters on SQLite (a broken binding would wrongly include the sale).
        SalesSummaryReport r = reports.salesSummary(LocalDate.parse("2019-01-01"), LocalDate.parse("2019-12-31"));

        assertThat(r.saleCount()).isZero();
        assertThat(r.grossSales()).isEqualByComparingTo("0");
        assertThat(r.currencyCode()).isEqualTo("SAR");
    }

    @Test
    void rejectsInvertedRange() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> reports.salesSummary(ALL_TO, ALL_FROM))
                .isInstanceOf(com.company.pos.common.exception.DomainException.class);
    }
}
