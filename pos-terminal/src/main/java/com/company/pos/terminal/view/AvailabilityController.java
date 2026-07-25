package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.AvailabilityViewModel;
import java.util.List;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The "86 board": a searchable list of active products; each row shows an Available/86'D pill and a
 * toggle. Pure I/O-free view logic here — all HTTP goes through {@link AvailabilityViewModel} off
 * the FX thread via {@link FxTasks}, results applied in onDone via a holder array.
 */
public class AvailabilityController {

    private static final System.Logger LOG = System.getLogger(AvailabilityController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final AvailabilityViewModel vm;
    private List<ProductView> all = List.of();

    @FXML private TextField searchField;
    @FXML private VBox listBox;
    @FXML private Label statusLabel;
    @FXML private Button backButton;

    public AvailabilityController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new AvailabilityViewModel(services.availabilityApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        statusLabel.setText("");
        backButton.setOnAction(e -> navigator.toHome());
        searchField.textProperty().addListener((o, a, b) -> renderRows());
        reload();
    }

    private void reload() {
        final List<ProductView>[] holder = new List[1];
        FxTasks.run(() -> holder[0] = vm.load(),
                () -> {
                    if (holder[0] == null) {
                        statusLabel.setText(vm.errorMessage().get());
                        return;
                    }
                    all = holder[0];
                    renderRows();
                },
                err -> {
                    statusLabel.setText("Couldn't load products");
                    LOG.log(System.Logger.Level.ERROR, "Load availability failed", err);
                });
    }

    private void renderRows() {
        listBox.getChildren().clear();
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        for (ProductView p : all) {
            if (!q.isEmpty() && !p.name().toLowerCase().contains(q) && !p.sku().toLowerCase().contains(q)) {
                continue;
            }
            listBox.getChildren().add(row(p));
        }
    }

    private HBox row(ProductView p) {
        boolean available = p.available() == null || p.available();
        Label name = new Label(p.name());
        name.getStyleClass().add("subtitle");
        Label pill = new Label(available ? "Available" : "86'D");
        pill.getStyleClass().add(available ? "pill-available" : "pill-eightysix");
        Button toggle = new Button(available ? "86 it" : "Restore");
        toggle.getStyleClass().add("btn-secondary");
        toggle.setOnAction(e -> toggle(p, !available));
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, name, spacer, pill, toggle);
        row.getStyleClass().add("cart-line");
        return row;
    }

    private void toggle(ProductView p, boolean makeAvailable) {
        final ProductView[] holder = new ProductView[1];
        FxTasks.run(() -> holder[0] = vm.setAvailability(p.sku(), makeAvailable),
                () -> {
                    if (holder[0] == null) {
                        statusLabel.setText(vm.errorMessage().get());
                        return;
                    }
                    statusLabel.setText("");
                    reload();
                },
                err -> {
                    statusLabel.setText("Couldn't update " + p.sku());
                    LOG.log(System.Logger.Level.ERROR, "Toggle availability failed", err);
                });
    }
}
