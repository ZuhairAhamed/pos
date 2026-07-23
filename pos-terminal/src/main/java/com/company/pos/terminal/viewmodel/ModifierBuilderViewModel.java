package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.MenuAdminApi;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.dto.ModifierGroupAdminView;
import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the modifier builder. Synchronous like the other admin VMs — the controller runs it
 * off the FX thread via FxTasks and reads the return value; the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher. Mutations return true on success; the
 * controller re-kicks reload() in onDone.
 */
public class ModifierBuilderViewModel {

    private final MenuAdminApi menu;
    private final ProductApi productApi;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public ModifierBuilderViewModel(MenuAdminApi menu, ProductApi productApi, Consumer<Runnable> ui) {
        this.menu = menu;
        this.productApi = productApi;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<ModifierGroupAdminView> loadGroups() {
        try {
            List<ModifierGroupAdminView> list = menu.listGroups();
            clearError();
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public List<ProductView> loadProducts() {
        try {
            List<ProductView> list = productApi.list();
            clearError();
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean createGroup(String name, int min, int max) {
        String err = validateGroup(name, min, max);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> menu.createGroup(name.trim(), min, max));
    }

    public boolean updateGroup(UUID groupId, String name, int min, int max) {
        String err = validateGroup(name, min, max);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> menu.updateGroup(groupId, name.trim(), min, max));
    }

    public boolean deactivateGroup(UUID groupId) {
        return run(() -> menu.deactivateGroup(groupId));
    }

    public boolean reactivateGroup(UUID groupId) {
        return run(() -> menu.reactivateGroup(groupId));
    }

    public boolean addOption(UUID groupId, String name, BigDecimal priceDelta) {
        String err = validateOption(name, priceDelta);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> menu.addOption(groupId, name.trim(), priceDelta));
    }

    public boolean updateOption(UUID groupId, UUID optionId, String name, BigDecimal priceDelta) {
        String err = validateOption(name, priceDelta);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> menu.updateOption(groupId, optionId, name.trim(), priceDelta));
    }

    public boolean deactivateOption(UUID groupId, UUID optionId) {
        return run(() -> menu.deactivateOption(groupId, optionId));
    }

    public boolean reactivateOption(UUID groupId, UUID optionId) {
        return run(() -> menu.reactivateOption(groupId, optionId));
    }

    public boolean assign(UUID groupId, String sku) {
        if (sku == null || sku.isBlank()) {
            setError("Choose a product to assign");
            return false;
        }
        return run(() -> menu.assignSku(groupId, sku));
    }

    public boolean unassign(UUID groupId, String sku) {
        return run(() -> menu.unassignSku(groupId, sku));
    }

    private boolean run(Runnable call) {
        try {
            call.run();
            clearError();
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    private static String validateGroup(String name, int min, int max) {
        if (name == null || name.isBlank()) {
            return "Group name is required";
        }
        if (min < 0 || max < 1 || max < min) {
            return "Invalid min/max selections";
        }
        return null;
    }

    private static String validateOption(String name, BigDecimal priceDelta) {
        if (name == null || name.isBlank()) {
            return "Option name is required";
        }
        if (priceDelta == null) {
            return "Price delta is required (may be 0)";
        }
        return null;
    }

    private void clearError() {
        ui.accept(() -> errorMessage.set(""));
    }

    private void setError(String msg) {
        ui.accept(() -> errorMessage.set(msg));
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
