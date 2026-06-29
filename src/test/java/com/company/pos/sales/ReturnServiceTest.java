package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.exception.ErrorCode;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Drives a real cash sale, then exercises the return write path directly (synchronous):
 * proportional refund math, the over-return guard, and the recorded refund tender. After-commit
 * stock/drawer/ERP reversal is covered in Tasks 6-9. Non-@Transactional + DatabaseCleaner.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnServiceTest {

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
    InMemoryPaymentTerminal terminal;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
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
        databaseCleaner.clean();
        terminal.setApprove(true);
    }

    /** Sell 2x COLA for cash, then return 1. */
    private SaleView sellTwoColasForCash() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        return sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");
    }

    @Test
    void returningOneOfTwoRefundsHalfProportionally() {
        // 2x COLA @ 4.50 net = 9.00, +15% VAT (default config) = 1.35 -> grand 10.35.
        // Return 1 of 2: refundNet = 4.50, refundTax = 0.675 -> 0.68 (HALF_UP), refund total = 5.18.
        SaleView sale = sellTwoColasForCash();
        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");

        ReturnView ret = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        assertThat(ret.refundGrandTotal()).isEqualByComparingTo("5.18");
        assertThat(ret.refundSubtotal()).isEqualByComparingTo("4.50");
        assertThat(ret.refundTaxTotal()).isEqualByComparingTo("0.68");
        assertThat(ret.lines()).hasSize(1);
        assertThat(ret.lines().get(0).quantity()).isEqualByComparingTo("1");
        assertThat(ret.refunds()).hasSize(1);
        assertThat(ret.refunds().get(0).method()).isEqualTo("CASH");
        assertThat(ret.refunds().get(0).amount()).isEqualByComparingTo("5.18");
    }

    @Test
    void overReturnIsRejectedAcrossTwoPartialReturns() {
        SaleView sale = sellTwoColasForCash();
        returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        // Second return of 2 would make cumulative 3 > 2 sold.
        assertThatThrownBy(() -> returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("2")))), "manager"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void emptyLinesAreRejectedWithValidation() {
        SaleView sale = sellTwoColasForCash();
        assertThatThrownBy(() -> returns.processReturn(
                new ReturnCommand(sale.id(), null, List.of()), "manager"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void nonPositiveQuantityIsRejectedWithValidation() {
        SaleView sale = sellTwoColasForCash();
        assertThatThrownBy(() -> returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("0")))), "manager"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void lookupByReceiptNumberResolvesTheSale() {
        SaleView sale = sellTwoColasForCash();

        ReturnView ret = returns.processReturn(new ReturnCommand(null, sale.receiptNumber(),
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        assertThat(ret.originalSaleId()).isEqualTo(sale.id());
    }

    @Test
    void getReturnReturnsThePersistedRecord() {
        SaleView sale = sellTwoColasForCash();
        ReturnView created = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        ReturnView fetched = returns.getReturn(created.id());

        assertThat(fetched.creditNoteNumber()).isEqualTo(created.creditNoteNumber());
        assertThat(fetched.refunds()).hasSize(1);
    }

    @Test
    void mixedTenderRefundAllocatesAcrossOriginalMethodsAndSumsExactly() {
        // 2x COLA grand 10.35, paid 5.00 cash + 5.35 card. Return 1 -> refund 5.18, split
        // proportionally across the two original tenders (exercises the proportional divide branch).
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cart, List.of(
                new TenderInput(PaymentMethod.CASH, new BigDecimal("5.00"), new BigDecimal("5.00")),
                new TenderInput(PaymentMethod.CARD, new BigDecimal("5.35"), null))), "cashier");
        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");

        ReturnView ret = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        // Order-independent: the per-tender split depends on payment ordering, but the total is
        // exact and there is exactly one cash and one card refund regardless of order.
        assertThat(ret.refundGrandTotal()).isEqualByComparingTo("5.18");
        assertThat(ret.refunds()).hasSize(2);
        BigDecimal sum = ret.refunds().stream()
                .map(com.company.pos.sales.api.ReturnPaymentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("5.18");
        assertThat(ret.refunds().stream().filter(r -> r.method().equals("CASH")).count()).isEqualTo(1);
        assertThat(ret.refunds().stream().filter(r -> r.method().equals("CARD")).count()).isEqualTo(1);
    }
}
