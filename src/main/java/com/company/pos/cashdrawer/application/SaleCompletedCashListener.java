package com.company.pos.cashdrawer.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.sales.api.SaleCompleted;
import java.math.BigDecimal;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Attributes the cash portion of a completed sale to the terminal's open drawer session.
 *
 * <p>From Phase 3a this is an {@link ApplicationModuleListener}: it runs after the checkout
 * transaction commits, asynchronously, in its own transaction, and is tracked by the Spring
 * Modulith Event Publication Registry. A failure leaves an incomplete publication for replay
 * rather than affecting the (already committed) sale, so we no longer swallow exceptions.
 * {@link CashDrawerService#recordCashSale} is still a no-op when no session is open for the
 * terminal, so cash sales rung up without an open drawer are simply not captured (not errors).
 */
@Component
class SaleCompletedCashListener {

    private final CashDrawerService drawer;

    SaleCompletedCashListener(CashDrawerService drawer) {
        this.drawer = drawer;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        if (event.cashTotal() == null || event.cashTotal().signum() <= 0) {
            return; // nothing paid in cash
        }
        drawer.recordCashSale(event.terminalId(), event.cashTotal(), event.saleId().toString());
    }
}
