package com.company.pos.terminal.view;

import com.company.pos.terminal.api.StationAssignmentView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.KitchenRoutingViewModel;
import com.company.pos.terminal.viewmodel.RoutingRows;
import com.company.pos.terminal.viewmodel.RoutingRows.RoutingRow;
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
 * MANAGER/ADMIN kitchen-routing screen. Loads products + station assignments off the FX thread via
 * FxTasks, joins them into rows (RoutingRows), and lets the user assign/change/clear a SKU's station
 * through the I/O-free StationPickerDialog. Search filters the cached rows client-side.
 */
public class KitchenRoutingController {

    private static final System.Logger LOG = System.getLogger(KitchenRoutingController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final KitchenRoutingViewModel vm;

    private List<RoutingRow> allRows = new ArrayList<>();
    private List<String> stations = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button assignButton;
    @FXML private Button clearButton;
    @FXML private TableView<RoutingRow> table;
    @FXML private TableColumn<RoutingRow, String> skuCol;
    @FXML private TableColumn<RoutingRow, String> nameCol;
    @FXML private TableColumn<RoutingRow, String> stationCol;

    public KitchenRoutingController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new KitchenRoutingViewModel(services.kitchenApi, services.productApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        skuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sku()));
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        stationCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().routed() ? c.getValue().station() : "Default"));

        errorLabel.textProperty().bind(vm.errorMessage());
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        assignButton.setOnAction(e -> assignSelected());
        clearButton.setOnAction(e -> clearSelected());

        refreshButtons(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        final List<ProductView>[] ph = new List[1];
        final List<StationAssignmentView>[] ah = new List[1];
        FxTasks.run(
                () -> {
                    ph[0] = vm.loadProducts();
                    ah[0] = vm.loadAssignments();
                },
                () -> {
                    if (ph[0] != null && ah[0] != null) {
                        allRows = RoutingRows.build(ph[0], ah[0]);
                        stations = RoutingRows.distinctStations(ah[0]);
                        applyFilter();
                    }
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load kitchen routing failed", err));
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<RoutingRow> shown = allRows.stream()
                .filter(r -> q.isEmpty()
                        || r.sku().toLowerCase().contains(q)
                        || r.name().toLowerCase().contains(q))
                .toList();
        table.setItems(FXCollections.observableArrayList(shown));
    }

    private void refreshButtons(RoutingRow sel) {
        assignButton.setDisable(sel == null);
        clearButton.setDisable(sel == null || !sel.routed());
    }

    private void assignSelected() {
        RoutingRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Optional<String> station = StationPickerDialog.promptForStation(stations, sel.station());
        station.ifPresent(s -> {
            final StationAssignmentView[] holder = new StationAssignmentView[1];
            FxTasks.run(() -> holder[0] = vm.assign(sel.sku(), s),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Assign station failed", err));
        });
    }

    private void clearSelected() {
        RoutingRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || !sel.routed()) {
            return;
        }
        final boolean[] holder = {false};
        FxTasks.run(() -> holder[0] = vm.unassign(sel.sku()),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Clear routing failed", err));
    }
}
