package com.company.pos.dining.api;

import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.SaleView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DiningService {

    // --- table registry ---
    TableView registerTable(RegisterTableCommand command);

    void deactivateTable(UUID tableId);

    List<TableView> listTables();

    // --- orders ---
    OrderView openOrder(OpenOrderCommand command, String openedBy);

    OrderView getOrder(UUID orderId);

    List<OpenOrderView> listOpenOrders();

    /** Relocates an OPEN order to a different, active, free table. Fails if the order is not open,
     *  the target is the same/unknown/inactive, or the target already has an open order. */
    OrderView transferOrder(UUID orderId, UUID targetTableId);

    /** Merges the absorbed OPEN dine-in order's lines into the survivor OPEN dine-in order
     *  (preserving qty/note/course/modifiers/fired state), then voids the absorbed order.
     *  Fails if either order is not open or not dine-in, they are the same order, or the
     *  absorbed order has no lines. Returns the survivor. */
    OrderView mergeOrders(UUID survivorOrderId, UUID absorbedOrderId);

    // --- fire to kitchen ---
    OrderView fireOrder(UUID orderId, String firedBy);

    // --- lines ---
    OrderView addLine(UUID orderId, AddLineCommand command, String addedBy);

    OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note, CourseTag course);

    OrderView removeLine(UUID orderId, UUID lineId);

    // --- close / void ---
    /** Read-only pricing pass for an open order: prices it exactly as {@link #closeOrder} would
     *  (same lines, same service-charge decision), returns the authoritative totals. Creates no
     *  sale, closes no order.
     *  <p>Assumes the no-discount, no-waiver close path. For a discounted close, use
     *  {@link #quoteOrder(UUID, Map, DiscountInput)} with the same inputs the close will carry.
     *  A manager service-charge waiver at close still has no quote counterpart (waiver UI is
     *  out of scope) — thread {@code waiveServiceCharge} through here if that ever lands. */
    QuoteView quoteOrder(UUID orderId);

    /** As {@link #quoteOrder(UUID)} but priced WITH the given manual discounts, exactly as a
     *  close carrying the same discounts would price them (cap not enforced — quote is a pure
     *  calculator; the close still requires a manager for over-cap discounts). */
    QuoteView quoteOrder(UUID orderId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount);

    SaleView closeOrder(UUID orderId, CloseOrderCommand command, String cashierUsername,
            boolean callerIsManager);

    List<SaleView> closeOrderSplit(UUID orderId, SplitCloseCommand command, String cashierUsername,
            boolean callerIsManager);

    /** Prices a BY_ITEM partition exactly as {@link #closeOrderSplit} would (per-bill ephemeral
     *  carts, service charge per bill). Pure calculator: creates no sale, closes nothing, the
     *  order stays OPEN. Runs the same partition validation as close so mistakes fail at quote
     *  time. Assumes the no-discount, no-waiver close path (the split UI carries neither). */
    SplitQuoteView quoteSplitByItem(UUID orderId, List<List<UUID>> billLineIds);

    /** Prices an EVEN split: the whole-order quote plus the exact per-share amounts
     *  {@link #closeOrderSplit} would tender ({@code ways ≥ 2}; last share absorbs the
     *  rounding remainder). Pure calculator — the order stays OPEN. */
    SplitQuoteView quoteSplitEven(UUID orderId, int ways);

    List<UUID> listOrderSaleIds(UUID orderId);

    void voidOrder(UUID orderId, String reason);
}
