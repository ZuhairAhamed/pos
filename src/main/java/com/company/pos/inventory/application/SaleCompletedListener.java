package com.company.pos.inventory.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.util.Identifiers;
import com.company.pos.inventory.api.LowStockDetected;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.domain.StockMovement;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.sales.api.SaleCompleted;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Decrements on-hand and appends a movement-ledger row when a sale completes (after-commit, async,
 * own transaction; see Phase 3a). From Phase 3c it also publishes {@link LowStockDetected} when a
 * decrement edge-crosses below the SKU's reorder level. Exceptions propagate (no swallow) so a
 * failure leaves the publication incomplete for replay; a negative-stock result is only a warning.
 */
@Component
class SaleCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(SaleCompletedListener.class);

    private final StockLevelRepository stock;
    private final StockMovementRepository movements;
    private final DomainEvents events;

    SaleCompletedListener(StockLevelRepository stock, StockMovementRepository movements,
            DomainEvents events) {
        this.stock = stock;
        this.movements = movements;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        for (SaleCompleted.SoldLine line : event.lines()) {
            applyMovement(event, line);
        }
    }

    private void applyMovement(SaleCompleted event, SaleCompleted.SoldLine line) {
        StockLevel level = stock.findBySkuAndLocationCode(line.sku(), event.locationCode())
                .orElseGet(() -> stock.save(
                        new StockLevel(Identifiers.newId(), line.sku(), event.locationCode())));
        BigDecimal previous = level.getQuantityOnHand();
        BigDecimal updated = previous.subtract(line.quantity());
        if (updated.signum() < 0) {
            log.warn("Stock for sku {} at {} went negative ({}) after sale {}",
                    line.sku(), event.locationCode(), updated, event.receiptNumber());
        }
        level.setQuantityOnHand(updated);
        movements.save(new StockMovement(Identifiers.newId(), line.sku(), event.locationCode(),
                line.quantity().negate(), "SALE", event.saleId().toString(), Instant.now()));

        BigDecimal reorder = level.getReorderLevel();
        if (reorder.signum() > 0 && previous.compareTo(reorder) >= 0 && updated.compareTo(reorder) < 0) {
            events.publish(new LowStockDetected(line.sku(), event.locationCode(), updated, reorder));
        }
    }
}
