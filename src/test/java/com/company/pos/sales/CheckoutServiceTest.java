package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.math.BigDecimal;
import java.util.UUID;
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

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void checkoutPricesTaxesPaysAndPersists() {
        // VAT default 0.15, exclusive. 2 x 4.50 = 9.00 net, tax 1.35, total 10.35.
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));

        SaleView sale = sales.checkout(new CheckoutCommand(cart, new BigDecimal("20.00")), "cashier");

        assertThat(sale.receiptNumber()).matches("S01-T01-\\d{6}");
        assertThat(sale.subtotal()).isEqualByComparingTo("9.00");
        assertThat(sale.taxTotal()).isEqualByComparingTo("1.35");
        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");
        assertThat(sale.payment().changeDue()).isEqualByComparingTo("9.65");
        assertThat(sale.lines()).hasSize(1);
        assertThat(sale.status()).isEqualTo("COMPLETED");

        // sale is retrievable
        SaleView fetched = sales.getSale(sale.id());
        assertThat(fetched.receiptNumber()).isEqualTo(sale.receiptNumber());

        // receipt was printed
        assertThat(printer.lastReceipt()).isNotEmpty();

        // cart is closed
        assertThat(carts.getCart(cart).status()).isEqualTo("CHECKED_OUT");
    }

    @Test
    void emptyCartCannotCheckout() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart, new BigDecimal("5")), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void insufficientCashIsRejected() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart, new BigDecimal("1.00")), "cashier"))
                .isInstanceOf(DomainException.class);
    }
}
