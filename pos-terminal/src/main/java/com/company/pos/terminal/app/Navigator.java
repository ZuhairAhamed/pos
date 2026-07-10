package com.company.pos.terminal.app;

import com.company.pos.terminal.view.LoginController;
import com.company.pos.terminal.view.TableMapController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Screen switcher over a single {@link Stage}. Each {@code to*} method builds
 * the target screen's controller (constructor-injected with {@link Services}
 * and this navigator), loads its FXML, and swaps the scene root — the shared
 * {@code app.css} stylesheet is attached to the {@link Scene} once, so every
 * screen inherits the same design tokens.
 */
public class Navigator {
    private static final int SCENE_WIDTH = 1280;
    private static final int SCENE_HEIGHT = 800;

    private final Stage stage;
    private final Services services;

    public Navigator(Stage stage, Services services) {
        this.stage = stage;
        this.services = services;
    }

    public void toLogin() {
        LoginController controller = new LoginController(services, this);
        setScene("/fxml/login.fxml", controller);
    }

    public void toTableMap() {
        TableMapController controller = new TableMapController(services, this);
        setScene("/fxml/table-map.fxml", controller);
    }

    // Reached from the table map on a table tap (TableMapController.open); the map
    // stops its poller before calling this. Builds the order screen for the given order.
    public void toOrder(UUID orderId) {
        com.company.pos.terminal.view.OrderController controller =
                new com.company.pos.terminal.view.OrderController(services, this, orderId);
        setScene("/fxml/order.fxml", controller);
    }

    // TODO(Task 14): navigate to the payment screen for the given order with the
    // client-side estimated subtotal. Left throwing until the payment screen exists.
    public void toPayment(UUID orderId, BigDecimal estimatedTotal) {
        throw new UnsupportedOperationException("toPayment is wired in Task 14");
    }

    void setScene(String fxml, Object controller) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
            loader.setController(controller);
            Parent root = loader.load();
            Scene scene = stage.getScene();
            if (scene == null) {
                scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);
                scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
                stage.setScene(scene);
            } else {
                scene.setRoot(root);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load " + fxml, e);
        }
    }
}
