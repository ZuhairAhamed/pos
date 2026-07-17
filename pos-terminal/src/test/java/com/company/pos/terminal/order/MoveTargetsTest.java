package com.company.pos.terminal.order;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.TableView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MoveTargetsTest {

    private final UUID t1 = UUID.randomUUID();
    private final UUID t2 = UUID.randomUUID();
    private final UUID t3 = UUID.randomUUID();
    private final UUID t4 = UUID.randomUUID();

    private TableView table(UUID id, String label, boolean active) {
        return new TableView(id, label, 4, active);
    }

    private OpenOrderView openOn(UUID tableId) {
        return new OpenOrderView(UUID.randomUUID(), tableId, "L", Instant.now(), 0, "DINE_IN");
    }

    @Test
    void excludesCurrentOccupiedAndInactive() {
        List<TableView> tables = List.of(
                table(t1, "T1", true),   // current → excluded
                table(t2, "T2", true),   // occupied → excluded
                table(t3, "T3", false),  // inactive → excluded
                table(t4, "T4", true));  // free → kept
        List<OpenOrderView> open = List.of(openOn(t1), openOn(t2));

        List<UUID> ids = MoveTargets.freeTargets(tables, open, t1).stream()
                .map(TableView::id).collect(Collectors.toList());

        assertEquals(List.of(t4), ids);
    }

    @Test
    void emptyWhenNoFreeTables() {
        List<TableView> tables = List.of(table(t1, "T1", true), table(t2, "T2", true));
        List<OpenOrderView> open = List.of(openOn(t2));
        assertTrue(MoveTargets.freeTargets(tables, open, t1).isEmpty());
    }
}
