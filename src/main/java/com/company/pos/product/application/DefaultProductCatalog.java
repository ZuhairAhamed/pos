package com.company.pos.product.application;

import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultProductCatalog implements ProductCatalog {

    private final ProductRepository products;

    DefaultProductCatalog(ProductRepository products) {
        this.products = products;
    }

    @Override
    public Optional<ProductView> findBySku(String sku) {
        return products.findBySku(sku).map(this::toView);
    }

    @Override
    public List<ProductView> search(String query) {
        return products.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(query, query)
                .stream().map(this::toView).toList();
    }

    @Override
    public List<ProductView> findAll() {
        return products.findAll().stream().map(this::toView).toList();
    }

    private ProductView toView(Product p) {
        return new ProductView(p.getSku(), p.getName(), p.getCategoryName(), p.getBarcode(),
                p.getUnitOfMeasure(), p.getUnitPrice(), p.getCurrencyCode(), p.isActive(),
                p.isAvailable());
    }
}
