package com.company.pos.inventory.web;

import com.company.pos.common.exception.DomainException;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.StockView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InventoryController {

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/inventory/{sku}")
    StockView onHand(@PathVariable String sku) {
        return inventory.onHand(sku)
                .orElseThrow(() -> DomainException.notFound("No stock for sku " + sku));
    }
}
