package com.company.pos.product.web;

import com.company.pos.product.api.ProductView;
import com.company.pos.product.api.SetAvailabilityCommand;
import com.company.pos.product.application.ProductAdminService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cashier-level item 86 toggle. Deliberately NOT annotated with {@code @PreAuthorize}: the
 * SecurityConfig authenticates every request outside {@code /auth/*}, so any signed-in
 * cashier/server may 86 or restore an item. The change is audited via {@code ProductChanged}.
 */
@RestController
class ProductAvailabilityController {

    private final ProductAdminService admin;

    ProductAvailabilityController(ProductAdminService admin) {
        this.admin = admin;
    }

    @PutMapping("/products/{sku}/availability")
    ProductView setAvailability(@PathVariable String sku, @RequestBody SetAvailabilityCommand cmd) {
        return admin.setAvailability(sku, cmd.available());
    }
}
