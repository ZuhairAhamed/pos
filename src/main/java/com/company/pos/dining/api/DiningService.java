package com.company.pos.dining.api;

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

    // --- lines ---
    OrderView addLine(UUID orderId, AddLineCommand command, String addedBy);

    OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note, CourseTag course);

    OrderView removeLine(UUID orderId, UUID lineId);

    // --- close / void ---
    SaleView closeOrder(UUID orderId, CloseOrderCommand command, String cashierUsername,
            boolean callerIsManager);

    void voidOrder(UUID orderId, String reason);
}
