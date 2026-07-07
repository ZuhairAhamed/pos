package com.company.pos.kitchen.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.kitchen.api.StationAssignmentView;
import com.company.pos.kitchen.domain.StationAssignment;
import com.company.pos.kitchen.infrastructure.StationAssignmentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultKitchenService implements KitchenService {

    private final StationAssignmentRepository assignments;
    private final ConfigurationService config;

    DefaultKitchenService(StationAssignmentRepository assignments, ConfigurationService config) {
        this.assignments = assignments;
        this.config = config;
    }

    @Override
    public StationAssignmentView assignSku(String sku, String stationName) {
        if (sku == null || sku.isBlank()) {
            throw DomainException.validation("sku is required");
        }
        if (stationName == null || stationName.isBlank()) {
            throw DomainException.validation("stationName is required");
        }
        String key = sku.trim();
        String station = stationName.trim();
        StationAssignment saved = assignments.findById(key)
                .map(existing -> {
                    existing.setStationName(station);
                    return existing;
                })
                .orElseGet(() -> assignments.save(new StationAssignment(key, station)));
        return new StationAssignmentView(saved.getSku(), saved.getStationName());
    }

    @Override
    public void unassignSku(String sku) {
        assignments.deleteById(sku);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationAssignmentView> listAssignments() {
        return assignments.findAll().stream()
                .map(a -> new StationAssignmentView(a.getSku(), a.getStationName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String stationFor(String sku) {
        return assignments.findById(sku)
                .map(StationAssignment::getStationName)
                .orElseGet(() -> config.getString(SettingKey.KITCHEN_DEFAULT_STATION));
    }
}
