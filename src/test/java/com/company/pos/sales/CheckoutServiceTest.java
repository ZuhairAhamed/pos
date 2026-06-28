package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalePaymentView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
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
class CheckoutServiceTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPrinter printer;
    @Autowired
    InMemoryPaymentTerminal terminal;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    private UUID cartWith(String qty) {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal(qty));
        return cart;
    }

    @Test
    void singleCashTenderComputesChange() {
        // 2 x 4.50 = 9.00 net, tax 1.35, total 10.35
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");
        assertThat(sale.payments()).hasSize(1);
        assertThat(sale.payments().get(0).method()).isEqualTo("CASH");
        assertThat(sale.payments().get(0).changeDue()).isEqualByComparingTo("9.65");
        assertThat(sale.status()).isEqualTo("COMPLETED");
        assertThat(printer.lastReceipt()).isNotEmpty();
        assertThat(carts.getCart(cart).status()).isEqualTo("CHECKED_OUT");
    }

    @Test
    void splitCardThenCashSettlesAndGivesChangeOnCash() {
        // total 10.35: pay 5.00 on card, rest (5.35) on cash tendered 10.00 -> change 4.65
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart, List.of(
                new TenderInput(PaymentMethod.CARD, new BigDecimal("5.00"), null),
                new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00")))), "cashier");

        assertThat(sale.payments()).hasSize(2);
        SalePaymentView card = sale.payments().get(0);
        SalePaymentView cash = sale.payments().get(1);
        assertThat(card.method()).isEqualTo("CARD");
        assertThat(card.amount()).isEqualByComparingTo("5.00");
        assertThat(card.maskedPan()).isEqualTo("**** **** **** 4242");
        assertThat(cash.method()).isEqualTo("CASH");
        assertThat(cash.amount()).isEqualByComparingTo("5.35");
        assertThat(cash.changeDue()).isEqualByComparingTo("4.65");
    }

    @Test
    void cardOnlyExactTotal() {
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null))), "cashier");
        assertThat(sale.payments()).hasSize(1);
        assertThat(sale.payments().get(0).method()).isEqualTo("CARD");
    }

    @Test
    void walletTenderSettles() {
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.WALLET, new BigDecimal("10.35"), null))), "cashier");
        assertThat(sale.payments().get(0).method()).isEqualTo("WALLET");
    }

    @Test
    void tendersBelowTotalAreRejected() {
        UUID cart = cartWith("2");
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("5.00"), null))), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void tendersAboveTotalAreRejected() {
        UUID cart = cartWith("2");
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart, List.of(
                new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null),
                new TenderInput(PaymentMethod.CARD, new BigDecimal("1.00"), null))), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void declinedCardFailsCheckoutAndPersistsNothing() {
        terminal.setApprove(false);
        UUID cart = cartWith("2");
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null))), "cashier"))
                .isInstanceOf(DomainException.class);
        // cart stays open — the failed checkout rolled back
        assertThat(carts.getCart(cart).status()).isEqualTo("OPEN");
    }

    @Test
    void emptyTendersRejected() {
        UUID cart = cartWith("2");
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart, List.of()), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void emptyCartCannotCheckout() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("5")))), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void saleIsRetrievableWithItsPayments() {
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        SaleView fetched = sales.getSale(sale.id());
        assertThat(fetched.receiptNumber()).isEqualTo(sale.receiptNumber());
        assertThat(fetched.payments()).hasSize(1);
        assertThat(fetched.payments().get(0).changeDue()).isEqualByComparingTo("9.65");
    }
}
