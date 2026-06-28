package com.company.pos.inventory.application;

import com.company.pos.common.util.Identifiers;
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
 * Decrements on-hand and appends a movement-ledger row when a sale completes.
 *
 * <p>From Phase 3a this is an {@link ApplicationModuleListener}: it runs <em>after</em> the
 * checkout transaction commits, asynchronously, in its own transaction. The Spring Modulith
 * Event Publication Registry persists an {@code event_publication} row for this listener inside
 * the publishing (sale) transaction and stamps its completion only when this method returns
 * normally. Consequently a failure here can no longer roll back the sale; it leaves an
 * incomplete publication that is resubmitted on restart (or via
 * {@code IncompleteEventPublications}). We therefore no longer swallow exceptions — letting one
 * propagate is what triggers durable retry. A negative-stock result is still only a warning,
 * not a failure, so it neither blocks nor poisons the publication.
 */
@Component
class SaleCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(SaleCompletedListener.class);

    private final StockLevelRepository stock;
    private final StockMovementRepository movements;

    SaleCompletedListener(StockLevelRepository stock, StockMovementRepository movements) {
        this.stock = stock;
        this.movements = movements;
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
        BigDecimal updated = level.getQuantityOnHand().subtract(line.quantity());
        if (updated.signum() < 0) {
            log.warn("Stock for sku {} at {} went negative ({}) after sale {}",
                    line.sku(), event.locationCode(), updated, event.receiptNumber());
        }
        level.setQuantityOnHand(updated);
        movements.save(new StockMovement(Identifiers.newId(), line.sku(), event.locationCode(),
                line.quantity().negate(), "SALE", event.saleId().toString(), Instant.now()));
    }
}
