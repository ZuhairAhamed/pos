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
}
