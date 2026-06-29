package com.company.pos.cashdrawer.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.sales.api.ReturnCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Pays the cash portion of a completed return out of the terminal's open drawer (after-commit,
 * async, own transaction; mirrors {@link SaleCompletedCashListener}). {@code recordCashRefund} is a
 * no-op when no session is open, so a cash refund processed without an open drawer is simply not
 * captured (not an error) and never wedges the publication.
 */
@Component
class ReturnCompletedCashListener {

    private final CashDrawerService drawer;

    ReturnCompletedCashListener(CashDrawerService drawer) {
        this.drawer = drawer;
    }

    @ApplicationModuleListener
    void on(ReturnCompleted event) {
        if (event.cashRefundTotal() == null || event.cashRefundTotal().signum() <= 0) {
            return; // nothing refunded in cash
        }
        drawer.recordCashRefund(event.terminalId(), event.cashRefundTotal(),
                event.returnId().toString());
    }
}
