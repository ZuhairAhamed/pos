package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.BillInput;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.api.SplitMode;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.DiscountInput;
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
class DiningSplitByItemTest {

    @Autowired DiningService dining;
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

    private UUID addLine(UUID orderId, String sku, String qty) {
        return dining.addLine(orderId,
                new AddLineCommand(sku, new BigDecimal(qty), null, null), "alice")
                .lines().stream().filter(l -> l.sku().equals(sku)).findFirst().orElseThrow().id();
    }

    private static TenderInput cash(String amount) {
        return new TenderInput(PaymentMethod.CASH, new BigDecimal(amount), new BigDecimal(amount));
    }

    @Test
    void closesTwoItemizedBillsFromOnePartition() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER", "1"); // 30.00 -> grand 34.50
        addLine(orderId, "FRIES", "1");                // 12.00
        addLine(orderId, "WATER", "1");                // 5.00  -> fries+water grand 19.55
        UUID fries = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.sku().equals("FRIES")).findFirst().orElseThrow().id();
        UUID water = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.sku().equals("WATER")).findFirst().orElseThrow().id();

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null),
                new BillInput(List.of(fries, water), List.of(cash("19.55")), Map.of(), null)),
                null);

        List<SaleView> sales = dining.closeOrderSplit(orderId, cmd, "alice", false);

        assertThat(sales).hasSize(2);
        assertThat(sales.get(0).grandTotal()).isEqualByComparingTo("34.50");
        assertThat(sales.get(1).grandTotal()).isEqualByComparingTo("19.55");
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(dining.listOrderSaleIds(orderId)).hasSize(2);
    }

    @Test
    void rejectsIncompletePartition() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER", "1");
        addLine(orderId, "FRIES", "1"); // left unassigned

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void rejectsLineOnTwoBills() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER", "1");

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null),
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsUnknownLineId() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER", "1");

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(UUID.randomUUID()), List.of(cash("34.50")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEmptyBill() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER", "1");

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(), List.of(cash("34.50")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aFailingBillRollsBackTheWholeSplit() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER", "1");
        UUID fries = addLine(orderId, "FRIES", "1");

        // second bill's tender is short -> checkout throws -> whole split rolls back
        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null),
                new BillInput(List.of(fries), List.of(cash("1.00")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
        assertThat(dining.listOrderSaleIds(orderId)).isEmpty();
    }
}
