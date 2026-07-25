package com.company.pos.product.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.product.api.CategoryView;
import com.company.pos.product.api.CreateProductCommand;
import com.company.pos.product.api.ProductChangeType;
import com.company.pos.product.api.ProductChanged;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.api.UpdateProductCommand;
import com.company.pos.product.domain.Category;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.CategoryRepository;
import com.company.pos.product.infrastructure.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product-catalogue admin use cases. Class-level {@code @Transactional}: each method does its
 * repository write and publishes {@link ProductChanged} in the same transaction; the {@code audit}
 * module records those events async AFTER commit. There is no synchronous {@code REQUIRES_NEW}
 * audit call, so single-writer SQLite never deadlocks (the {@code ProductErpSyncService} pattern).
 * SKU uniqueness is pre-checked for a friendly error and backed by the DB unique constraint.
 */
@Service
@Transactional
public class ProductAdminService {

    private static final String DEFAULT_UOM = "EA";

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ConfigurationService config;
    private final DomainEvents events;

    public ProductAdminService(ProductRepository products, CategoryRepository categories,
            ConfigurationService config, DomainEvents events) {
        this.products = products;
        this.categories = categories;
        this.config = config;
        this.events = events;
    }

    public ProductView createProduct(CreateProductCommand cmd) {
        String sku = requireText(cmd.sku(), "SKU is required");
        String name = requireText(cmd.name(), "Name is required");
        BigDecimal price = requireNonNegativePrice(cmd.unitPrice());
        if (products.findBySku(sku).isPresent()) {
            throw DomainException.conflict("SKU already in use");
        }
        String actor = actor();
        Category category = resolveCategory(cmd.categoryCode(), cmd.categoryName(), actor);
        String currency = trimToNull(cmd.currencyCode());
        if (currency == null) {
            currency = config.getString(SettingKey.CURRENCY_CODE);
        }
        String uom = trimToNull(cmd.unitOfMeasure());
        if (uom == null) {
            uom = DEFAULT_UOM;
        }

        Product p = new Product(Identifiers.newId(), sku, name);
        if (category != null) {
            p.changeCategory(category.getId(), category.getName());
        }
        p.changePrice(price, currency);
        p.changeUnitOfMeasure(uom);
        p.changeBarcode(trimToNull(cmd.barcode()));
        products.save(p);

        events.publish(new ProductChanged(sku, ProductChangeType.CREATED, actor, null, null));
        return toView(p);
    }

    public ProductView updateProduct(String sku, UpdateProductCommand cmd) {
        String name = requireText(cmd.name(), "Name is required");
        BigDecimal price = requireNonNegativePrice(cmd.unitPrice());
        Product p = products.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
        String actor = actor();
        BigDecimal oldPrice = p.getUnitPrice();

        boolean categorySupplied =
                trimToNull(cmd.categoryCode()) != null || trimToNull(cmd.categoryName()) != null;
        String currency = trimToNull(cmd.currencyCode());
        if (currency == null) {
            currency = p.getCurrencyCode() != null ? p.getCurrencyCode()
                    : config.getString(SettingKey.CURRENCY_CODE);
        }
        String uom = trimToNull(cmd.unitOfMeasure());
        if (uom == null) {
            uom = p.getUnitOfMeasure() != null ? p.getUnitOfMeasure() : DEFAULT_UOM;
        }

        p.rename(name);
        if (categorySupplied) {
            Category category = resolveCategory(cmd.categoryCode(), cmd.categoryName(), actor);
            p.changeCategory(category.getId(), category.getName());
        }
        // else: leave the product's existing category unchanged
        p.changePrice(price, currency);
        p.changeUnitOfMeasure(uom);
        p.changeBarcode(trimToNull(cmd.barcode()));
        products.save(p);

        events.publish(new ProductChanged(sku, ProductChangeType.UPDATED, actor, null, null));
        if (oldPrice == null || oldPrice.compareTo(price) != 0) {
            events.publish(new ProductChanged(sku, ProductChangeType.PRICE_CHANGED, actor,
                    oldPrice, price));
        }
        return toView(p);
    }

    public void deactivate(String sku) {
        Product p = products.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
        p.deactivate();
        products.save(p);
        events.publish(new ProductChanged(sku, ProductChangeType.DEACTIVATED, actor(), null, null));
    }

    public void reactivate(String sku) {
        Product p = products.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
        p.activate();
        products.save(p);
        events.publish(new ProductChanged(sku, ProductChangeType.REACTIVATED, actor(), null, null));
    }

    @Transactional(readOnly = true)
    public List<CategoryView> listCategories() {
        return categories.findAll().stream()
                .map(c -> new CategoryView(c.getCode(), c.getName()))
                .toList();
    }

    /**
     * Resolve the category for a create/update. A non-blank {@code code} must reference an existing
     * category. Otherwise a non-blank {@code name} derives a code (uppercased alphanumeric, ≤50),
     * reusing an existing category with that code or creating a new one (and publishing
     * CATEGORY_CREATED). Both blank ⇒ no category.
     */
    private Category resolveCategory(String code, String name, String actor) {
        String cd = trimToNull(code);
        if (cd != null) {
            return categories.findByCode(cd)
                    .orElseThrow(() -> DomainException.validation("No category with code " + cd));
        }
        String nm = trimToNull(name);
        if (nm == null) {
            return null;
        }
        String derived = deriveCode(nm);
        if (derived.isEmpty()) {
            throw DomainException.validation("Category name must contain a letter or digit");
        }
        return categories.findByCode(derived).orElseGet(() -> {
            Category created = new Category(Identifiers.newId(), derived, nm);
            categories.save(created);
            events.publish(new ProductChanged(derived, ProductChangeType.CATEGORY_CREATED, actor,
                    null, null));
            return created;
        });
    }

    private static String deriveCode(String name) {
        String code = name.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return code.length() > 50 ? code.substring(0, 50) : code;
    }

    private ProductView toView(Product p) {
        return new ProductView(p.getSku(), p.getName(), p.getCategoryName(), p.getBarcode(),
                p.getUnitOfMeasure(), p.getUnitPrice(), p.getCurrencyCode(), p.isActive(),
                p.isAvailable());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw DomainException.validation(message);
        }
        return value.trim();
    }

    private static BigDecimal requireNonNegativePrice(BigDecimal price) {
        if (price == null) {
            throw DomainException.validation("Price is required");
        }
        if (price.signum() < 0) {
            throw DomainException.validation("Price must not be negative");
        }
        return price;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "system";
    }
}
