package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.VariantGroupAdminView;
import com.company.pos.terminal.api.dto.VariantMemberAdminView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.VariantBuilderViewModel;
import com.company.pos.terminal.viewmodel.VariantRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

/**
 * MANAGER/ADMIN variant builder. Master/detail: groups table (master) → members table (detail).
 * Selecting a group renders its members from the already-loaded admin view — no per-selection fetch.
 * All I/O runs in FxTasks work lambdas; each mutation's onDone re-kicks reload(); dialogs are I/O-free.
 * Adding a member first uses SkuPickerDialog (re-uses existing), then VariantMemberFormDialog for label.
 * Does NOT implement Navigator.Screen (no socket/timer).
 */
public class VariantBuilderController {

    private static final System.Logger LOG = System.getLogger(VariantBuilderController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final VariantBuilderViewModel vm;

    private List<VariantGroupAdminView> allGroups = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private TableView<VariantGroupAdminView> groupsTable;
    @FXML private TableColumn<VariantGroupAdminView, String> gNameCol;
    @FXML private TableColumn<VariantGroupAdminView, String> gMemberCol;
    @FXML private TableColumn<VariantGroupAdminView, String> gStatusCol;
    @FXML private Button newGroupButton;
    @FXML private Button editGroupButton;
    @FXML private Button deactivateGroupButton;
    @FXML private Button reactivateGroupButton;
    @FXML private Label detailTitle;
    @FXML private TableView<VariantMemberAdminView> membersTable;
    @FXML private TableColumn<VariantMemberAdminView, String> mSkuCol;
    @FXML private TableColumn<VariantMemberAdminView, String> mLabelCol;
    @FXML private TableColumn<VariantMemberAdminView, String> mStatusCol;
    @FXML private Button addMemberButton;
    @FXML private Button editMemberButton;
    @FXML private Button deactivateMemberButton;
    @FXML private Button reactivateMemberButton;

    public VariantBuilderController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new VariantBuilderViewModel(services.variantAdminApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        // Groups table columns
        gNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        gMemberCol.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(c.getValue().members().size())));
        gStatusCol.setCellValueFactory(c -> new SimpleStringProperty(
                VariantRows.statusLabel(c.getValue().active())));

        // Members table columns
        mSkuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sku()));
        mLabelCol.setCellValueFactory(c -> new SimpleStringProperty(
                VariantRows.memberLabel(c.getValue().sku(), c.getValue().displayLabel())));
        mStatusCol.setCellValueFactory(c -> new SimpleStringProperty(
                VariantRows.statusLabel(c.getValue().active())));

        errorLabel.textProperty().bind(vm.errorMessage());
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        groupsTable.getSelectionModel().selectedItemProperty()
                .addListener((o, a, sel) -> renderDetail(sel));
        membersTable.getSelectionModel().selectedItemProperty()
                .addListener((o, a, sel) -> refreshMemberButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        newGroupButton.setOnAction(e -> newGroup());
        editGroupButton.setOnAction(e -> editGroup());
        deactivateGroupButton.setOnAction(e -> groupActive(false));
        reactivateGroupButton.setOnAction(e -> groupActive(true));
        addMemberButton.setOnAction(e -> addMember());
        editMemberButton.setOnAction(e -> editMember());
        deactivateMemberButton.setOnAction(e -> memberActive(false));
        reactivateMemberButton.setOnAction(e -> memberActive(true));

        renderDetail(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        UUID keep = selectedGroupId();
        final List<VariantGroupAdminView>[] gh = new List[1];
        FxTasks.run(
                () -> gh[0] = vm.load(),
                () -> {
                    if (gh[0] != null) {
                        allGroups = gh[0];
                    }
                    applyFilter();
                    reselect(keep);
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load variant groups failed", err));
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<VariantGroupAdminView> shown = allGroups.stream()
                .filter(g -> q.isEmpty() || g.name().toLowerCase().contains(q))
                .toList();
        groupsTable.setItems(FXCollections.observableArrayList(shown));
        refreshGroupButtons(selectedGroup());
    }

    private void reselect(UUID id) {
        if (id == null) {
            return;
        }
        for (VariantGroupAdminView g : groupsTable.getItems()) {
            if (g.id().equals(id)) {
                groupsTable.getSelectionModel().select(g);
                return;
            }
        }
    }

    private VariantGroupAdminView selectedGroup() {
        return groupsTable.getSelectionModel().getSelectedItem();
    }

    private UUID selectedGroupId() {
        VariantGroupAdminView g = selectedGroup();
        return g == null ? null : g.id();
    }

    private void renderDetail(VariantGroupAdminView g) {
        refreshGroupButtons(g);
        if (g == null) {
            detailTitle.setText("Select a group");
            membersTable.setItems(FXCollections.observableArrayList());
        } else {
            detailTitle.setText(g.name());
            membersTable.setItems(FXCollections.observableArrayList(g.members()));
        }
        refreshMemberButtons(null);
        boolean hasGroup = g != null;
        addMemberButton.setDisable(!hasGroup);
    }

    private void refreshGroupButtons(VariantGroupAdminView g) {
        editGroupButton.setDisable(g == null);
        deactivateGroupButton.setDisable(g == null || !g.active());
        reactivateGroupButton.setDisable(g == null || g.active());
    }

    private void refreshMemberButtons(VariantMemberAdminView m) {
        editMemberButton.setDisable(m == null);
        deactivateMemberButton.setDisable(m == null || !m.active());
        reactivateMemberButton.setDisable(m == null || m.active());
    }

    // --- group actions ---

    private void newGroup() {
        Optional<VariantGroupFormDialog.GroupResult> r = VariantGroupFormDialog.promptForGroup(null);
        r.ifPresent(res -> kick(() -> vm.createGroup(res.name())));
    }

    private void editGroup() {
        VariantGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        Optional<VariantGroupFormDialog.GroupResult> r =
                VariantGroupFormDialog.promptForGroup(g.name());
        r.ifPresent(res -> kick(() -> vm.updateGroup(g.id(), res.name())));
    }

    private void groupActive(boolean active) {
        VariantGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        kick(() -> active ? vm.reactivateGroup(g.id()) : vm.deactivateGroup(g.id()));
    }

    // --- member actions ---

    private void addMember() {
        VariantGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        // Step 1: load product list off-thread; in onDone open the dialog chain (I/O-free).
        // FX-threading: products fetched in work lambda, dialogs opened in onDone (FX thread).
        final com.company.pos.terminal.api.dto.ProductView[][] ph =
                new com.company.pos.terminal.api.dto.ProductView[1][];
        FxTasks.run(
                () -> {
                    List<com.company.pos.terminal.api.dto.ProductView> list =
                            services.productApi.list();
                    ph[0] = list != null
                            ? list.toArray(new com.company.pos.terminal.api.dto.ProductView[0])
                            : new com.company.pos.terminal.api.dto.ProductView[0];
                },
                () -> {
                    Optional<String> sku = SkuPickerDialog.pickSku(
                            java.util.Arrays.asList(ph[0]));
                    sku.ifPresent(s -> {
                        Optional<VariantMemberFormDialog.MemberResult> labelResult =
                                VariantMemberFormDialog.promptForLabel(null);
                        labelResult.ifPresent(lr ->
                                kick(() -> vm.addMember(g.id(), s, lr.displayLabel())));
                    });
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load products for SKU picker failed", err));
    }

    private void editMember() {
        VariantGroupAdminView g = selectedGroup();
        VariantMemberAdminView m = membersTable.getSelectionModel().getSelectedItem();
        if (g == null || m == null) {
            return;
        }
        Optional<VariantMemberFormDialog.MemberResult> r =
                VariantMemberFormDialog.promptForLabel(m.displayLabel());
        r.ifPresent(res -> kick(() -> vm.updateMember(g.id(), m.id(), res.displayLabel())));
    }

    private void memberActive(boolean active) {
        VariantGroupAdminView g = selectedGroup();
        VariantMemberAdminView m = membersTable.getSelectionModel().getSelectedItem();
        if (g == null || m == null) {
            return;
        }
        kick(() -> active ? vm.reactivateMember(g.id(), m.id()) : vm.deactivateMember(g.id(), m.id()));
    }

    /** Run a boolean-returning VM mutation off-thread; reload on success. */
    private void kick(java.util.function.BooleanSupplier work) {
        final boolean[] holder = {false};
        FxTasks.run(() -> holder[0] = work.getAsBoolean(),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Variant mutation failed", err));
    }
}
