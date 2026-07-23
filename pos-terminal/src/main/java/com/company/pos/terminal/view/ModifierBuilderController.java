package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ModifierGroupAdminView;
import com.company.pos.terminal.api.dto.ModifierOptionAdminView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.ModifierBuilderViewModel;
import com.company.pos.terminal.viewmodel.ModifierRows;
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
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

/**
 * MANAGER/ADMIN modifier builder. Master/detail: a groups table on top; selecting a group renders its
 * options table + assigned-SKU list from the cached admin view (no extra fetch). All I/O runs in
 * FxTasks work lambdas; each mutation's onDone re-kicks reload(); dialogs are I/O-free.
 */
public class ModifierBuilderController {

    private static final System.Logger LOG = System.getLogger(ModifierBuilderController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final ModifierBuilderViewModel vm;

    private List<ModifierGroupAdminView> allGroups = new ArrayList<>();
    private List<ProductView> products = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private TableView<ModifierGroupAdminView> groupsTable;
    @FXML private TableColumn<ModifierGroupAdminView, String> gNameCol;
    @FXML private TableColumn<ModifierGroupAdminView, String> gSelCol;
    @FXML private TableColumn<ModifierGroupAdminView, String> gOptCol;
    @FXML private TableColumn<ModifierGroupAdminView, String> gStatusCol;
    @FXML private Button newGroupButton;
    @FXML private Button editGroupButton;
    @FXML private Button deactivateGroupButton;
    @FXML private Button reactivateGroupButton;
    @FXML private Label detailTitle;
    @FXML private TableView<ModifierOptionAdminView> optionsTable;
    @FXML private TableColumn<ModifierOptionAdminView, String> oNameCol;
    @FXML private TableColumn<ModifierOptionAdminView, String> oPriceCol;
    @FXML private TableColumn<ModifierOptionAdminView, String> oStatusCol;
    @FXML private Button addOptionButton;
    @FXML private Button editOptionButton;
    @FXML private Button deactivateOptionButton;
    @FXML private Button reactivateOptionButton;
    @FXML private ListView<String> assignedList;
    @FXML private Button assignButton;
    @FXML private Button unassignButton;

    public ModifierBuilderController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new ModifierBuilderViewModel(services.menuAdminApi, services.productApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        gNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        gSelCol.setCellValueFactory(c -> new SimpleStringProperty(
                ModifierRows.selectionsLabel(c.getValue().minSelections(), c.getValue().maxSelections())));
        gOptCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().options().size())));
        gStatusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().active() ? "Active" : "Inactive"));

        oNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        oPriceCol.setCellValueFactory(c -> new SimpleStringProperty(ModifierRows.priceDeltaLabel(c.getValue().priceDelta())));
        oStatusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().active() ? "Active" : "Inactive"));
        assignedList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String sku, boolean empty) {
                super.updateItem(sku, empty);
                setText(empty || sku == null ? null : ModifierRows.skuLabel(sku, products));
            }
        });

        errorLabel.textProperty().bind(vm.errorMessage());
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        groupsTable.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> renderDetail(sel));
        optionsTable.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshOptionButtons(sel));
        assignedList.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> unassignButton.setDisable(sel == null));

        backButton.setOnAction(e -> navigator.toAdmin());
        newGroupButton.setOnAction(e -> newGroup());
        editGroupButton.setOnAction(e -> editGroup());
        deactivateGroupButton.setOnAction(e -> groupActive(false));
        reactivateGroupButton.setOnAction(e -> groupActive(true));
        addOptionButton.setOnAction(e -> addOption());
        editOptionButton.setOnAction(e -> editOption());
        deactivateOptionButton.setOnAction(e -> optionActive(false));
        reactivateOptionButton.setOnAction(e -> optionActive(true));
        assignButton.setOnAction(e -> assign());
        unassignButton.setOnAction(e -> unassign());

        renderDetail(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        UUID keep = selectedGroupId();
        final List<ModifierGroupAdminView>[] gh = new List[1];
        final List<ProductView>[] ph = new List[1];
        FxTasks.run(
                () -> {
                    gh[0] = vm.loadGroups();
                    ph[0] = vm.loadProducts();
                },
                () -> {
                    if (gh[0] != null) {
                        allGroups = gh[0];
                    }
                    if (ph[0] != null) {
                        products = ph[0];
                    }
                    applyFilter();
                    reselect(keep);
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load modifier groups failed", err));
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<ModifierGroupAdminView> shown = allGroups.stream()
                .filter(g -> q.isEmpty() || g.name().toLowerCase().contains(q))
                .toList();
        groupsTable.setItems(FXCollections.observableArrayList(shown));
        refreshGroupButtons(selectedGroup());
    }

    private void reselect(UUID id) {
        if (id == null) {
            return;
        }
        for (ModifierGroupAdminView g : groupsTable.getItems()) {
            if (g.id().equals(id)) {
                groupsTable.getSelectionModel().select(g);
                return;
            }
        }
    }

    private ModifierGroupAdminView selectedGroup() {
        return groupsTable.getSelectionModel().getSelectedItem();
    }

    private UUID selectedGroupId() {
        ModifierGroupAdminView g = selectedGroup();
        return g == null ? null : g.id();
    }

    private void renderDetail(ModifierGroupAdminView g) {
        refreshGroupButtons(g);
        if (g == null) {
            detailTitle.setText("Select a group");
            optionsTable.setItems(FXCollections.observableArrayList());
            assignedList.setItems(FXCollections.observableArrayList());
        } else {
            detailTitle.setText(g.name());
            optionsTable.setItems(FXCollections.observableArrayList(g.options()));
            assignedList.setItems(FXCollections.observableArrayList(g.assignedSkus()));
        }
        refreshOptionButtons(null);
        unassignButton.setDisable(true);
        boolean hasGroup = g != null;
        addOptionButton.setDisable(!hasGroup);
        assignButton.setDisable(!hasGroup);
    }

    private void refreshGroupButtons(ModifierGroupAdminView g) {
        editGroupButton.setDisable(g == null);
        deactivateGroupButton.setDisable(g == null || !g.active());
        reactivateGroupButton.setDisable(g == null || g.active());
    }

    private void refreshOptionButtons(ModifierOptionAdminView o) {
        editOptionButton.setDisable(o == null);
        deactivateOptionButton.setDisable(o == null || !o.active());
        reactivateOptionButton.setDisable(o == null || o.active());
    }

    // --- group actions ---
    private void newGroup() {
        Optional<ModifierGroupFormDialog.GroupResult> r = ModifierGroupFormDialog.promptForGroup(null, 0, 1);
        r.ifPresent(res -> kick(() -> vm.createGroup(res.name(), res.min(), res.max())));
    }

    private void editGroup() {
        ModifierGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        Optional<ModifierGroupFormDialog.GroupResult> r =
                ModifierGroupFormDialog.promptForGroup(g.name(), g.minSelections(), g.maxSelections());
        r.ifPresent(res -> kick(() -> vm.updateGroup(g.id(), res.name(), res.min(), res.max())));
    }

    private void groupActive(boolean active) {
        ModifierGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        kick(() -> active ? vm.reactivateGroup(g.id()) : vm.deactivateGroup(g.id()));
    }

    // --- option actions ---
    private void addOption() {
        ModifierGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        Optional<ModifierOptionFormDialog.OptionResult> r = ModifierOptionFormDialog.promptForOption(null, "");
        r.ifPresent(res -> kick(() -> vm.addOption(g.id(), res.name(), res.priceDelta())));
    }

    private void editOption() {
        ModifierGroupAdminView g = selectedGroup();
        ModifierOptionAdminView o = optionsTable.getSelectionModel().getSelectedItem();
        if (g == null || o == null) {
            return;
        }
        Optional<ModifierOptionFormDialog.OptionResult> r =
                ModifierOptionFormDialog.promptForOption(o.name(), o.priceDelta().toPlainString());
        r.ifPresent(res -> kick(() -> vm.updateOption(g.id(), o.id(), res.name(), res.priceDelta())));
    }

    private void optionActive(boolean active) {
        ModifierGroupAdminView g = selectedGroup();
        ModifierOptionAdminView o = optionsTable.getSelectionModel().getSelectedItem();
        if (g == null || o == null) {
            return;
        }
        kick(() -> active ? vm.reactivateOption(g.id(), o.id()) : vm.deactivateOption(g.id(), o.id()));
    }

    // --- assignment actions ---
    private void assign() {
        ModifierGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        Optional<String> sku = SkuPickerDialog.pickSku(products);
        sku.ifPresent(s -> kick(() -> vm.assign(g.id(), s)));
    }

    private void unassign() {
        ModifierGroupAdminView g = selectedGroup();
        String sku = assignedList.getSelectionModel().getSelectedItem();
        if (g == null || sku == null) {
            return;
        }
        kick(() -> vm.unassign(g.id(), sku));
    }

    /** Run a boolean-returning VM mutation off-thread; reload on success. */
    private void kick(java.util.function.BooleanSupplier work) {
        final boolean[] holder = {false};
        FxTasks.run(() -> holder[0] = work.getAsBoolean(),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Modifier mutation failed", err));
    }
}
