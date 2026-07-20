package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.dto.TableView;
import com.company.pos.terminal.viewmodel.TableRows.TableRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TableRowsTest {

    @Test
    void counterLabelDerivesCounterType() {
        List<TableRow> rows = TableRows.build(
                List.of(new TableView(UUID.randomUUID(), "Counter 1", 1, true)), "Counter ");
        assertTrue(rows.get(0).counter());
    }

    @Test
    void plainLabelIsDineIn() {
        List<TableRow> rows = TableRows.build(
                List.of(new TableView(UUID.randomUUID(), "T1", 4, true)), "Counter ");
        assertFalse(rows.get(0).counter());
    }

    @Test
    void nullListYieldsEmpty() {
        assertEquals(0, TableRows.build(null, "Counter ").size());
    }
}
