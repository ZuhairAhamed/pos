package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.dining.api.DiningFloorChanged;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.FloorChangeType;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.dining.api.TableView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@RecordApplicationEvents
@Transactional
class DiningFloorChangedPublicationTest {

    @Autowired
    DiningService dining;
    @Autowired
    ApplicationEvents events;

    @Test
    void registerOpenTransferAndVoidEachPublishAFloorChange() {
        TableView t1 = dining.registerTable(new RegisterTableCommand("WS-1", 4));
        TableView t2 = dining.registerTable(new RegisterTableCommand("WS-2", 4));
        OrderView order = dining.openOrder(new OpenOrderCommand(t1.id(), ServiceType.DINE_IN), "tester");
        dining.transferOrder(order.id(), t2.id());
        dining.voidOrder(order.id(), "test");

        assertThat(changesOf(FloorChangeType.TABLE_REGISTERED)).isEqualTo(2);
        assertThat(changesOf(FloorChangeType.ORDER_OPENED)).isEqualTo(1);
        assertThat(changesOf(FloorChangeType.ORDER_TRANSFERRED)).isEqualTo(1);
        assertThat(changesOf(FloorChangeType.ORDER_VOIDED)).isEqualTo(1);

        // The opened order carries both table and order id on its ping.
        DiningFloorChanged opened = events.stream(DiningFloorChanged.class)
                .filter(e -> e.change() == FloorChangeType.ORDER_OPENED).findFirst().orElseThrow();
        assertThat(opened.tableId()).isEqualTo(t1.id());
        assertThat(opened.orderId()).isEqualTo(order.id());
        assertThat(opened.at()).isNotNull();
    }

    private long changesOf(FloorChangeType type) {
        return events.stream(DiningFloorChanged.class).filter(e -> e.change() == type).count();
    }
}
