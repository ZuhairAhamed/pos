package com.company.pos.terminal.view;

import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.TableCell;
import com.company.pos.terminal.viewmodel.TableMapViewModel;
import com.company.pos.terminal.viewmodel.TakeawayRow;
import java.time.Instant;
import java.util.UUID;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Thin controller for the dining screen. Binds FXML to a {@link TableMapViewModel}, renders
 * {@code vm.cells()} as a dine-in tile grid and {@code vm.takeawayOrders()} as a takeaway list,
 * and runs the (synchronous) VM calls off the FX thread via {@link FxTasks}. A segmented control
 * swaps which region is visible; polling repaints both. No business logic lives here.
 */
public class TableMapController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(TableMapController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final TableMapViewModel vm;
    private final ToggleGroup segmentGroup = new ToggleGroup();
    private Timeline poller;

    @FXML private Label storeLabel;
    @FXML private Label errorLabel;
    @FXML private Button refreshButton;
    @FXML private ToggleButton tablesSegment;
    @FXML private ToggleButton takeawaySegment;
    @FXML private ScrollPane tablesPane;
    @FXML private ScrollPane takeawayPane;
    @FXML private Button newTakeawayButton;
    @FXML private FlowPane tableFlow;
    @FXML private VBox takeawayList;

    public TableMapController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new TableMapViewModel(services.diningApi, Platform::runLater,
                services.config.takeawayLabelPrefix(), services.config.dwellAttention(),
                Instant::now);
    }

    @FXML
    public void initialize() {
        storeLabel.setText("Store " + services.config.storeId()
                + "  ·  Terminal " + services.config.terminalId());

        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        vm.cells().addListener((ListChangeListener<TableCell>) c -> renderTables());
        vm.takeawayOrders().addListener((ListChangeListener<TakeawayRow>) c -> renderTakeaway());

        refreshButton.setOnAction(e -> refresh());
        newTakeawayButton.setOnAction(e -> newTakeaway());

        // Segmented control: keep one segment always selected; swap region visibility.
        tablesSegment.setToggleGroup(segmentGroup);
        takeawaySegment.setToggleGroup(segmentGroup);
        segmentGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);          // no deselect-to-empty
                return;
            }
            showSegment();
        });
        showSegment();

        refresh();
        int seconds = Math.max(1, services.config.pollIntervalSeconds());
        poller = new Timeline(new KeyFrame(Duration.seconds(seconds), e -> refresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
        if (services.config.realtimeEnabled()) {
            services.realtimeClient.connect(this::refresh);
        }
    }

    /** Show the region for the selected segment; the other is hidden and unmanaged. */
    private void showSegment() {
        boolean tables = tablesSegment.isSelected();
        tablesPane.setVisible(tables);
        tablesPane.setManaged(tables);
        takeawayPane.setVisible(!tables);
        takeawayPane.setManaged(!tables);
    }

    private void refresh() {
        FxTasks.run(vm::refresh, () -> { },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error in refresh", err));
    }

    private void renderTables() {
        tableFlow.getChildren().clear();
        for (TableCell cell : vm.cells()) {
            tableFlow.getChildren().add(tileFor(cell));
        }
    }

    private Button tileFor(TableCell cell) {
        Button tile = new Button(captionFor(cell));
        tile.getStyleClass().addAll("table-cell", stateClass(cell.state()));
        if (cell.attention()) {
            tile.getStyleClass().add("attention-badge");
        }
        tile.setWrapText(true);
        tile.setOnAction(e -> open(cell));
        return tile;
    }

    private String captionFor(TableCell cell) {
        String att = cell.attention() ? " ⏰" : "";
        return switch (cell.state()) {
            case FREE -> cell.label() + "\nOpen";
            case SEATED -> cell.label() + "\nSeated · " + cell.openMinutes() + "m" + att;
            case ACTIVE -> cell.label() + "\nIn use · " + cell.openMinutes() + "m" + att;
        };
    }

    private String stateClass(TableCell.TableState state) {
        return switch (state) {
            case FREE -> "table-free";
            case SEATED -> "table-seated";
            case ACTIVE -> "table-active";
        };
    }

    private void renderTakeaway() {
        takeawayList.getChildren().clear();
        for (TakeawayRow row : vm.takeawayOrders()) {
            Button r = new Button(takeawayCaption(row));
            r.getStyleClass().add("takeaway-row");
            r.setMaxWidth(Double.MAX_VALUE);
            r.setWrapText(true);
            r.setOnAction(e -> resume(row.orderId()));
            takeawayList.getChildren().add(r);
        }
    }

    private String takeawayCaption(TakeawayRow row) {
        String att = row.attention() ? " ⏰" : "";
        return row.label() + "  ·  " + row.lineCount() + " items  ·  " + row.openMinutes() + "m" + att;
    }

    /** Open or resume the order for the tapped dine-in table, then hand off to the order screen. */
    private void open(TableCell cell) {
        UUID[] holder = new UUID[1];
        FxTasks.run(
                () -> holder[0] = vm.openOrResume(cell),
                () -> { if (holder[0] != null) navigator.toOrder(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error in open", err));
    }

    /** Open a new takeaway order against a free counter, then hand off to the order screen. */
    private void newTakeaway() {
        UUID[] holder = new UUID[1];
        FxTasks.run(
                () -> holder[0] = vm.openTakeaway(),
                () -> { if (holder[0] != null) navigator.toOrder(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error in new takeaway", err));
    }

    private void resume(UUID orderId) {
        navigator.toOrder(orderId);
    }

    @Override
    public void onLeave() {
        stopPolling();
        services.realtimeClient.close();
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
        }
    }
}
