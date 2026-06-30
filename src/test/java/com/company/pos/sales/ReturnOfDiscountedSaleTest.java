package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
class ReturnOfDiscountedSaleTest {

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

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void returningOneOfTwoDiscountedUnitsRefundsHalfTheDiscountedLine() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00
        // 10% off -> net 8.10, tax 1.22, line total 9.32
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null), "cashier", false);
        assertThat(sale.lines().get(0).netAmount()).isEqualByComparingTo("8.10");

        // Return 1 of 2 units. Proportional refund off the DISCOUNTED line:
        // net 8.10/2 = 4.05, tax 1.22/2 = 0.61, line total 4.66
        ReturnView ret = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        assertThat(ret.refundSubtotal()).isEqualByComparingTo("4.05");
        assertThat(ret.refundTaxTotal()).isEqualByComparingTo("0.61");
        assertThat(ret.refundGrandTotal()).isEqualByComparingTo("4.66");
    }
}
