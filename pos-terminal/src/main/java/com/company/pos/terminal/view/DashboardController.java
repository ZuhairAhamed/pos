package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.DashboardSnapshot;
import com.company.pos.terminal.api.dto.LowStockTile;
import com.company.pos.terminal.api.dto.ProductPerformanceReport.ProductLine;
import com.company.pos.terminal.api.dto.ShiftView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.DashboardViewModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** Manager dashboard: KPI tiles + best-sellers / low-stock / open-shifts tables. Read-only. */
public class DashboardController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(DashboardController.class.getName());
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

    private final Services services;
    private final Navigator navigator;
    private final DashboardViewModel vm;

    @FXML private Label errorLabel;
    @FXML private Button refreshButton;
    @FXML private Button backButton;
    @FXML private FlowPane kpiRow;
    @FXML private TableView<ProductLine> bestSellersTable;
    @FXML private TableColumn<ProductLine, String> bsNameCol;
    @FXML private TableColumn<ProductLine, String> bsQtyCol;
    @FXML private TableColumn<ProductLine, String> bsRevenueCol;
    @FXML private TableView<LowStockTile> lowStockTable;
    @FXML private TableColumn<LowStockTile, String> lsNameCol;
    @FXML private TableColumn<LowStockTile, String> lsOnHandCol;
    @FXML private TableColumn<LowStockTile, String> lsReorderCol;
    @FXML private TableView<ShiftView> shiftsTable;
    @FXML private TableColumn<ShiftView, String> shTerminalCol;
    @FXML private TableColumn<ShiftView, String> shOpenedByCol;
    @FXML private TableColumn<ShiftView, String> shOpenedAtCol;
    @FXML private Label activeCashiersLabel;

    public DashboardController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new DashboardViewModel(services.dashboardApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        bsNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        bsQtyCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().quantitySold().stripTrailingZeros().toPlainString()));
        lsNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        lsOnHandCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().onHand().stripTrailingZeros().toPlainString()));
        lsReorderCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().reorderLevel().stripTrailingZeros().toPlainString()));
        shTerminalCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().terminalId()));
        shOpenedByCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().openedBy()));
        shOpenedAtCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().openedAt() == null ? "" : TS.format(c.getValue().openedAt())));

        refreshButton.setOnAction(e -> load());
        backButton.setOnAction(e -> navigator.toHome());
        load();
    }

    private void load() {
        DashboardSnapshot[] holder = new DashboardSnapshot[1];
        FxTasks.run(
                () -> holder[0] = vm.load(),
                () -> { if (holder[0] != null) render(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Dashboard load failed", err));
    }

    private void render(DashboardSnapshot s) {
        kpiRow.getChildren().setAll(
                kpi("Today's sales", money(s.todaysSales().grossSales(), s.currencyCode()),
                        s.todaysSales().saleCount() + " sales"),
                kpi("Revenue today", money(s.revenue().today(), s.currencyCode()), ""),
                kpi("Revenue " + s.revenue().windowDays() + "d",
                        money(s.revenue().window(), s.currencyCode()), ""));
        bsRevenueCol.setCellValueFactory(c -> new SimpleStringProperty(
                money(c.getValue().revenue(), s.currencyCode())));
        bestSellersTable.setItems(FXCollections.observableArrayList(s.bestSellers()));
        lowStockTable.setItems(FXCollections.observableArrayList(s.lowStock()));
        shiftsTable.setItems(FXCollections.observableArrayList(s.openShifts()));
        activeCashiersLabel.setText(s.activeCashiers().isEmpty()
                ? "No active cashiers"
                : "Active: " + String.join(", ", s.activeCashiers()));
    }

    private VBox kpi(String label, String value, String sub) {
        VBox tile = new VBox(4);
        tile.getStyleClass().add("kpi-tile");
        Label v = new Label(value);
        v.getStyleClass().add("kpi-value");
        Label l = new Label(label);
        l.getStyleClass().add("kpi-label");
        tile.getChildren().addAll(v, l);
        if (sub != null && !sub.isBlank()) {
            Label sb = new Label(sub);
            sb.getStyleClass().add("kpi-sub");
            tile.getChildren().add(sb);
        }
        return tile;
    }

    private static String money(java.math.BigDecimal amount, String currency) {
        return (amount == null ? "0" : amount.toPlainString()) + " " + currency;
    }
}
