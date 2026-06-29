package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Request to return lines of a prior sale. Identify the sale by id or receipt number. */
public record ReturnCommand(UUID originalSaleId, String receiptNumber, List<ReturnLineRequest> lines) {

    public record ReturnLineRequest(int lineNo, BigDecimal quantity) {
    }
}
