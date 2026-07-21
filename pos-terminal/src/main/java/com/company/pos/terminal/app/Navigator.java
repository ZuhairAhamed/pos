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

    // Reached from the order screen's "Split bill" action. Builds the split screen; Cancel
    // returns to the order, Done (after a successful close) returns to the table map.
    public void toSplit(UUID orderId) {
        com.company.pos.terminal.view.SplitController controller =
                new com.company.pos.terminal.view.SplitController(services, this, orderId);
        setScene("/fxml/split.fxml", controller);
    }

    // Reached from the order screen's "Pay" action with the client-side estimated
    // subtotal (pre-tax, pre-service-charge). Builds the payment screen for dine-in;
    // the VM closes the order server-side and exposes the authoritative SaleView totals.
    public void toPayment(UUID orderId, BigDecimal estimatedTotal) {
        com.company.pos.terminal.view.PaymentController controller =
                new com.company.pos.terminal.view.PaymentController(services, this,
                        com.company.pos.terminal.view.PaymentController.Mode.DINE_IN, orderId, estimatedTotal);
        setScene("/fxml/payment.fxml", controller);
    }

    public void toRetailPayment(UUID cartId, BigDecimal estimatedTotal) {
        com.company.pos.terminal.view.PaymentController controller =
                new com.company.pos.terminal.view.PaymentController(services, this,
                        com.company.pos.terminal.view.PaymentController.Mode.RETAIL, cartId, estimatedTotal);
        setScene("/fxml/payment.fxml", controller);
    }

    public void toHome() {
        com.company.pos.terminal.view.HomeController controller =
                new com.company.pos.terminal.view.HomeController(services, this);
        setScene("/fxml/home.fxml", controller);
    }

    public void toAdmin() {
        com.company.pos.terminal.view.AdminController controller =
                new com.company.pos.terminal.view.AdminController(services, this);
        setScene("/fxml/admin.fxml", controller);
    }

    public void toStaff() {
        com.company.pos.terminal.view.StaffController controller =
                new com.company.pos.terminal.view.StaffController(services, this);
        setScene("/fxml/staff.fxml", controller);
    }

    public void toProducts() {
        com.company.pos.terminal.view.ProductsController controller =
                new com.company.pos.terminal.view.ProductsController(services, this);
        setScene("/fxml/products.fxml", controller);
    }

    public void toKitchenRouting() {
        com.company.pos.terminal.view.KitchenRoutingController controller =
                new com.company.pos.terminal.view.KitchenRoutingController(services, this);
        setScene("/fxml/kitchen-routing.fxml", controller);
    }

    public void toTables() {
        com.company.pos.terminal.view.TablesController controller =
                new com.company.pos.terminal.view.TablesController(services, this);
        setScene("/fxml/tables.fxml", controller);
    }

    public void toSettings() {
        com.company.pos.terminal.view.SettingsController controller =
                new com.company.pos.terminal.view.SettingsController(services, this);
        setScene("/fxml/settings.fxml", controller);
    }

    public void toRetail() {
        com.company.pos.terminal.view.RetailController controller =
                new com.company.pos.terminal.view.RetailController(services, this);
        setScene("/fxml/retail.fxml", controller);
    }

    // The controller currently attached to the stage, tracked so its timers/
    // resources can be released via Screen#onLeave before we swap in the next
    // screen. Any controller that owns a repeating task (e.g. a polling Timeline)
    // implements Screen so it is torn down on EVERY navigation-away, not just the
    // one happy path that thought to call stop itself.
    private Object current;

    void setScene(String fxml, Object controller) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
            loader.setController(controller);
            Parent root = loader.load();
            if (current instanceof Screen s) {
                s.onLeave();
            }
            Scene scene = stage.getScene();
            if (scene == null) {
                scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);
                scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
                stage.setScene(scene);
            } else {
                scene.setRoot(root);
            }
            current = controller;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load " + fxml, e);
        }
    }

    /**
     * Lifecycle hook for a screen controller that owns resources needing release
     * when the user navigates away (polling timers, subscriptions, …). The
     * {@link Navigator} calls {@link #onLeave()} on the outgoing controller
     * before swapping in the next screen, so cleanup runs on every exit path.
     */
    public interface Screen {
        default void onLeave() {}
    }
}
