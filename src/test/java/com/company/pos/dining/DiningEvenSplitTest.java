package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.EvenSplitInput;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.api.SplitMode;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalePaymentView;
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
class DiningEvenSplitTest {

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
        fake.addProduct(new ErpProduct("WATER", "Water", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openWithBurgerAndWater() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, null), "alice");
        dining.addLine(orderId, new AddLineCommand("WATER", new BigDecimal("1"), null, null), "alice");
        return orderId; // subtotal 35.00, VAT 15% -> grand 40.25
    }

    @Test
    void evenlySplitsIntoThreePaymentsWithLastAbsorbingTheRemainder() {
        UUID orderId = openWithBurgerAndWater();

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.EVEN, null,
                new EvenSplitInput(3, List.of(PaymentMethod.CASH, PaymentMethod.CASH, PaymentMethod.CASH)));

        List<SaleView> sales = dining.closeOrderSplit(orderId, cmd, "alice", false);

        assertThat(sales).hasSize(1);
        SaleView sale = sales.get(0);
        assertThat(sale.grandTotal()).isEqualByComparingTo("40.25");
        List<SalePaymentView> pays = sale.payments();
        assertThat(pays).hasSize(3);
        BigDecimal sum = pays.stream().map(SalePaymentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("40.25");
        // 40.25 / 3 -> 13.42, 13.42, 13.41 (last absorbs the remainder)
        assertThat(pays.stream().map(p -> p.amount().setScale(2)).toList())
                .containsExactlyInAnyOrder(new BigDecimal("13.42"), new BigDecimal("13.42"),
                        new BigDecimal("13.41"));
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(dining.listOrderSaleIds(orderId)).hasSize(1);
    }

    @Test
    void rejectsFewerThanTwoWays() {
        UUID orderId = openWithBurgerAndWater();
        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.EVEN, null,
                new EvenSplitInput(1, List.of(PaymentMethod.CASH)));
        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void rejectsMethodCountNotMatchingWays() {
        UUID orderId = openWithBurgerAndWater();
        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.EVEN, null,
                new EvenSplitInput(3, List.of(PaymentMethod.CASH, PaymentMethod.CASH)));
        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
    }
}
