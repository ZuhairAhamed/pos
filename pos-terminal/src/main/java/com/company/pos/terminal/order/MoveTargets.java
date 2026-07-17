package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.TableView;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure helper: given all tables, the current open orders, and the order's current table, returns
 * the tables an order may be transferred to — active tables that are neither the current table nor
 * already occupied by an open order. No FX, no I/O; unit-tested headlessly.
 */
public final class MoveTargets {

    private MoveTargets() {}

    public static List<TableView> freeTargets(List<TableView> tables, List<OpenOrderView> openOrders,
            UUID currentTableId) {
        Set<UUID> occupied = openOrders.stream()
                .map(OpenOrderView::tableId)
                .collect(Collectors.toSet());
        return tables.stream()
                .filter(TableView::active)
                .filter(t -> !t.id().equals(currentTableId))
                .filter(t -> !occupied.contains(t.id()))
                .collect(Collectors.toList());
    }
}
