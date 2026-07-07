package com.company.pos.kitchen.api;

import java.util.List;

public interface KitchenService {

    StationAssignmentView assignSku(String sku, String stationName);

    void unassignSku(String sku);

    List<StationAssignmentView> listAssignments();

    /** The station a SKU routes to: its explicit assignment, else the configured default station. */
    String stationFor(String sku);
}
