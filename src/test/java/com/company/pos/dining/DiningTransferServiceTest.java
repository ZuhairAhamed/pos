package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class DiningTransferServiceTest {

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
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID freshTable(String label) {
        return dining.registerTable(new RegisterTableCommand(label + UUID.randomUUID(), 4)).id();
    }

    @Test
    void transfersOpenOrderToFreeTable() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();

        OrderView moved = dining.transferOrder(orderId, to);

        assertThat(moved.id()).isEqualTo(orderId);
        assertThat(moved.tableId()).isEqualTo(to);
        assertThat(moved.status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void movedOrderKeepsItsLinesAndFiredState() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("2"), "no onion", CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");

        OrderView moved = dining.transferOrder(orderId, to);

        assertThat(moved.lines()).hasSize(1);
        assertThat(moved.lines().get(0).sku()).isEqualTo("BURGER");
        assertThat(moved.lines().get(0).firedAt()).isNotNull(); // fired state rides along
    }

    @Test
    void rejectsTransferToOccupiedTable() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        dining.openOrder(new OpenOrderCommand(to, null), "bob"); // target now occupied
        assertThatThrownBy(() -> dining.transferOrder(orderId, to))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTransferToInactiveTable() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        dining.deactivateTable(to);
        assertThatThrownBy(() -> dining.transferOrder(orderId, to))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTransferToUnknownTable() {
        UUID from = freshTable("FROM");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        assertThatThrownBy(() -> dining.transferOrder(orderId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTransferToSameTable() {
        UUID from = freshTable("FROM");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        assertThatThrownBy(() -> dining.transferOrder(orderId, from))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTransferOfNonOpenOrder() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        dining.voidOrder(orderId, "test");
        assertThatThrownBy(() -> dining.transferOrder(orderId, to))
                .isInstanceOf(DomainException.class);
    }
}
