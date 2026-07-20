package com.company.pos.terminal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StationAssignmentView(String sku, String stationName) {
}
