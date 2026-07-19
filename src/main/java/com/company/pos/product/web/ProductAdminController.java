package com.company.pos.product.web;

import com.company.pos.product.api.CategoryView;
import com.company.pos.product.api.CreateProductCommand;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.api.UpdateProductCommand;
import com.company.pos.product.application.ProductAdminService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('ADMIN')")
class ProductAdminController {

    private final ProductAdminService admin;

    ProductAdminController(ProductAdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    ProductView create(@RequestBody CreateProductCommand cmd) {
        return admin.createProduct(cmd);
    }

    @PutMapping("/products/{sku}")
    ProductView update(@PathVariable String sku, @RequestBody UpdateProductCommand cmd) {
        return admin.updateProduct(sku, cmd);
    }

    @PostMapping("/products/{sku}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivate(@PathVariable String sku) {
        admin.deactivate(sku);
    }

    @PostMapping("/products/{sku}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reactivate(@PathVariable String sku) {
        admin.reactivate(sku);
    }

    @GetMapping("/categories")
    List<CategoryView> categories() {
        return admin.listCategories();
    }
}
