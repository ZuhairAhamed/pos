package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.StationAssignmentView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.viewmodel.RoutingRows.RoutingRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingRowsTest {

    private ProductView product(String sku, String name) {
        return new ProductView(sku, name, null, null, new BigDecimal("1.00"), true, true);
    }

    @Test
    void routedAndUnroutedRows() {
        List<RoutingRow> rows = RoutingRows.build(
                List.of(product("COLA", "Cola"), product("FRIES", "Fries")),
                List.of(new StationAssignmentView("FRIES", "Fryer")));
        RoutingRow cola = rows.stream().filter(r -> r.sku().equals("COLA")).findFirst().orElseThrow();
        RoutingRow fries = rows.stream().filter(r -> r.sku().equals("FRIES")).findFirst().orElseThrow();
        assertFalse(cola.routed());
        assertNull(cola.station());
        assertTrue(fries.routed());
        assertEquals("Fryer", fries.station());
    }

    @Test
    void orphanAssignmentSurfacesAsUnknownProduct() {
        List<RoutingRow> rows = RoutingRows.build(
                List.of(product("COLA", "Cola")),
                List.of(new StationAssignmentView("GONE", "Grill")));
        RoutingRow orphan = rows.stream().filter(r -> r.sku().equals("GONE")).findFirst().orElseThrow();
        assertEquals("(unknown product)", orphan.name());
        assertTrue(orphan.routed());
        assertEquals("Grill", orphan.station());
    }

    @Test
    void distinctStationsDedupesAndSkipsBlank() {
        List<String> s = RoutingRows.distinctStations(List.of(
                new StationAssignmentView("A", "Bar"),
                new StationAssignmentView("B", "Bar"),
                new StationAssignmentView("C", "Grill")));
        assertEquals(List.of("Bar", "Grill"), s);
    }
}
