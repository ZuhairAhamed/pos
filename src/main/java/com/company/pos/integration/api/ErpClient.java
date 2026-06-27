package com.company.pos.integration.api;

import java.util.List;

public interface ErpClient {

    List<ErpProduct> fetchProductsSince(long version);

    List<ErpStockLevel> fetchStockLevelsSince(long version);
}
