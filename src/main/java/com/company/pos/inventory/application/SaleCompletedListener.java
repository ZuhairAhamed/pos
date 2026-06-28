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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decrements on-hand and appends a movement-ledger row when a sale completes.
 * Runs synchronously inside the checkout transaction and swallows per-line
 * {@link RuntimeException}s so that ordinary stock failures do not roll back the sale.
 * Note: a database constraint violation (e.g. a duplicate key) marks the shared
 * transaction rollback-only, so under that specific failure the checkout would still
 * roll back. Full decoupling — where the listener runs after commit and never affects
 * the sale transaction — arrives in Phase 3 via the transactional outbox and
 * {@code @TransactionalEventListener(AFTER_COMMIT)}.
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

    @EventListener
    @Transactional
    void on(SaleCompleted event) {
        for (SaleCompleted.SoldLine line : event.lines()) {
            try {
                applyMovement(event, line);
            } catch (RuntimeException ex) {
                log.warn("Failed to apply stock movement for sku {} on sale {}",
                        line.sku(), event.receiptNumber(), ex);
            }
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
