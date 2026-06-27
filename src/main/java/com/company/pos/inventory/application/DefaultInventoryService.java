package com.company.pos.inventory.application;

import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.StockView;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultInventoryService implements InventoryService {

    private final StockLevelRepository stock;

    DefaultInventoryService(StockLevelRepository stock) {
        this.stock = stock;
    }

    @Override
    public Optional<StockView> onHand(String sku) {
        List<StockLevel> levels = stock.findBySku(sku);
        if (levels.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal total = levels.stream()
                .map(StockLevel::getQuantityOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(new StockView(sku, total));
    }
}
