package com.company.pos.terminal.view;

import com.company.pos.terminal.api.RealtimeClient;
import com.company.pos.terminal.api.dto.KitchenTicketLineView;
import com.company.pos.terminal.api.dto.KitchenTicketView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.KitchenDisplayViewModel;
import com.company.pos.terminal.viewmodel.KitchenDisplayViewModel.Aging;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Full-screen kitchen ticket board: one column per station, FIFO cards, tap-to-advance. Owns its
 * own realtime client (KITCHEN-topic → re-fetch) and a poll fallback. FX-threading: VM runs off
 * the FX thread via FxTasks; advance/recall chain a fresh refresh in the SAME work lambda (never
 * HTTP in onDone).
 */
public class KitchenDisplayController implements Navigator.Screen {

    private static final System.Logger LOG =
            System.getLogger(KitchenDisplayController.class.getName());
    private static final int POLL_SECONDS = 10;

    private final Services services;
    private final Navigator navigator;
    private final KitchenDisplayViewModel vm;
    private Timeline poller;
    private RealtimeClient realtime;

    @FXML private Label errorLabel;
    @FXML private Button refreshButton;
    @FXML private Button backButton;
    @FXML private HBox stationLanes;

    public KitchenDisplayController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new KitchenDisplayViewModel(services.kitchenTicketApi, Platform::runLater,
                java.time.Instant::now, java.time.Duration.ofSeconds(300),
                java.time.Duration.ofSeconds(600));
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        vm.tickets().addListener((ListChangeListener<KitchenTicketView>) c -> render());
        refreshButton.setOnAction(e -> refresh());
        backButton.setOnAction(e -> navigator.toHome());

        refresh();
        poller = new Timeline(new KeyFrame(Duration.seconds(POLL_SECONDS), e -> refresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
        realtime = services.newRealtimeClient();
        realtime.connect((java.util.function.Consumer<String>) topic -> {
            if (topic == null || "KITCHEN".equals(topic)) {
                refresh();
            }
        });
    }

    private void refresh() {
        FxTasks.run(vm::refresh, () -> { },
                err -> LOG.log(System.Logger.Level.ERROR, "KDS refresh failed", err));
    }

    private void render() {
        stationLanes.getChildren().clear();
        Map<String, VBox> lanes = new LinkedHashMap<>();
        for (KitchenTicketView t : vm.tickets()) {
            VBox lane = lanes.computeIfAbsent(t.station(), this::newLane);
            lane.getChildren().add(cardFor(t));
        }
        stationLanes.getChildren().addAll(lanes.values());
    }

    private VBox newLane(String station) {
        VBox lane = new VBox(8);
        lane.getStyleClass().add("kds-lane");
        Label header = new Label(station);
        header.getStyleClass().add("kds-lane-header");
        lane.getChildren().add(header);
        return lane;
    }

    private VBox cardFor(KitchenTicketView t) {
        VBox card = new VBox(4);
        card.getStyleClass().addAll("kds-card", agingClass(vm.agingOf(t)));
        Label head = new Label("Table " + t.tableLabel() + "  ·  " + t.state());
        head.getStyleClass().add("kds-card-head");
        card.getChildren().add(head);
        for (KitchenTicketLineView line : t.lines()) {
            Label item = new Label(line.qty().stripTrailingZeros().toPlainString() + " × " + line.name());
            item.getStyleClass().add("kds-card-item");
            card.getChildren().add(item);
            for (String mod : line.modifiers()) {
                Label m = new Label("   + " + mod);
                m.getStyleClass().add("kds-card-mod");
                card.getChildren().add(m);
            }
            if (line.note() != null && !line.note().isBlank()) {
                Label note = new Label("   note: " + line.note());
                note.getStyleClass().add("kds-card-note");
                card.getChildren().add(note);
            }
        }
        HBox actions = new HBox(8);
        Button advance = new Button(advanceLabel(t.state()));
        advance.getStyleClass().add("btn-primary");
        advance.setOnAction(e -> transition(true, t.id(), t.state()));
        actions.getChildren().add(advance);
        if (!"FIRED".equals(t.state())) {
            Button recall = new Button("Recall");
            recall.getStyleClass().add("btn-secondary");
            recall.setOnAction(e -> transition(false, t.id(), t.state()));
            actions.getChildren().add(recall);
        }
        card.getChildren().add(actions);
        return card;
    }

    /** advance/recall then re-fetch in ONE work lambda; render in onDone (no HTTP in onDone). */
    private void transition(boolean advance, UUID id, String currentState) {
        FxTasks.run(
                () -> {
                    if (advance) {
                        vm.advance(id, currentState);
                    } else {
                        vm.recall(id, currentState);
                    }
                    vm.refresh();
                },
                () -> { },
                err -> LOG.log(System.Logger.Level.ERROR, "KDS transition failed", err));
    }

    private static String advanceLabel(String state) {
        return switch (state) {
            case "FIRED" -> "Start";
            case "PREPARING" -> "Ready";
            case "READY" -> "Bump";
            default -> "Advance";
        };
    }

    private static String agingClass(Aging aging) {
        return switch (aging) {
            case GREEN -> "kds-age-green";
            case AMBER -> "kds-age-amber";
            case RED -> "kds-age-red";
        };
    }

    @Override
    public void onLeave() {
        if (poller != null) {
            poller.stop();
        }
        if (realtime != null) {
            realtime.close();
        }
    }
}
