package com.company.pos.inventory.application;

import com.company.pos.common.util.Identifiers;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.domain.StockMovement;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.sales.api.ReturnCompleted;
import java.time.Instant;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Adds returned quantities back to on-hand and appends a positive RETURN movement when a return
 * completes (after-commit, async, own transaction; mirrors {@link SaleCompletedListener}). A return
 * only increases stock, so there is no negative-stock warning and no low-stock emission.
 */
@Component
class ReturnCompletedListener {

    private final StockLevelRepository stock;
    private final StockMovementRepository movements;

    ReturnCompletedListener(StockLevelRepository stock, StockMovementRepository movements) {
        this.stock = stock;
        this.movements = movements;
    }

    @ApplicationModuleListener
    void on(ReturnCompleted event) {
        for (ReturnCompleted.ReturnedLine line : event.lines()) {
            StockLevel level = stock.findBySkuAndLocationCode(line.sku(), event.locationCode())
                    .orElseGet(() -> stock.save(
                            new StockLevel(Identifiers.newId(), line.sku(), event.locationCode())));
            level.setQuantityOnHand(level.getQuantityOnHand().add(line.quantity()));
            movements.save(new StockMovement(Identifiers.newId(), line.sku(), event.locationCode(),
                    line.quantity(), "RETURN", event.returnId().toString(), Instant.now()));
        }
    }
}
