package com.company.pos.product.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.product.api.ProductPriceChanged;
import com.company.pos.product.api.ProductSync;
import com.company.pos.product.domain.Category;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.CategoryRepository;
import com.company.pos.product.infrastructure.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ProductErpSyncService implements ProductSync {

    private static final String STREAM = "products";

    private final ErpClient erpClient;
    private final SyncCursorStore cursors;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final DomainEvents events;

    ProductErpSyncService(ErpClient erpClient, SyncCursorStore cursors,
            ProductRepository products, CategoryRepository categories, DomainEvents events) {
        this.erpClient = erpClient;
        this.cursors = cursors;
        this.products = products;
        this.categories = categories;
        this.events = events;
    }

    @Override
    public int sync() {
        long cursor = cursors.get(STREAM);
        List<ErpProduct> batch = erpClient.fetchProductsSince(cursor);
        long maxVersion = cursor;
        int upserted = 0;

        for (ErpProduct e : batch) {
            if (e.version() > maxVersion) {
                maxVersion = e.version();
            }
            UUID categoryId = upsertCategory(e);

            Product existing = products.findBySku(e.sku()).orElse(null);
            if (existing != null && existing.getErpVersion() >= e.version()) {
                continue;
            }
            BigDecimal oldPrice = existing != null ? existing.getUnitPrice() : null;
            Product p = existing != null ? existing : new Product(Identifiers.newId(), e.sku(), e.name());
            p.setName(e.name());
            p.setCategoryId(categoryId);
            p.setCategoryName(e.categoryName());
            p.setBarcode(e.barcode());
            p.setUnitOfMeasure(e.unitOfMeasure());
            p.setUnitPrice(e.unitPrice());
            p.setCurrencyCode(e.currencyCode());
            p.setErpVersion(e.version());
            p.setActive(e.active());
            products.save(p);
            upserted++;
            if (oldPrice != null && oldPrice.compareTo(e.unitPrice()) != 0) {
                events.publish(new ProductPriceChanged(e.sku(), oldPrice, e.unitPrice(), e.version()));
            }
        }

        if (maxVersion > cursor) {
            cursors.set(STREAM, maxVersion);
        }
        return upserted;
    }

    private UUID upsertCategory(ErpProduct e) {
        Category category = categories.findByCode(e.categoryCode())
                .orElseGet(() -> new Category(Identifiers.newId(), e.categoryCode(), e.categoryName()));
        category.setName(e.categoryName());
        if (e.version() > category.getErpVersion()) {
            category.setErpVersion(e.version());
        }
        categories.save(category);
        return category.getId();
    }
}
