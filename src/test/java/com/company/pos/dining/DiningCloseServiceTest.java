package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OpenOrderView;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.menu.api.MenuService;
import com.company.pos.payment.api.PaymentMethod;
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
class DiningCloseServiceTest {

    @Autowired DiningService dining;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;
    @Autowired MenuService menu;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openOrderWithTwoBurgers(String tableLabel) {
        UUID tableId = dining.registerTable(new RegisterTableCommand(tableLabel, 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        // two separate lines, same sku — must aggregate to one cart line at close
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), "medium", CourseTag.MAIN), "alice");
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), "well done", CourseTag.MAIN), "alice");
        return orderId;
    }

    @Test
    void closingProducesSaleFreesTableAndMarksClosed() {
        UUID orderId = openOrderWithTwoBurgers("C1");

        // 2 x 30.00 = 60.00 net, +15% tax = 9.00, grand 69.00
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00"))),
                        Map.of(), null),
                "alice", false);

        assertThat(sale.id()).isNotNull();
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");

        // order is now CLOSED and stamped with the sale id
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(dining.getOrder(orderId).saleId()).isEqualTo(sale.id());

        // table is freed — it no longer appears among open orders
        assertThat(dining.listOpenOrders())
                .extracting(OpenOrderView::orderId).doesNotContain(orderId);
    }

    @Test
    void multiTenderClosesAsOneBill() {
        UUID orderId = openOrderWithTwoBurgers("C2");

        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(
                        new TenderInput(PaymentMethod.CARD, new BigDecimal("40.00"), null),
                        new TenderInput(PaymentMethod.CASH, null, new BigDecimal("40.00"))),
                        Map.of(), null),
                "alice", false);

        assertThat(sale.payments()).hasSize(2);
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }

    @Test
    void doubleCloseIsRejected() {
        UUID orderId = openOrderWithTwoBurgers("C3");
        dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00"))),
                        Map.of(), null), "alice", false);

        assertThatThrownBy(() -> dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00"))),
                        Map.of(), null), "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void voidedOrderCannotBeClosed() {
        UUID orderId = openOrderWithTwoBurgers("C4");
        dining.voidOrder(orderId, "walked out");

        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.VOIDED);
        assertThatThrownBy(() -> dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00"))),
                        Map.of(), null), "alice", false))
                .isInstanceOf(DomainException.class);
        // voiding frees the table too
        assertThat(dining.listOpenOrders())
                .extracting(OpenOrderView::orderId).doesNotContain(orderId);
    }

    @Test
    void closingEmptyOrderIsRejected() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("C7", 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        assertThatThrownBy(() -> dining.closeOrder(orderId,
                new CloseOrderCommand(
                        List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00"))),
                        Map.of(), null),
                "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updateLineRejectedOnVoidedOrder() {
        UUID orderId = openOrderWithTwoBurgers("C5");
        dining.voidOrder(orderId, "walked out");
        assertThatThrownBy(() -> dining.updateLine(orderId, UUID.randomUUID(),
                new BigDecimal("1"), null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void removeLineRejectedOnVoidedOrder() {
        UUID orderId = openOrderWithTwoBurgers("C6");
        dining.voidOrder(orderId, "walked out");
        assertThatThrownBy(() -> dining.removeLine(orderId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void closingCarriesModifierPriceAndDetailToTheSale() {
        // Arrange: a cheese add-on on BURGER
        com.company.pos.menu.api.ModifierGroupView addons =
                menu.createModifierGroup(new com.company.pos.menu.api.CreateModifierGroupCommand("Add-ons", 0, 3));
        java.util.UUID cheeseId = menu.addOption(addons.id(),
                new com.company.pos.menu.api.AddOptionCommand("Extra cheese", new java.math.BigDecimal("2.00"))).id();
        menu.assignGroupToSku(addons.id(), "BURGER");

        UUID tableId = dining.registerTable(new RegisterTableCommand("CM1", 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        dining.addLine(orderId, new com.company.pos.dining.api.AddLineCommand(
                "BURGER", new java.math.BigDecimal("1"), null, null, java.util.List.of(cheeseId)), "alice");

        // Act: close (effective 32.00 +15% = 4.80 → grand 36.80)
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(java.util.List.of(
                        new TenderInput(PaymentMethod.CASH, null, new java.math.BigDecimal("40.00"))),
                        java.util.Map.of(), null), "alice", false);

        assertThat(sale.grandTotal()).isEqualByComparingTo("36.80");
        assertThat(sale.lines().get(0).unitPrice()).isEqualByComparingTo("32.00");
        assertThat(sale.lines().get(0).modifiers()).extracting("name").containsExactly("Extra cheese");
    }
}
