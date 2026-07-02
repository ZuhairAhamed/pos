package com.company.pos.reporting;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReportingCsvTest {

    @Autowired MockMvc mvc;
    @Autowired FakeErpClient fake;
    @Autowired ProductSync productSync;
    @Autowired CartService carts;
    @Autowired SalesService salesService;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        fake.clear();
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
        fake.clear();
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("u")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    @Test
    void salesCsvHasHeaderAndContentType() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("format", "csv").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString(
                        "from,to,currencyCode,saleCount,subtotal,lineDiscounts,txnDiscounts,taxTotal,grossSales,returnCount,refundTotal,netSales")));
    }

    @Test
    void productsCsvHasHeaderRow() throws Exception {
        mvc.perform(get("/reports/products").param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("format", "csv").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString(
                        "sku,name,quantitySold,revenue,discounts")));
    }

    @Test
    void commaInProductNameIsQuotedInCsv() throws Exception {
        // "Cola, Large" contains a comma — without csv() escaping it would split into two columns.
        fake.addProduct(new ErpProduct("COLA-LG", "Cola, Large", "BEV", "Beverages", "bcCOLALG",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();

        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA-LG", BigDecimal.ONE);
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00")))), "cashier1");

        mvc.perform(get("/reports/products").param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("format", "csv").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"Cola, Large\"")));
    }
}
