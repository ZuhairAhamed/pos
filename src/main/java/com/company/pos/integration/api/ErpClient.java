package com.company.pos.integration.api;

import java.util.List;

public interface ErpClient {

    List<ErpProduct> fetchProductsSince(long version);

    List<ErpStockLevel> fetchStockLevelsSince(long version);

    /** Uploads a completed sale. Idempotent: a repeat {@code sale.saleId()} is a no-op. */
    void uploadSale(SaleUpload sale);

    /**
     * Uploads the stock-movement deltas produced by a sale. Idempotent on {@code saleId}: a repeat
     * batch for the same sale is a no-op.
     */
    void uploadStockMovements(String saleId, List<StockMovementUpload> movements);
}
