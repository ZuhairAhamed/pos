package com.company.pos.product.web;

import com.company.pos.common.exception.DomainException;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductController {

    private final ProductCatalog catalog;

    ProductController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    List<ProductView> list(@RequestParam(name = "q", required = false) String query) {
        if (query == null || query.isBlank()) {
            return catalog.findAll();
        }
        return catalog.search(query);
    }

    @GetMapping("/products/{sku}")
    ProductView bySku(@PathVariable String sku) {
        return catalog.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
    }
}
