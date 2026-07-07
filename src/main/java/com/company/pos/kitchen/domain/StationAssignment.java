package com.company.pos.kitchen.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "kitchen_station_assignment")
public class StationAssignment {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(name = "station_name", nullable = false, length = 100)
    private String stationName;

    protected StationAssignment() {
        // JPA
    }

    public StationAssignment(String sku, String stationName) {
        this.sku = sku;
        this.stationName = stationName;
    }

    public String getSku() {
        return sku;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }
}
