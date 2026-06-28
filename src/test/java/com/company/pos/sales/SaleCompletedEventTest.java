package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleCompleted;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
@RecordApplicationEvents
class SaleCompletedEventTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    ApplicationEvents events;
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
    void cashSalePublishesTerminalAndFullCashTotal() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        SaleCompleted event = events.stream(SaleCompleted.class).findFirst().orElseThrow();
        assertThat(event.terminalId()).isEqualTo("T01");
        assertThat(event.cashTotal()).isEqualByComparingTo("10.35");
    }

    @Test
    void splitSalePublishesOnlyTheCashPortion() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        // 5.00 on card, remainder 5.35 on cash
        sales.checkout(new CheckoutCommand(cart, List.of(
                new TenderInput(PaymentMethod.CARD, new BigDecimal("5.00"), null),
                new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00")))), "cashier");

        SaleCompleted event = events.stream(SaleCompleted.class).findFirst().orElseThrow();
        assertThat(event.cashTotal()).isEqualByComparingTo("5.35");
    }

    @Test
    void cardOnlySalePublishesZeroCash() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null))), "cashier");

        SaleCompleted event = events.stream(SaleCompleted.class).findFirst().orElseThrow();
        assertThat(event.cashTotal()).isEqualByComparingTo("0.00");
    }
}
