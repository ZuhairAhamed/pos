package com.company.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.api.TaxSummaryReport;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that a processed return is correctly reflected in all three reporting aggregates:
 * salesSummary (returnCount, refundTotal, netSales), paymentBreakdown (totalRefunded, per-method
 * refunded), and taxSummary (refundTax, netTax). Mirrored from ReturnServiceTest / ReturnCashRefundTest.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class RefundReportTest {

    private static final LocalDate WIDE_FROM = LocalDate.parse("2000-01-01");
    private static final LocalDate WIDE_TO   = LocalDate.parse("2100-01-01");

    @Autowired ReportingService reports;
    @Autowired SalesService sales;
    @Autowired ReturnService returns;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired InventorySync inventorySync;
    @Autowired FakeErpClient fake;
    @Autowired InMemoryPaymentTerminal terminal;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        terminal.setApprove(true);
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void cleanup() {
        cleaner.clean();
        fake.clear();
    }

    /**
     * Sell 2x COLA for cash (grand total 10.35), then do a full return of both items.
     * The refund covers the entire grand total (10.35) and tax (1.35).
     */
    @Test
    void returnAppearsCorrectlyInAllThreeReports() {
        // Seed: one cash sale, 2x COLA @ 4.50 = 9.00 net + 1.35 tax = 10.35 grand.
        var cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))),
                "cashier");
        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");

        // Full return: line 1, qty 2. Mirrored from ReturnServiceTest#returningOneOfTwoRefundsHalfProportionally.
        ReturnView ret = returns.processReturn(
                new ReturnCommand(sale.id(), null,
                        List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("2")))),
                "manager");
        assertThat(ret.refundGrandTotal()).isEqualByComparingTo("10.35");
        assertThat(ret.refundTaxTotal()).isEqualByComparingTo("1.35");

        // --- salesSummary ---
        SalesSummaryReport summary = reports.salesSummary(WIDE_FROM, WIDE_TO);
        assertThat(summary.returnCount()).isEqualTo(1);
        assertThat(summary.refundTotal()).isEqualByComparingTo(ret.refundGrandTotal());
        assertThat(summary.netSales())
                .isEqualByComparingTo(summary.grossSales().subtract(summary.refundTotal()));

        // --- paymentBreakdown ---
        PaymentBreakdownReport payment = reports.paymentBreakdown(WIDE_FROM, WIDE_TO);
        // The original CASH sale remains fully collected.
        assertThat(payment.totalCollected()).isEqualByComparingTo("10.35");
        // The refund is recorded against the CASH method.
        assertThat(payment.totalRefunded()).isEqualByComparingTo(ret.refundGrandTotal());
        assertThat(payment.lines()).anySatisfy(line -> {
            assertThat(line.method()).isEqualTo("CASH");
            assertThat(line.refunded()).isGreaterThan(BigDecimal.ZERO);
        });

        // --- taxSummary ---
        TaxSummaryReport tax = reports.taxSummary(WIDE_FROM, WIDE_TO);
        assertThat(tax.refundTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(tax.refundTax()).isEqualByComparingTo(ret.refundTaxTotal());
        assertThat(tax.netTax())
                .isEqualByComparingTo(tax.taxCollected().subtract(tax.refundTax()));
    }
}
