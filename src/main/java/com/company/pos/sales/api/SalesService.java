package com.company.pos.sales.api;

import java.util.Map;
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

    /** Fetch a committed sale by its unique receipt number; 404 (NOT_FOUND) when absent. */
    SaleView getSaleByReceipt(String receiptNumber);

    void reprint(UUID saleId);

    /**
     * Renders the stored sale's receipt and emails it to {@code toAddress}. Throws a
     * validation error (400) for a blank/malformed address and not-found (404) for an
     * unknown sale. Unlike print, delivery failure is NOT swallowed.
     */
    void emailReceipt(UUID saleId, String toAddress);

    /**
     * Read-only pricing pass: prices the cart with NO discounts, applies tax, returns the totals.
     * Creates no sale, takes no payment, fires no event. Used to learn a cart's total up front
     * (e.g. to split it evenly).
     */
    QuoteView quote(UUID cartId);

    /** As {@link #quote(UUID)} but optionally applies the configured service charge. */
    QuoteView quote(UUID cartId, boolean applyServiceCharge);

    /**
     * As {@link #quote(UUID, boolean)} but priced WITH the given manual discounts, exactly as
     * checkout would apply them. Pure calculator: the cashier discount cap is NOT enforced here
     * (quote commits nothing; checkout is the sole enforcement point) but reason codes are still
     * validated. Null discount arguments mean "none".
     */
    QuoteView quote(UUID cartId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean applyServiceCharge);
}
