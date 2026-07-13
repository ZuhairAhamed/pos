package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.BillInput;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.EvenSplitInput;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.api.SplitMode;
import com.company.pos.dining.api.SplitQuoteView;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.SalePaymentView;
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
class DiningQuoteSplitTest {

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
        fake.addProduct(new ErpProduct("FRIES", "Fries", "FOOD", "Food", "bcFRIES",
                "EA", new BigDecimal("12.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("WATER", "Water", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openOrderOnFreshTable() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    private UUID addLine(UUID orderId, String sku) {
        return dining.addLine(orderId,
                new AddLineCommand(sku, BigDecimal.ONE, null, null), "alice")
                .lines().stream().filter(l -> l.sku().equals(sku)).findFirst().orElseThrow().id();
    }

    private static TenderInput cash(BigDecimal amount) {
        return new TenderInput(PaymentMethod.CASH, amount, amount);
    }

    // --- BY_ITEM ---

    @Test
    void quoteSplitByItemPricesEachBillAndLeavesTheOrderOpen() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER");   // 30.00 -> grand 34.50 (15% VAT, no SC)
        UUID fries = addLine(orderId, "FRIES");     // 12.00
        UUID water = addLine(orderId, "WATER");     // 5.00  -> fries+water grand 19.55

        SplitQuoteView q = dining.quoteSplitByItem(orderId,
                List.of(List.of(burger), List.of(fries, water)));

        assertThat(q.bills()).hasSize(2);
        assertThat(q.bills().get(0).grandTotal()).isEqualByComparingTo("34.50");
        assertThat(q.bills().get(1).grandTotal()).isEqualByComparingTo("19.55");
        assertThat(q.order()).isNull();
        assertThat(q.shares()).isNull();
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
        assertThat(dining.listOrderSaleIds(orderId)).isEmpty();
    }

    @Test
    void quoteSplitByItemMatchesCloseSplitWithServiceChargeOn() {
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER");
        UUID fries = addLine(orderId, "FRIES");
        UUID water = addLine(orderId, "WATER");

        SplitQuoteView q = dining.quoteSplitByItem(orderId,
                List.of(List.of(burger), List.of(fries, water)));

        // Close with EXACTLY the quoted amounts — service charge applied per bill on both paths.
        List<SaleView> sales = dining.closeOrderSplit(orderId,
                new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                        new BillInput(List.of(burger),
                                List.of(cash(q.bills().get(0).grandTotal())), Map.of(), null),
                        new BillInput(List.of(fries, water),
                                List.of(cash(q.bills().get(1).grandTotal())), Map.of(), null)),
                        null),
                "alice", false);

        assertThat(sales.get(0).grandTotal()).isEqualByComparingTo(q.bills().get(0).grandTotal());
        assertThat(sales.get(1).grandTotal()).isEqualByComparingTo(q.bills().get(1).grandTotal());
        assertThat(sales.get(0).serviceChargeAmount()).isEqualByComparingTo(q.bills().get(0).serviceChargeAmount());
        assertThat(sales.get(1).serviceChargeAmount()).isEqualByComparingTo(q.bills().get(1).serviceChargeAmount());
    }

    @Test
    void quoteSplitByItemValidationParityWithClose() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER");
        addLine(orderId, "FRIES"); // present but unassigned in the first case

        // incomplete partition
        assertThatThrownBy(() -> dining.quoteSplitByItem(orderId, List.of(List.of(burger))))
                .isInstanceOf(DomainException.class);
        // duplicate assignment
        assertThatThrownBy(() -> dining.quoteSplitByItem(orderId,
                List.of(List.of(burger), List.of(burger))))
                .isInstanceOf(DomainException.class);
        // empty bill
        assertThatThrownBy(() -> dining.quoteSplitByItem(orderId,
                List.of(List.of(burger), List.of())))
                .isInstanceOf(DomainException.class);
        // unknown line id
        assertThatThrownBy(() -> dining.quoteSplitByItem(orderId,
                List.of(List.of(UUID.randomUUID()))))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }

    // --- EVEN ---

    @Test
    void quoteSplitEvenReturnsSharesWithLastAbsorbingRemainder() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER");
        addLine(orderId, "WATER");  // subtotal 35.00, 15% VAT -> grand 40.25

        SplitQuoteView q = dining.quoteSplitEven(orderId, 3);

        assertThat(q.order().grandTotal()).isEqualByComparingTo("40.25");
        assertThat(q.shares()).containsExactly(new BigDecimal("13.42"),
                new BigDecimal("13.42"), new BigDecimal("13.41"));
        assertThat(q.bills()).isNull();
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void quoteSplitEvenSharesEqualCloseEvenPayments() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER");
        addLine(orderId, "WATER");

        SplitQuoteView q = dining.quoteSplitEven(orderId, 3);

        List<SaleView> sales = dining.closeOrderSplit(orderId,
                new SplitCloseCommand(SplitMode.EVEN, null,
                        new EvenSplitInput(3, List.of(PaymentMethod.CASH, PaymentMethod.CASH,
                                PaymentMethod.CASH))),
                "alice", false);

        List<BigDecimal> paid = sales.get(0).payments().stream()
                .map(SalePaymentView::amount).map(a -> a.setScale(2)).toList();
        assertThat(paid).containsExactlyInAnyOrderElementsOf(
                q.shares().stream().map(s -> s.setScale(2)).toList());
    }

    @Test
    void quoteSplitEvenRejectsFewerThanTwoWays() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER");
        assertThatThrownBy(() -> dining.quoteSplitEven(orderId, 1))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }
}
