package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.dto.TableView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure mapping of tables into display rows, deriving the counter/dine-in type from the label prefix. */
public final class TableRows {

    private TableRows() {
    }

    /** {@code counter} is true when the label carries the takeaway prefix (a QUICK_SERVICE counter). */
    public record TableRow(UUID id, String label, int seats, boolean active, boolean counter) {
    }

    public static List<TableRow> build(List<TableView> tables, String counterPrefix) {
        List<TableRow> rows = new ArrayList<>();
        if (tables != null) {
            for (TableView t : tables) {
                boolean counter = counterPrefix != null && !counterPrefix.isBlank()
                        && t.label() != null && t.label().startsWith(counterPrefix);
                rows.add(new TableRow(t.id(), t.label(), t.seats(), t.active(), counter));
            }
        }
        return rows;
    }
}
