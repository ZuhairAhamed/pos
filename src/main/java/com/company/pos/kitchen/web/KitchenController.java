package com.company.pos.kitchen.web;

import com.company.pos.kitchen.api.AssignStationCommand;
import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.kitchen.api.StationAssignmentView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class KitchenController {

    private final KitchenService kitchen;

    KitchenController(KitchenService kitchen) {
        this.kitchen = kitchen;
    }

    @PostMapping("/kitchen/stations/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    StationAssignmentView assign(@RequestBody AssignStationCommand body) {
        return kitchen.assignSku(body.sku(), body.stationName());
    }

    @DeleteMapping("/kitchen/stations/assignments/{sku}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void unassign(@PathVariable String sku) {
        kitchen.unassignSku(sku);
    }

    @GetMapping("/kitchen/stations/assignments")
    List<StationAssignmentView> list() {
        return kitchen.listAssignments();
    }
}
