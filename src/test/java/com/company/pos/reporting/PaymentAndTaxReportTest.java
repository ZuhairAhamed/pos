package com.company.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.TaxSummaryReport;
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
class PaymentAndTaxReportTest {

    private static final LocalDate ALL_FROM = LocalDate.parse("2000-01-01");
    private static final LocalDate ALL_TO = LocalDate.parse("2100-01-01");

    @Autowired ReportingService reports;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired com.company.pos.device.infrastructure.InMemoryPaymentTerminal terminal;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private void sellTwoColas(PaymentMethod method) {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2")); // grand 10.35
        BigDecimal tendered = method == PaymentMethod.CASH ? new BigDecimal("20.00") : new BigDecimal("10.35");
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(method, new BigDecimal("10.35"), tendered))), "cashier");
    }

    @Test
    void paymentsGroupedByMethod() {
        sellTwoColas(PaymentMethod.CASH);
        sellTwoColas(PaymentMethod.CARD);

        PaymentBreakdownReport r = reports.paymentBreakdown(ALL_FROM, ALL_TO);

        assertThat(r.totalCollected()).isEqualByComparingTo("20.70");
        assertThat(r.lines()).anySatisfy(l -> {
            assertThat(l.method()).isEqualTo("CASH");
            assertThat(l.count()).isEqualTo(1);
            assertThat(l.collected()).isEqualByComparingTo("10.35");
        });
        assertThat(r.lines()).anySatisfy(l -> {
            assertThat(l.method()).isEqualTo("CARD");
            assertThat(l.collected()).isEqualByComparingTo("10.35");
        });
    }

    @Test
    void taxSummaryTotals() {
        sellTwoColas(PaymentMethod.CASH);

        TaxSummaryReport r = reports.taxSummary(ALL_FROM, ALL_TO);

        assertThat(r.taxableAmount()).isEqualByComparingTo("9.00");
        assertThat(r.taxCollected()).isEqualByComparingTo("1.35");
        assertThat(r.refundTax()).isEqualByComparingTo("0");
        assertThat(r.netTax()).isEqualByComparingTo("1.35");
        assertThat(r.currencyCode()).isEqualTo("SAR");
    }
}
