package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
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
class DiningServiceChargeTest {

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

    private UUID openOrder(ServiceType type) {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, type), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), null, null), "alice");
        return orderId; // net 60.00 -> with 10% SC and 15% VAT, grand 75.90
    }

    private static TenderInput cash(String amount) {
        return new TenderInput(PaymentMethod.CASH, new BigDecimal(amount), new BigDecimal(amount));
    }

    @Test
    void dineInCloseAppliesServiceCharge() {
        UUID orderId = openOrder(ServiceType.DINE_IN);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(cash("75.90")), Map.of(), null, false), "alice", false);
        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("6.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("75.90");
    }

    @Test
    void quickServiceCloseDoesNotApplyServiceCharge() {
        UUID orderId = openOrder(ServiceType.QUICK_SERVICE);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(cash("69.00")), Map.of(), null, false), "alice", false);
        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }

    @Test
    void nonManagerWaiverIsRejected() {
        UUID orderId = openOrder(ServiceType.DINE_IN);
        assertThatThrownBy(() -> dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(cash("69.00")), Map.of(), null, true), "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void managerWaiverZeroesTheCharge() {
        UUID orderId = openOrder(ServiceType.DINE_IN);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(cash("69.00")), Map.of(), null, true), "manager", true);
        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }
}
