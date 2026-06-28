package com.company.pos.cashdrawer;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
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
class SaleCapturedByDrawerTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    CashDrawerService drawer;
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
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    @Test
    void cashSaleIsCapturedIntoOpenDrawerSession() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        DrawerReconciliation recon = drawer.reconcile(session.sessionId());
        assertThat(recon.cashSales()).isEqualByComparingTo("10.35");
        assertThat(recon.cashSalesCount()).isEqualTo(1);
        assertThat(recon.expectedCash()).isEqualByComparingTo("110.35");
    }

    @Test
    void cardSaleDoesNotChangeCashSales() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null))), "cashier");

        DrawerReconciliation recon = drawer.reconcile(session.sessionId());
        assertThat(recon.cashSales()).isEqualByComparingTo("0.00");
        assertThat(recon.cashSalesCount()).isEqualTo(0);
    }
}
