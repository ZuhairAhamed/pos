package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.SettingView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.SettingsCatalog;
import com.company.pos.terminal.viewmodel.SettingsCatalog.Group;
import com.company.pos.terminal.viewmodel.SettingsCatalog.Row;
import com.company.pos.terminal.viewmodel.SettingsViewModel;
import java.util.List;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * ADMIN store-settings screen. Loads the typed settings off the FX thread via FxTasks, then builds
 * the grouped form programmatically (mixed editor types per setting). Save validates client-side,
 * confirms live-critical edits, then PUTs via the VM; each save's onDone re-kicks reload().
 */
public class SettingsController {

    private static final System.Logger LOG = System.getLogger(SettingsController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final SettingsViewModel vm;

    @FXML private Label errorLabel;
    @FXML private Button backButton;
    @FXML private VBox settingsBox;

    public SettingsController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new SettingsViewModel(services.configApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        backButton.setOnAction(e -> navigator.toAdmin());
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        final List<SettingView>[] h = new List[1];
        FxTasks.run(
                () -> h[0] = vm.loadSettings(),
                () -> { if (h[0] != null) buildSections(h[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Load settings failed", err));
    }

    private void buildSections(List<SettingView> settings) {
        settingsBox.getChildren().clear();
        for (Group group : SettingsCatalog.group(settings)) {
            Label header = new Label(group.category());
            header.getStyleClass().add("field-label");
            settingsBox.getChildren().add(header);
            for (Row row : group.rows()) {
                settingsBox.getChildren().add(buildRow(row));
            }
        }
    }

    private Node buildRow(Row row) {
        SettingView v = row.view();
        Label label = new Label(row.label());
        label.setMinWidth(280);

        Control editor;
        Supplier<String> read;
        if ("BOOLEAN".equals(v.type())) {
            CheckBox cb = new CheckBox();
            cb.setSelected(Boolean.parseBoolean(v.value()));
            editor = cb;
            read = () -> cb.isSelected() ? "true" : "false";
        } else {
            TextField tf = new TextField(v.value());
            editor = tf;
            read = tf::getText;
        }
        HBox.setHgrow(editor, Priority.ALWAYS);

        Button save = new Button("Save");
        save.getStyleClass().add("btn-secondary");
        save.setOnAction(e -> saveSetting(v.name(), v.type(), row.label(), row.liveCritical(), read.get()));

        HBox box = new HBox(12, label, editor, save);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void saveSetting(String name, String type, String label, boolean liveCritical, String value) {
        if (liveCritical && !confirmLiveCritical(label)) {
            return;
        }
        final boolean[] holder = {false};
        FxTasks.run(
                () -> holder[0] = vm.update(name, value, type),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Save setting failed", err));
    }

    private boolean confirmLiveCritical(String label) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Changing \"" + label + "\" affects how orders are priced. Apply now?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Live setting change");
        alert.getDialogPane().getStyleClass().add("drawer-modal");
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }
}
