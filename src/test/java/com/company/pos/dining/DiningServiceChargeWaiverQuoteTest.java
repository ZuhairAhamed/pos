package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class DiningServiceChargeWaiverQuoteTest {

    @Autowired DiningService dining;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired ConfigurationService config;
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

    private UUID openDineInWithLine() {
        UUID table = dining.registerTable(new RegisterTableCommand("SC-" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(table, null), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN),
                "alice");
        return orderId;
    }

    @Test
    void waivedQuoteHasNoServiceChargeAndSmallerTotal() {
        UUID orderId = openDineInWithLine();

        QuoteView withSc = dining.quoteOrder(orderId, Map.of(), null, false);
        QuoteView waived = dining.quoteOrder(orderId, Map.of(), null, true);

        assertThat(withSc.serviceChargeAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(waived.serviceChargeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(waived.grandTotal()).isLessThan(withSc.grandTotal());
    }

    @Test
    void waivedQuoteDoesNotRequireManager() {
        UUID orderId = openDineInWithLine();
        // The quote is an ungated preview — it must NOT throw the manager-only validation.
        assertThatCode(() -> dining.quoteOrder(orderId, Map.of(), null, true)).doesNotThrowAnyException();
    }

    @Test
    void waivedEvenSplitQuoteHasNoServiceCharge() {
        UUID orderId = openDineInWithLine();

        com.company.pos.dining.api.SplitQuoteView waived = dining.quoteSplitEven(orderId, 2, true);
        com.company.pos.dining.api.SplitQuoteView withSc = dining.quoteSplitEven(orderId, 2, false);

        assertThat(waived.order().serviceChargeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(withSc.order().serviceChargeAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(waived.order().grandTotal()).isLessThan(withSc.order().grandTotal());
    }

    @Test
    void waivedByItemSplitQuoteHasNoServiceCharge() {
        UUID table = dining.registerTable(new RegisterTableCommand("SC-" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(table, null), "alice").id();
        var line = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().get(0).id();

        com.company.pos.dining.api.SplitQuoteView waived =
                dining.quoteSplitByItem(orderId, java.util.List.of(java.util.List.of(line)), true);

        assertThat(waived.bills().get(0).serviceChargeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
