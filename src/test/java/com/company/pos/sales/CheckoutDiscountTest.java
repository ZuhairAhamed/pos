package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class CheckoutDiscountTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPaymentTerminal terminal;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("WATER", "Water Bottle", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("2.00"), "SAR", 2, true));
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    private DiscountInput pct(String v, String reason) {
        return new DiscountInput(DiscountType.PERCENT, new BigDecimal(v), reason);
    }

    private DiscountInput amt(String v, String reason) {
        return new DiscountInput(DiscountType.AMOUNT, new BigDecimal(v), reason);
    }

    @Test
    void cashierLineDiscountTaxedOnDiscountedBase() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00
        // 10% off (cashier cap is exactly 10%) -> 0.90 off; net 8.10, tax 1.22, total 9.32
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", pct("10", "LOYALTY")), null), "cashier", false);

        assertThat(sale.subtotal()).isEqualByComparingTo("8.10");
        assertThat(sale.taxTotal()).isEqualByComparingTo("1.22");
        assertThat(sale.grandTotal()).isEqualByComparingTo("9.32");
        assertThat(sale.discountTotal()).isEqualByComparingTo("0.90");
        assertThat(sale.lines().get(0).grossAmount()).isEqualByComparingTo("9.00");
        assertThat(sale.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("0.90");
        assertThat(sale.lines().get(0).lineDiscountReason()).isEqualTo("LOYALTY");
        assertThat(sale.payments().get(0).changeDue()).isEqualByComparingTo("10.68");
    }

    @Test
    void managerTransactionDiscountAllocatedAndTaxed() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00, single line
        // 5.00 off the cart (55% of base -> manager only); discounted extended 4.00, tax 0.60, total 4.60
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00"))),
                Map.of(), amt("5.00", "MANAGER_COMP")), "manager", true);

        assertThat(sale.subtotal()).isEqualByComparingTo("4.00");
        assertThat(sale.taxTotal()).isEqualByComparingTo("0.60");
        assertThat(sale.grandTotal()).isEqualByComparingTo("4.60");
        assertThat(sale.txnDiscountAmount()).isEqualByComparingTo("5.00");
        assertThat(sale.txnDiscountReason()).isEqualTo("MANAGER_COMP");
        assertThat(sale.discountTotal()).isEqualByComparingTo("5.00");
    }

    @Test
    void mixedLineAndTransactionDiscountReconciles() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));   // gross 9.00
        carts.addLine(cart, "WATER", new BigDecimal("3"));  // gross 6.00
        // COLA 10% line (0.90) -> postLine 8.10; WATER 6.00; cartBase 14.10
        // txn 4.10: COLA share 2.36 -> ext 5.74; WATER share 1.74 -> ext 4.26
        // nets 5.74 + 4.26 = 10.00; tax 0.86 + 0.64 = 1.50; grand 11.50
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("11.50"))),
                Map.of("COLA", pct("10", "LOYALTY")), amt("4.10", "MANAGER_COMP")), "manager", true);

        assertThat(sale.subtotal()).isEqualByComparingTo("10.00");
        assertThat(sale.taxTotal()).isEqualByComparingTo("1.50");
        assertThat(sale.grandTotal()).isEqualByComparingTo("11.50");
        assertThat(sale.discountTotal()).isEqualByComparingTo("5.00");
        // reconciliation: gross 15.00 - line 0.90 - txn 4.10 = 10.00 subtotal
        BigDecimal gross = sale.lines().stream().map(l -> l.grossAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(gross).isEqualByComparingTo("15.00");
    }

    @Test
    void cashierOverCapIsRejected() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", pct("20", "LOYALTY")), null), "cashier", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void managerMayExceedTheCashierCap() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00
        // 20% off -> 1.80; net 7.20, tax 1.08, total 8.28
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00"))),
                Map.of("COLA", pct("20", "LOYALTY")), null), "manager", true);

        assertThat(sale.grandTotal()).isEqualByComparingTo("8.28");
        assertThat(sale.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("1.80");
    }

    @Test
    void noDiscountCheckoutStillWorks() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");
        assertThat(sale.discountTotal()).isEqualByComparingTo("0");
        assertThat(sale.lines().get(0).grossAmount()).isEqualByComparingTo("9.00");
    }
}
