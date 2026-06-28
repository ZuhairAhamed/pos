package com.company.pos.cashdrawer.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.sales.api.SaleCompleted;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captures the cash portion of a completed sale into the terminal's open drawer session.
 * Runs synchronously inside the checkout transaction and swallows {@link RuntimeException} so a
 * drawer failure never rolls back the sale. If no session is open the capture is a no-op (handled
 * inside {@link CashDrawerService#recordCashSale}). Same caveat as the inventory listener: a DB
 * constraint violation can still mark the shared transaction rollback-only; the Phase 3 outbox +
 * {@code @TransactionalEventListener(AFTER_COMMIT)} removes that coupling.
 */
@Component
class SaleCompletedCashListener {

    private static final Logger log = LoggerFactory.getLogger(SaleCompletedCashListener.class);

    private final CashDrawerService drawer;

    SaleCompletedCashListener(CashDrawerService drawer) {
        this.drawer = drawer;
    }

    @EventListener
    @Transactional
    void on(SaleCompleted event) {
        if (event.cashTotal() == null || event.cashTotal().signum() <= 0) {
            return;
        }
        try {
            drawer.recordCashSale(event.terminalId(), event.cashTotal(), event.saleId().toString());
        } catch (RuntimeException ex) {
            log.warn("Failed to capture cash for sale {} on terminal {}",
                    event.receiptNumber(), event.terminalId(), ex);
        }
    }
}
