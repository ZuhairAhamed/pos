package com.company.pos.terminal.view;

import com.company.pos.terminal.api.ReportType;
import com.company.pos.terminal.api.dto.CashierReport;
import com.company.pos.terminal.api.dto.PaymentBreakdownReport;
import com.company.pos.terminal.api.dto.ProductPerformanceReport;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import com.company.pos.terminal.api.dto.TaxSummaryReport;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.ReportsViewModel;
import com.company.pos.terminal.viewmodel.ReportsViewModel.Preset;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Reports screen: report-type selector + preset/custom date range + results + CSV export. */
public class ReportsController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(ReportsController.class.getName());
    private static final int PRODUCTS_LIMIT = 50;

    private final Services services;
    private final Navigator navigator;
    private final ReportsViewModel vm;
    private final ToggleGroup typeGroup = new ToggleGroup();
    private final ToggleGroup rangeGroup = new ToggleGroup();

    private ReportType selectedType = ReportType.SALES;
    private LocalDate from;
    private LocalDate to;

    @FXML private Label errorLabel;
    @FXML private Button exportButton;
    @FXML private Button backButton;
    @FXML private HBox typeRow;
    @FXML private ToggleButton todayChip;
    @FXML private ToggleButton yesterdayChip;
    @FXML private ToggleButton last7Chip;
    @FXML private ToggleButton monthChip;
    @FXML private ToggleButton customChip;
    @FXML private HBox customRow;
    @FXML private DatePicker fromPicker;
    @FXML private DatePicker toPicker;
    @FXML private Button runButton;
    @FXML private StackPane resultArea;

    public ReportsController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new ReportsViewModel(services.reportingApi, Platform::runLater, java.time.Instant::now);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        for (ReportType type : ReportType.values()) {
            ToggleButton b = new ToggleButton(label(type));
            b.getStyleClass().add("chip");
            b.setToggleGroup(typeGroup);
            b.setUserData(type);
            b.setOnAction(e -> { selectedType = type; fetch(); });
            if (type == ReportType.SALES) b.setSelected(true);
            typeRow.getChildren().add(b);
        }

        todayChip.setToggleGroup(rangeGroup);
        yesterdayChip.setToggleGroup(rangeGroup);
        last7Chip.setToggleGroup(rangeGroup);
        monthChip.setToggleGroup(rangeGroup);
        customChip.setToggleGroup(rangeGroup);
        todayChip.setOnAction(e -> applyPreset(Preset.TODAY));
        yesterdayChip.setOnAction(e -> applyPreset(Preset.YESTERDAY));
        last7Chip.setOnAction(e -> applyPreset(Preset.LAST_7_DAYS));
        monthChip.setOnAction(e -> applyPreset(Preset.THIS_MONTH));
        customChip.setOnAction(e -> showCustom(true));
        runButton.setOnAction(e -> applyCustom());

        exportButton.setOnAction(e -> export());
        backButton.setOnAction(e -> navigator.toAdmin());

        showCustom(false);
        todayChip.setSelected(true);
        applyPreset(Preset.TODAY);
    }

    private void showCustom(boolean show) {
        customRow.setVisible(show);
        customRow.setManaged(show);
    }

    private void applyPreset(Preset preset) {
        showCustom(false);
        LocalDate[] r = vm.resolve(preset);
        from = r[0];
        to = r[1];
        fetch();
    }

    private void applyCustom() {
        if (fromPicker.getValue() == null || toPicker.getValue() == null) {
            return;
        }
        from = fromPicker.getValue();
        to = toPicker.getValue();
        fetch();
    }

    private void fetch() {
        if (from == null || to == null) {
            return;
        }
        LocalDate f = from;
        LocalDate t = to;
        ReportType type = selectedType;
        Object[] holder = new Object[1];
        FxTasks.run(
                () -> holder[0] = fetchFor(type, f, t),
                () -> { if (holder[0] != null) render(type, holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Report fetch failed", err));
    }

    private Object fetchFor(ReportType type, LocalDate f, LocalDate t) {
        return switch (type) {
            case SALES -> vm.sales(f, t);
            case PAYMENTS -> vm.payments(f, t);
            case TAX -> vm.tax(f, t);
            case CASHIERS -> vm.cashiers(f, t);
            case PRODUCTS -> vm.products(f, t, PRODUCTS_LIMIT);
        };
    }

    private void render(ReportType type, Object report) {
        switch (type) {
            case SALES -> renderSales((SalesSummaryReport) report);
            case TAX -> renderTax((TaxSummaryReport) report);
            case PAYMENTS -> renderPayments((PaymentBreakdownReport) report);
            case CASHIERS -> renderCashiers((CashierReport) report);
            case PRODUCTS -> renderProducts((ProductPerformanceReport) report);
        }
    }

    private void renderSales(SalesSummaryReport r) {
        FlowPane tiles = tileGrid();
        tiles.getChildren().addAll(
                tile("Sales", r.saleCount() + ""),
                tile("Gross", money(r.grossSales(), r.currencyCode())),
                tile("Discounts", money(r.lineDiscounts().add(r.txnDiscounts()), r.currencyCode())),
                tile("Tax", money(r.taxTotal(), r.currencyCode())),
                tile("Returns", r.returnCount() + ""),
                tile("Refunds", money(r.refundTotal(), r.currencyCode())),
                tile("Net", money(r.netSales(), r.currencyCode())));
        resultArea.getChildren().setAll(tiles);
    }

    private void renderTax(TaxSummaryReport r) {
        FlowPane tiles = tileGrid();
        tiles.getChildren().addAll(
                tile("Taxable", money(r.taxableAmount(), r.currencyCode())),
                tile("Tax collected", money(r.taxCollected(), r.currencyCode())),
                tile("Refund tax", money(r.refundTax(), r.currencyCode())),
                tile("Net tax", money(r.netTax(), r.currencyCode())));
        resultArea.getChildren().setAll(tiles);
    }

    private void renderPayments(PaymentBreakdownReport r) {
        TableView<PaymentBreakdownReport.PaymentLine> t = new TableView<>();
        addCol(t, "Method", l -> l.method());
        addCol(t, "Count", l -> l.count() + "");
        addCol(t, "Collected", l -> l.collected().toPlainString());
        addCol(t, "Refunded", l -> l.refunded().toPlainString());
        t.setItems(FXCollections.observableArrayList(r.lines()));
        resultArea.getChildren().setAll(t);
    }

    private void renderCashiers(CashierReport r) {
        TableView<CashierReport.CashierLine> t = new TableView<>();
        addCol(t, "Cashier", l -> l.cashierUsername());
        addCol(t, "Sales", l -> l.saleCount() + "");
        addCol(t, "Total", l -> l.totalSales().toPlainString());
        addCol(t, "Discounts", l -> l.totalDiscounts().toPlainString());
        t.setItems(FXCollections.observableArrayList(r.lines()));
        resultArea.getChildren().setAll(t);
    }

    private void renderProducts(ProductPerformanceReport r) {
        TableView<ProductPerformanceReport.ProductLine> t = new TableView<>();
        addCol(t, "SKU", l -> l.sku());
        addCol(t, "Item", l -> l.name());
        addCol(t, "Qty", l -> l.quantitySold().stripTrailingZeros().toPlainString());
        addCol(t, "Revenue", l -> l.revenue().toPlainString());
        addCol(t, "Discounts", l -> l.discounts().toPlainString());
        t.setItems(FXCollections.observableArrayList(r.lines()));
        resultArea.getChildren().setAll(t);
    }

    private void export() {
        if (from == null || to == null) {
            return;
        }
        LocalDate f = from;
        LocalDate t = to;
        ReportType type = selectedType;
        Integer limit = type == ReportType.PRODUCTS ? PRODUCTS_LIMIT : null;
        String[] holder = new String[1];
        FxTasks.run(
                () -> holder[0] = vm.exportCsv(type, f, t, limit),
                () -> { if (holder[0] != null) saveCsv(type, f, t, holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "CSV export failed", err));
    }

    private void saveCsv(ReportType type, LocalDate f, LocalDate t, String csv) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(type.path() + "-" + f + "_" + t + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File file = chooser.showSaveDialog(resultArea.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), csv);
        } catch (java.io.IOException ex) {
            LOG.log(System.Logger.Level.ERROR, "Cannot write CSV", ex);
        }
    }

    private FlowPane tileGrid() {
        FlowPane p = new FlowPane();
        p.setHgap(16);
        p.setVgap(16);
        p.getStyleClass().add("report-tiles");
        return p;
    }

    private VBox tile(String label, String value) {
        VBox v = new VBox(4);
        v.getStyleClass().add("kpi-tile");
        Label val = new Label(value);
        val.getStyleClass().add("kpi-value");
        Label lab = new Label(label);
        lab.getStyleClass().add("kpi-label");
        v.getChildren().addAll(val, lab);
        return v;
    }

    private <S> void addCol(TableView<S> table, String title, java.util.function.Function<S, String> get) {
        TableColumn<S, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleStringProperty(get.apply(c.getValue())));
        table.getColumns().add(col);
    }

    private static String label(ReportType type) {
        return switch (type) {
            case SALES -> "Sales";
            case PAYMENTS -> "Payments";
            case TAX -> "Tax";
            case CASHIERS -> "Cashiers";
            case PRODUCTS -> "Products";
        };
    }

    private static String money(java.math.BigDecimal amount, String currency) {
        return (amount == null ? "0" : amount.toPlainString()) + " " + currency;
    }
}
