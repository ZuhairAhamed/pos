package com.company.pos.dining.api;

import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.SaleView;
import java.math.BigDecimal;
import java.util.List;
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
     *  <p>Assumes the no-discount, no-waiver close path (prices with no discounts and
     *  {@code waiveServiceCharge=false}). If dining ever passes line/transaction discounts or a
     *  manager waiver at close, thread those same inputs through here too — otherwise the quoted
     *  grandTotal would exceed what close charges and the tender would be rejected. */
    QuoteView quoteOrder(UUID orderId);

    SaleView closeOrder(UUID orderId, CloseOrderCommand command, String cashierUsername,
            boolean callerIsManager);

    List<SaleView> closeOrderSplit(UUID orderId, SplitCloseCommand command, String cashierUsername,
            boolean callerIsManager);

    List<UUID> listOrderSaleIds(UUID orderId);

    void voidOrder(UUID orderId, String reason);
}
