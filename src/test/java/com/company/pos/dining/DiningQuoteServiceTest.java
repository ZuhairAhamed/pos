package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
class DiningQuoteServiceTest {

    @Autowired DiningService dining;
    @Autowired ConfigurationService config;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openDineIn() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), null, null), "alice");
        return orderId; // net 60.00 -> +10% SC 6.00, +15% VAT on 66 -> grand 75.90
    }

    @Test
    void quoteOrderAppliesServiceChargeAndTax() {
        UUID orderId = openDineIn();
        QuoteView q = dining.quoteOrder(orderId);
        assertThat(q.subtotal()).isEqualByComparingTo("60.00");
        assertThat(q.serviceChargeAmount()).isEqualByComparingTo("6.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("75.90");
        // read-only on the order: still closeable afterwards
        assertThat(dining.getOrder(orderId).status().name()).isEqualTo("OPEN");
    }

    @Test
    void quoteOrderGrandTotalEqualsWhatCloseCharges() {
        UUID orderId = openDineIn();
        QuoteView q = dining.quoteOrder(orderId);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(
                        List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("75.90"),
                                new BigDecimal("75.90"))),
                        Map.of(), null, false),
                "alice", false);
        assertThat(q.grandTotal()).isEqualByComparingTo(sale.grandTotal());
        assertThat(q.serviceChargeAmount()).isEqualByComparingTo(sale.serviceChargeAmount());
    }
}
