package com.company.pos.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.ProductRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ProductCatalogTest {

    @Autowired
    ProductRepository products;
    @Autowired
    ProductCatalog catalog;

    private Product newProduct(String sku, String name) {
        Product p = new Product(Identifiers.newId(), sku, name);
        p.setCategoryName("Beverages");
        p.setBarcode("100000" + sku);
        p.setUnitOfMeasure("EA");
        p.setUnitPrice(new BigDecimal("4.50"));
        p.setCurrencyCode("SAR");
        p.setActive(true);
        return p;
    }

    @Test
    void findBySkuReturnsView() {
        products.save(newProduct("COLA", "Cola Can"));

        ProductView view = catalog.findBySku("COLA").orElseThrow();
        assertThat(view.name()).isEqualTo("Cola Can");
        assertThat(view.categoryName()).isEqualTo("Beverages");
        assertThat(view.unitPrice()).isEqualByComparingTo("4.50");
        assertThat(view.currencyCode()).isEqualTo("SAR");
    }

    @Test
    void searchMatchesPartialName() {
        products.save(newProduct("COLA", "Cola Can"));
        products.save(newProduct("WATER", "Spring Water"));

        assertThat(catalog.search("cola")).extracting(ProductView::sku).containsExactly("COLA");
    }
}
