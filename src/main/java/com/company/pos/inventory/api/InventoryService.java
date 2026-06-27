package com.company.pos.inventory.api;

import java.util.Optional;

public interface InventoryService {

    Optional<StockView> onHand(String sku);
}
