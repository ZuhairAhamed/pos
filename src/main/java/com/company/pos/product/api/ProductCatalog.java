package com.company.pos.product.api;

import java.util.List;
import java.util.Optional;

public interface ProductCatalog {

    Optional<ProductView> findBySku(String sku);

    List<ProductView> search(String query);

    List<ProductView> findAll();
}
