package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CategoryView;
import com.company.pos.terminal.api.CreateProductRequest;
import com.company.pos.terminal.api.ProductAdminApi;
import com.company.pos.terminal.api.ProductAdminView;
import com.company.pos.terminal.api.UpdateProductRequest;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the product catalogue admin screen. Synchronous like the other VMs — the controller
 * runs it off the FX thread via FxTasks and reads the return value; the only observable written
 * off-thread is {@code errorMessage}, inside the {@code ui} dispatcher.
 */
public class ProductAdminViewModel {

    private final ProductAdminApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public ProductAdminViewModel(ProductAdminApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<ProductAdminView> load() {
        try {
            List<ProductAdminView> list = api.list();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public List<CategoryView> loadCategories() {
        try {
            List<CategoryView> list = api.listCategories();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public ProductAdminView create(CreateProductRequest req) {
        try {
            ProductAdminView v = api.create(req);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public ProductAdminView update(String sku, UpdateProductRequest req) {
        try {
            ProductAdminView v = api.update(sku, req);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean deactivate(String sku) {
        return voidCall(() -> api.deactivate(sku));
    }

    public boolean reactivate(String sku) {
        return voidCall(() -> api.reactivate(sku));
    }

    private boolean voidCall(Runnable call) {
        try {
            call.run();
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    private void fail(ApiException e) {
        String msg = messageOf(e);
        ui.accept(() -> errorMessage.set(msg));
    }

    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
