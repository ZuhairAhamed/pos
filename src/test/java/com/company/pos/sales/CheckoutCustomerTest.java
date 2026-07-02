package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.sales.infrastructure.SaleRepository;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class CheckoutCustomerTest {

    @Autowired
    SalesService salesService;
    @Autowired
    CartService carts;
    @Autowired
    CustomerService customers;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPaymentTerminal terminal;
    @Autowired
    SaleRepository saleRepository;
    @Autowired
    DatabaseCleaner cleaner;

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

    @Test
    void saleRecordsAttachedCustomer() {
        // Register a customer and attach to a cart with one COLA line (2 x 4.50 = 9.00 net,
        // tax 1.35 at 15% exclusive, grand total 10.35)
        CustomerView customer = customers.register(
                new RegisterCustomerCommand("Aisha", "0501", null, null, null));

        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2"));
        carts.assignCustomer(cartId, customer.id());

        // Checkout with exact cash
        SaleView sale = salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))),
                "cashier");

        assertThat(sale.receiptNumber()).isNotBlank();

        // The persisted Sale entity must carry the customer id
        com.company.pos.sales.domain.Sale persisted = saleRepository.findById(sale.id())
                .orElseThrow();
        assertThat(persisted.getCustomerId()).isEqualTo(customer.id());
    }
}
