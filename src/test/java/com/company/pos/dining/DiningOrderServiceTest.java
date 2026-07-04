package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OpenOrderView;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.dining.api.TableView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class DiningOrderServiceTest {

    @Autowired
    DiningService dining;

    private UUID freshTable(String label) {
        return dining.registerTable(new RegisterTableCommand(label, 4)).id();
    }

    @Test
    void opensDineInOrderOnTable() {
        UUID tableId = freshTable("O1");
        OrderView order = dining.openOrder(new OpenOrderCommand(tableId, null), "alice");

        assertThat(order.id()).isNotNull();
        assertThat(order.tableId()).isEqualTo(tableId);
        assertThat(order.serviceType()).isEqualTo(ServiceType.DINE_IN); // null defaulted
        assertThat(order.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(order.openedBy()).isEqualTo("alice");
        assertThat(order.lines()).isEmpty();
    }

    @Test
    void rejectsSecondOpenOrderOnSameTable() {
        UUID tableId = freshTable("O2");
        dining.openOrder(new OpenOrderCommand(tableId, null), "alice");
        assertThatThrownBy(() -> dining.openOrder(new OpenOrderCommand(tableId, null), "bob"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsOpenOnUnknownTable() {
        assertThatThrownBy(() -> dining.openOrder(new OpenOrderCommand(UUID.randomUUID(), null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsOpenOnInactiveTable() {
        UUID tableId = freshTable("O3");
        dining.deactivateTable(tableId);
        assertThatThrownBy(() -> dining.openOrder(new OpenOrderCommand(tableId, null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void openOrderIsVisibleOnSharedFloorAndById() {
        UUID tableId = freshTable("O4");
        OrderView opened = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice");

        // Any terminal: fetch by id...
        assertThat(dining.getOrder(opened.id()).id()).isEqualTo(opened.id());
        // ...and see it in the store-wide open-orders list.
        assertThat(dining.listOpenOrders())
                .extracting(OpenOrderView::orderId).contains(opened.id());
    }
}
