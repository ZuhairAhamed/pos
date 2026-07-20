package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.StationAssignmentView;
import com.company.pos.terminal.api.dto.ProductView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure join of products × station assignments into display rows, plus the distinct station names. */
public final class RoutingRows {

    private RoutingRows() {
    }

    /** A product's routing. {@code station} is the explicit assignment, or null when {@code routed==false}
     *  (the row falls back to the default station at fire time). */
    public record RoutingRow(String sku, String name, String station, boolean routed) {
    }

    public static List<RoutingRow> build(List<ProductView> products,
            List<StationAssignmentView> assignments) {
        Map<String, String> bySku = new LinkedHashMap<>();
        if (assignments != null) {
            for (StationAssignmentView a : assignments) {
                bySku.put(a.sku(), a.stationName());
            }
        }
        List<RoutingRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (products != null) {
            for (ProductView p : products) {
                String station = bySku.get(p.sku());
                rows.add(new RoutingRow(p.sku(), p.name(), station, station != null));
                seen.add(p.sku());
            }
        }
        // Orphan assignments (SKU no longer in the product list) — surface so stale routing can be cleared.
        for (Map.Entry<String, String> e : bySku.entrySet()) {
            if (!seen.contains(e.getKey())) {
                rows.add(new RoutingRow(e.getKey(), "(unknown product)", e.getValue(), true));
            }
        }
        return rows;
    }

    /** Distinct station names currently in use, insertion-ordered — feeds the picker's suggestions. */
    public static List<String> distinctStations(List<StationAssignmentView> assignments) {
        Set<String> out = new LinkedHashSet<>();
        if (assignments != null) {
            for (StationAssignmentView a : assignments) {
                if (a.stationName() != null && !a.stationName().isBlank()) {
                    out.add(a.stationName());
                }
            }
        }
        return new ArrayList<>(out);
    }
}
