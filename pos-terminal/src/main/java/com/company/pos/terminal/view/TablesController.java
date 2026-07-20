package com.company.pos.terminal.view;

import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.TableRows;
import com.company.pos.terminal.viewmodel.TableRows.TableRow;
import com.company.pos.terminal.viewmodel.TablesViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * MANAGER/ADMIN dining-tables screen. Loads tables off the FX thread via FxTasks, maps them into
 * rows (TableRows, deriving counter vs dine-in from the label prefix), and creates/edits/deactivates/
 * reactivates through the I/O-free TableFormDialog. Search filters the cached rows client-side.
 * (The dto TableView is fully-qualified in the reload holder to avoid a clash with javafx TableView.)
 */
public class TablesController {

    private static final System.Logger LOG = System.getLogger(TablesController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final TablesViewModel vm;
    private final String counterPrefix;

    private List<TableRow> allRows = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button newButton;
    @FXML private Button editButton;
    @FXML private Button deactivateButton;
    @FXML private Button reactivateButton;
    @FXML private TableView<TableRow> table;
    @FXML private TableColumn<TableRow, String> labelCol;
    @FXML private TableColumn<TableRow, String> seatsCol;
    @FXML private TableColumn<TableRow, String> typeCol;
    @FXML private TableColumn<TableRow, String> statusCol;

    public TablesController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.counterPrefix = services.config.takeawayLabelPrefix();
        this.vm = new TablesViewModel(services.tableAdminApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        labelCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        seatsCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().seats())));
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().counter() ? "Counter" : "Dine-in"));
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().active() ? "Active" : "Inactive"));

        errorLabel.textProperty().bind(vm.errorMessage());
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        newButton.setOnAction(e -> createTable());
        editButton.setOnAction(e -> editSelected());
        deactivateButton.setOnAction(e -> deactivateSelected());
        reactivateButton.setOnAction(e -> reactivateSelected());

        refreshButtons(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        final List<com.company.pos.terminal.api.dto.TableView>[] th = new List[1];
        FxTasks.run(
                () -> th[0] = vm.loadTables(),
                () -> {
                    if (th[0] != null) {
                        allRows = TableRows.build(th[0], counterPrefix);
                        applyFilter();
                    }
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load tables failed", err));
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<TableRow> shown = allRows.stream()
                .filter(r -> q.isEmpty() || r.label().toLowerCase().contains(q))
                .toList();
        table.setItems(FXCollections.observableArrayList(shown));
    }

    private void refreshButtons(TableRow sel) {
        editButton.setDisable(sel == null);
        deactivateButton.setDisable(sel == null || !sel.active());
        reactivateButton.setDisable(sel == null || sel.active());
    }

    private void createTable() {
        Optional<TableFormDialog.Result> r = TableFormDialog.promptForTable(counterPrefix, null);
        r.ifPresent(res -> {
            final com.company.pos.terminal.api.dto.TableView[] holder =
                    new com.company.pos.terminal.api.dto.TableView[1];
            FxTasks.run(() -> holder[0] = vm.create(res.label(), res.seats()),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Create table failed", err));
        });
    }

    private void editSelected() {
        TableRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Optional<TableFormDialog.Result> r = TableFormDialog.promptForTable(counterPrefix, sel);
        r.ifPresent(res -> {
            final com.company.pos.terminal.api.dto.TableView[] holder =
                    new com.company.pos.terminal.api.dto.TableView[1];
            FxTasks.run(() -> holder[0] = vm.update(sel.id(), res.label(), res.seats()),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Update table failed", err));
        });
    }

    private void deactivateSelected() {
        TableRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || !sel.active()) {
            return;
        }
        final boolean[] holder = {false};
        FxTasks.run(() -> holder[0] = vm.deactivate(sel.id()),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Deactivate table failed", err));
    }

    private void reactivateSelected() {
        TableRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || sel.active()) {
            return;
        }
        final com.company.pos.terminal.api.dto.TableView[] holder =
                new com.company.pos.terminal.api.dto.TableView[1];
        FxTasks.run(() -> holder[0] = vm.reactivate(sel.id()),
                () -> { if (holder[0] != null) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Reactivate table failed", err));
    }
}
