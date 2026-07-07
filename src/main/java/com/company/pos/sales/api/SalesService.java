package com.company.pos.sales.api;

import java.util.UUID;

public interface SalesService {

    /**
     * Checkout as a non-manager (cashier). Discounts are subject to the cashier cap.
     * Delegates to {@link #checkout(CheckoutCommand, String, boolean)} with {@code callerIsManager = false}.
     */
    SaleView checkout(CheckoutCommand command, String cashierUsername);

    /**
     * Checkout. {@code callerIsManager} lifts the cashier discount cap (manager = unlimited).
     */
    SaleView checkout(CheckoutCommand command, String cashierUsername, boolean callerIsManager);

    SaleView getSale(UUID saleId);

    void reprint(UUID saleId);

    /**
     * Read-only pricing pass: prices the cart with NO discounts, applies tax, returns the totals.
     * Creates no sale, takes no payment, fires no event. Used to learn a cart's total up front
     * (e.g. to split it evenly).
     */
    QuoteView quote(UUID cartId);

    /** As {@link #quote(UUID)} but optionally applies the configured service charge. */
    QuoteView quote(UUID cartId, boolean applyServiceCharge);
}
