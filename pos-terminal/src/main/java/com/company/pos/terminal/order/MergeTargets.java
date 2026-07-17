package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.TableView;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure helper: given all tables, the current open orders, and the order's current table, returns
 * the open orders an order may be merged with — open DINE_IN orders on active tables other than the
 * current table. No FX, no I/O; unit-tested headlessly. Mirror of {@code MoveTargets} (which returns
 * FREE tables); this returns OCCUPIED dine-in orders.
 */
public final class MergeTargets {

    private MergeTargets() {}

    public static List<OpenOrderView> occupiedTargets(List<TableView> tables,
            List<OpenOrderView> openOrders, UUID currentTableId) {
        Set<UUID> activeTableIds = tables.stream()
                .filter(TableView::active)
                .map(TableView::id)
                .collect(Collectors.toSet());
        return openOrders.stream()
                .filter(o -> !o.tableId().equals(currentTableId))
                .filter(o -> "DINE_IN".equals(o.serviceType()))
                .filter(o -> activeTableIds.contains(o.tableId()))
                .collect(Collectors.toList());
    }
}
