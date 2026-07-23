package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.VariantAdminApi;
import com.company.pos.terminal.api.dto.VariantGroupAdminView;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the variant builder. Synchronous like the other admin VMs — the controller runs it
 * off the FX thread via FxTasks. The only observable written off-thread is {@code errorMessage},
 * inside the {@code ui} dispatcher. Validation short-circuits BEFORE the API call (tests assert
 * the API was not called on invalid input). Mutations return true on success.
 */
public class VariantBuilderViewModel {

    private final VariantAdminApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public VariantBuilderViewModel(VariantAdminApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<VariantGroupAdminView> load() {
        try {
            List<VariantGroupAdminView> list = api.listAdmin();
            clearError();
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    // --- group mutations ---

    public boolean createGroup(String name) {
        String err = validateName(name);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> api.createGroup(name.trim()));
    }

    public boolean updateGroup(UUID groupId, String name) {
        String err = validateName(name);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> api.updateGroup(groupId, name.trim()));
    }

    public boolean deactivateGroup(UUID groupId) {
        return run(() -> api.deactivateGroup(groupId));
    }

    public boolean reactivateGroup(UUID groupId) {
        return run(() -> api.reactivateGroup(groupId));
    }

    // --- member mutations ---

    public boolean addMember(UUID groupId, String sku, String displayLabel) {
        String err = validateSku(sku);
        if (err != null) {
            setError(err);
            return false;
        }
        String labelErr = validateLabel(displayLabel);
        if (labelErr != null) {
            setError(labelErr);
            return false;
        }
        return run(() -> api.addMember(groupId, sku.trim(), displayLabel.trim()));
    }

    public boolean updateMember(UUID groupId, UUID memberId, String displayLabel) {
        String err = validateLabel(displayLabel);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> api.updateMember(groupId, memberId, displayLabel.trim()));
    }

    public boolean deactivateMember(UUID groupId, UUID memberId) {
        return run(() -> api.deactivateMember(groupId, memberId));
    }

    public boolean reactivateMember(UUID groupId, UUID memberId) {
        return run(() -> api.reactivateMember(groupId, memberId));
    }

    // --- helpers ---

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

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "Group name is required";
        }
        return null;
    }

    private static String validateSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return "SKU is required";
        }
        return null;
    }

    private static String validateLabel(String label) {
        if (label == null || label.isBlank()) {
            return "Display label is required";
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
