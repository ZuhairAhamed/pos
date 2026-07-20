package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.dining.api.TableChangeType;
import com.company.pos.dining.api.TableChanged;
import com.company.pos.dining.api.TableView;
import com.company.pos.dining.api.UpdateTableCommand;
import java.util.List;
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
class TableAdminServiceTest {

    @Autowired DiningService dining;
    @Autowired ApplicationEvents events;

    @Test
    void createPublishesTableChangedCreated() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-1", 4));
        assertThat(typesFor(t.id().toString())).contains(TableChangeType.CREATED);
    }

    @Test
    void updateRenamesReseatsAndPublishesUpdated() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-2", 2));
        TableView updated = dining.updateTable(t.id(), new UpdateTableCommand("TA-2b", 6));
        assertThat(updated.label()).isEqualTo("TA-2b");
        assertThat(updated.seats()).isEqualTo(6);
        assertThat(typesFor(t.id().toString())).contains(TableChangeType.UPDATED);
    }

    @Test
    void updateRejectsDuplicateLabel() {
        dining.registerTable(new RegisterTableCommand("TA-3", 2));
        TableView other = dining.registerTable(new RegisterTableCommand("TA-4", 2));
        assertThatThrownBy(() -> dining.updateTable(other.id(), new UpdateTableCommand("TA-3", 2)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updateRejectsNonPositiveSeats() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-5", 2));
        assertThatThrownBy(() -> dining.updateTable(t.id(), new UpdateTableCommand("TA-5", 0)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateBlockedWhenTableHasOpenOrder() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-6", 4));
        dining.openOrder(new OpenOrderCommand(t.id(), ServiceType.DINE_IN), "tester");
        assertThatThrownBy(() -> dining.deactivateTable(t.id()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateThenReactivateFlips() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-7", 4));
        dining.deactivateTable(t.id());
        assertThat(typesFor(t.id().toString())).contains(TableChangeType.DEACTIVATED);
        TableView back = dining.reactivateTable(t.id());
        assertThat(back.active()).isTrue();
        assertThat(typesFor(t.id().toString())).contains(TableChangeType.REACTIVATED);
    }

    private List<TableChangeType> typesFor(String entityRef) {
        return events.stream(TableChanged.class)
                .filter(e -> e.entityRef().equals(entityRef))
                .map(TableChanged::type)
                .toList();
    }
}
