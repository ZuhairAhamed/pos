package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ReturnLineRequest;
import com.company.pos.terminal.api.dto.ReturnPaymentView;
import com.company.pos.terminal.api.dto.ReturnView;
import com.company.pos.terminal.api.dto.SaleLineView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.ReturnsViewModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.VBox;
import javafx.util.converter.BigDecimalStringConverter;

/** Returns screen: receipt lookup -> per-line return-qty -> manager PIN -> refund result. */
public class ReturnsController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(ReturnsController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final ReturnsViewModel vm;

    private SaleView currentSale;
    private String currencyCode = "";

    @FXML private Label errorLabel;
    @FXML private Button backButton;
    @FXML private TextField receiptField;
    @FXML private Button findButton;
    @FXML private VBox stagePane;
    @FXML private Label saleHeaderLabel;
    @FXML private TableView<Row> linesTable;
    @FXML private TableColumn<Row, String> colName;
    @FXML private TableColumn<Row, String> colSku;
    @FXML private TableColumn<Row, String> colSold;
    @FXML private TableColumn<Row, String> colLineTotal;
    @FXML private TableColumn<Row, BigDecimal> colReturnQty;
    @FXML private Button returnAllButton;
    @FXML private Label estimateLabel;
    @FXML private Button processButton;
    @FXML private VBox resultPane;
    @FXML private Label creditNoteLabel;
    @FXML private Label refundTotalLabel;
    @FXML private TableView<ReturnPaymentView> refundsTable;
    @FXML private TableColumn<ReturnPaymentView, String> colRefundMethod;
    @FXML private TableColumn<ReturnPaymentView, String> colRefundAmount;
    @FXML private Button newReturnButton;
    @FXML private Button doneButton;

    public ReturnsController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new ReturnsViewModel(services.salesApi, services.returnApi, services.authApi,
                Platform::runLater);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().line.name()));
        colSku.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().line.sku()));
        colSold.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().line.quantity().stripTrailingZeros().toPlainString()));
        colLineTotal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().line.lineTotal().toPlainString() + " " + currencyCode));
        colReturnQty.setCellValueFactory(c -> c.getValue().returnQty);
        colReturnQty.setCellFactory(TextFieldTableCell.forTableColumn(new BigDecimalStringConverter()));
        colReturnQty.setOnEditCommit(e -> {
            Row row = e.getRowValue();
            row.setClamped(e.getNewValue());
            recomputeEstimate();
            linesTable.refresh();
        });
        linesTable.setEditable(true);

        colRefundMethod.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().method()));
        colRefundAmount.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().amount().toPlainString() + " " + currencyCode));

        findButton.setOnAction(e -> find());
        receiptField.setOnAction(e -> find());
        returnAllButton.setOnAction(e -> {
            linesTable.getItems().forEach(r -> r.returnQty.set(r.line.quantity()));
            recomputeEstimate();
            linesTable.refresh();
        });
        processButton.setOnAction(e -> process());
        newReturnButton.setOnAction(e -> reset());
        doneButton.setOnAction(e -> navigator.toHome());
        backButton.setOnAction(e -> navigator.toHome());
    }

    private void find() {
        String receipt = receiptField.getText();
        if (receipt == null || receipt.isBlank()) {
            return;
        }
        SaleView[] holder = new SaleView[1];
        FxTasks.run(
                () -> holder[0] = vm.lookup(receipt),
                () -> { if (holder[0] != null) showSale(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Sale lookup failed", err));
    }

    private void showSale(SaleView sale) {
        currentSale = sale;
        currencyCode = sale.currencyCode();
        saleHeaderLabel.setText("Receipt " + sale.receiptNumber() + "  •  "
                + sale.grandTotal().toPlainString() + " " + currencyCode);
        List<Row> rows = new ArrayList<>();
        for (SaleLineView l : sale.lines()) {
            rows.add(new Row(l));
        }
        linesTable.setItems(FXCollections.observableArrayList(rows));
        recomputeEstimate();
        resultPane.setVisible(false);
        resultPane.setManaged(false);
        stagePane.setVisible(true);
        stagePane.setManaged(true);
    }

    private void recomputeEstimate() {
        BigDecimal est = BigDecimal.ZERO;
        for (Row r : linesTable.getItems()) {
            BigDecimal qty = r.returnQty.get();
            if (qty != null && qty.signum() > 0 && r.line.quantity().signum() > 0) {
                est = est.add(r.line.lineTotal().multiply(qty)
                        .divide(r.line.quantity(), 2, RoundingMode.HALF_UP));
            }
        }
        estimateLabel.setText("≈ " + est.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " " + currencyCode);
    }

    private void process() {
        if (currentSale == null) {
            return;
        }
        List<ReturnLineRequest> lines = new ArrayList<>();
        for (Row r : linesTable.getItems()) {
            BigDecimal qty = r.returnQty.get();
            if (qty != null && qty.signum() > 0) {
                lines.add(new ReturnLineRequest(r.line.lineNo(), qty));
            }
        }
        if (lines.isEmpty()) {
            estimateLabel.setText("Select at least one line to return");
            return;
        }
        Optional<ManagerPinDialog.Credentials> creds =
                ManagerPinDialog.promptForApproval("Manager approval required to process this return");
        if (creds.isEmpty()) {
            return;
        }
        UUID saleId = currentSale.id();
        String code = creds.get().cashierCode();
        String pin = creds.get().pin();
        ReturnView[] holder = new ReturnView[1];
        FxTasks.run(
                () -> holder[0] = vm.process(saleId, lines, code, pin),
                () -> { if (holder[0] != null) showResult(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Return processing failed", err));
    }

    private void showResult(ReturnView view) {
        creditNoteLabel.setText("Credit note " + view.creditNoteNumber());
        refundTotalLabel.setText("Refunded " + view.refundGrandTotal().toPlainString() + " "
                + view.currencyCode());
        currencyCode = view.currencyCode();
        refundsTable.setItems(FXCollections.observableArrayList(view.refunds()));
        stagePane.setVisible(false);
        stagePane.setManaged(false);
        resultPane.setVisible(true);
        resultPane.setManaged(true);
    }

    private void reset() {
        currentSale = null;
        receiptField.clear();
        linesTable.setItems(FXCollections.observableArrayList());
        resultPane.setVisible(false);
        resultPane.setManaged(false);
        stagePane.setVisible(false);
        stagePane.setManaged(false);
    }

    /** Table row model: the sold line + an editable, clamped return quantity. */
    private static final class Row {
        private final SaleLineView line;
        private final SimpleObjectProperty<BigDecimal> returnQty = new SimpleObjectProperty<>(BigDecimal.ZERO);

        Row(SaleLineView line) {
            this.line = line;
        }

        void setClamped(BigDecimal value) {
            BigDecimal v = value == null ? BigDecimal.ZERO : value;
            if (v.signum() < 0) {
                v = BigDecimal.ZERO;
            }
            if (v.compareTo(line.quantity()) > 0) {
                v = line.quantity();
            }
            returnQty.set(v);
        }
    }
}
