package com.company.pos.terminal.view;

import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.TableCell;
import com.company.pos.terminal.viewmodel.TableMapViewModel;
import java.util.UUID;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.util.Duration;

/**
 * Thin controller for the dining table-map screen. It binds the FXML controls to a
 * {@link TableMapViewModel} (built from {@link Services#diningApi}), renders {@code vm.cells()} as a
 * grid of tappable table tiles, and runs the (synchronous) VM calls off the FX thread via
 * {@link FxTasks} so the UI never blocks on the network. No business logic lives here.
 *
 * <p>Polling lifecycle: on {@link #initialize()} it does one immediate refresh (so the floor paints
 * without waiting an interval), then starts a {@link Timeline} that re-runs {@link #refresh()} every
 * {@code poll.interval.seconds}. The timeline is {@link #stopPolling() stopped} before navigating
 * away on a table tap, so it never polls a dead screen or leaks.
 */
public class TableMapController {

    private static final System.Logger LOG = System.getLogger(TableMapController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final TableMapViewModel vm;
    private Timeline poller;

    @FXML private Label storeLabel;
    @FXML private Label errorLabel;
    @FXML private Button refreshButton;
    @FXML private FlowPane tableFlow;

    public TableMapController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new TableMapViewModel(services.diningApi);
    }

    @FXML
    public void initialize() {
        storeLabel.setText("Store " + services.config.storeId()
                + "  ·  Terminal " + services.config.terminalId());

        // Error banner: text from the VM; hidden (and not laid out) when empty.
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        // Re-render whenever the VM rebuilds its cell list (from a background refresh).
        vm.cells().addListener((ListChangeListener<TableCell>) change -> render());

        refreshButton.setOnAction(e -> refresh());

        // Immediate first paint, then poll on an interval.
        refresh();
        int seconds = Math.max(1, services.config.pollIntervalSeconds());
        poller = new Timeline(new KeyFrame(Duration.seconds(seconds), e -> refresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    /** Run the VM refresh off the FX thread; the cells listener repaints on completion. */
    private void refresh() {
        FxTasks.run(vm::refresh, () -> { }, err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error in refresh", err));
    }

    /** Rebuild the tile grid from the current cells. Runs on the FX thread. */
    private void render() {
        tableFlow.getChildren().clear();
        for (TableCell cell : vm.cells()) {
            tableFlow.getChildren().add(tileFor(cell));
        }
    }

    private Button tileFor(TableCell cell) {
        String status = cell.occupied() ? "In use" : "Open";
        Button tile = new Button(cell.label() + "\n" + status);
        tile.getStyleClass().addAll("table-cell", cell.occupied() ? "table-occupied" : "table-free");
        tile.setWrapText(true);
        tile.setOnAction(e -> open(cell));
        return tile;
    }

    /** Open or resume the order for the tapped table, then hand off to the order screen. */
    private void open(TableCell cell) {
        UUID[] holder = new UUID[1];
        FxTasks.run(
                () -> holder[0] = vm.openOrResume(cell),
                () -> {
                    if (holder[0] != null) {
                        stopPolling();
                        navigator.toOrder(holder[0]);
                    }
                    // null id → VM already surfaced the error via errorMessage; stay on the map.
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error in open", err));
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
        }
    }
}
