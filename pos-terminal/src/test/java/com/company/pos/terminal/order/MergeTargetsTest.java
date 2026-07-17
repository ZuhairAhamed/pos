package com.company.pos.terminal.order;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.TableView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MergeTargetsTest {

    private final UUID t1 = UUID.randomUUID(); // current (occupied)
    private final UUID t2 = UUID.randomUUID(); // occupied dine-in → candidate
    private final UUID t3 = UUID.randomUUID(); // free (no order)
    private final UUID t4 = UUID.randomUUID(); // occupied but inactive table
    private final UUID t5 = UUID.randomUUID(); // occupied takeaway (QUICK_SERVICE)

    private TableView table(UUID id, boolean active) {
        return new TableView(id, "T", 4, active);
    }

    private OpenOrderView orderOn(UUID tableId, String serviceType) {
        return new OpenOrderView(UUID.randomUUID(), tableId, "T", Instant.now(), 1, serviceType);
    }

    @Test
    void keepsOtherOccupiedDineInExcludesRest() {
        List<TableView> tables = List.of(
                table(t1, true), table(t2, true), table(t3, true), table(t4, false), table(t5, true));
        List<OpenOrderView> open = List.of(
                orderOn(t1, "DINE_IN"),   // current → excluded
                orderOn(t2, "DINE_IN"),   // candidate → kept
                orderOn(t4, "DINE_IN"),   // inactive table → excluded
                orderOn(t5, "QUICK_SERVICE")); // takeaway → excluded
        // t3 has no order at all → not a candidate

        List<UUID> tableIds = MergeTargets.occupiedTargets(tables, open, t1).stream()
                .map(OpenOrderView::tableId).collect(Collectors.toList());

        assertEquals(List.of(t2), tableIds);
    }

    @Test
    void emptyWhenNoOtherOccupiedDineIn() {
        List<TableView> tables = List.of(table(t1, true), table(t3, true));
        List<OpenOrderView> open = List.of(orderOn(t1, "DINE_IN")); // only the current table
        assertTrue(MergeTargets.occupiedTargets(tables, open, t1).isEmpty());
    }
}
