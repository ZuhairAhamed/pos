package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.CashMovementView;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import com.company.pos.terminal.api.dto.ShiftSummary;
import com.company.pos.terminal.api.dto.ShiftView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.view.CashMovementDialog.CashMovementInput;
import com.company.pos.terminal.view.DrawerActivityDialog.DrawerAction;
import com.company.pos.terminal.viewmodel.CashDrawerViewModel;
import com.company.pos.terminal.viewmodel.CloseShiftViewModel;
import com.company.pos.terminal.viewmodel.StartShiftViewModel;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * Thin controller for the post-login mode picker. Two tiles route to the dine-in table map or a
 * fresh retail sale. Shows the signed-in user and a sign-out affordance. On arrival it checks the
 * terminal's shift: an open shift shows as a status line; none prompts the once-a-day Start Shift
 * modal (skippable — the dismissal is session-scoped so the prompt doesn't re-nag on every return
 * to Home). No other business logic.
 */
public class HomeController {

    private static final System.Logger LOG = System.getLogger(HomeController.class.getName());
    private static final DateTimeFormatter OPENED_AT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final Services services;
    private final Navigator navigator;
    private final StartShiftViewModel shiftVm;
    private final CloseShiftViewModel closeVm;
    private final CashDrawerViewModel drawerVm;
    private ShiftView openShift;

    @FXML private Label userLabel;
    @FXML private Label shiftLabel;
    @FXML private Button signOutButton;
    @FXML private Button adminButton;
    @FXML private Button dineInButton;
    @FXML private Button retailButton;
    @FXML private Button closeShiftButton;
    @FXML private Button drawerButton;
    @FXML private Button kitchenButton;
    @FXML private Button dashboardButton;
    @FXML private Button returnsButton;
    @FXML private Button availabilityButton;

    public HomeController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.shiftVm = new StartShiftViewModel(services.shiftApi, Platform::runLater);
        this.closeVm = new CloseShiftViewModel(services.shiftApi, Platform::runLater);
        this.drawerVm = new CashDrawerViewModel(services.cashDrawerApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        String user = services.session.username();
        userLabel.setText(user == null ? "" : "Signed in: " + user);
        shiftLabel.setText("");
        dineInButton.setOnAction(e -> navigator.toTableMap());
        retailButton.setOnAction(e -> navigator.toRetail());
        kitchenButton.setOnAction(e -> navigator.toKitchen());
        signOutButton.setOnAction(e -> {
            services.session.clear();
            navigator.toLogin();
        });
        closeShiftButton.setOnAction(e -> closeShift());
        closeShiftButton.setVisible(false);
        closeShiftButton.setManaged(false);
        drawerButton.setOnAction(e -> openDrawer());
        drawerButton.setVisible(false);
        drawerButton.setManaged(false);
        boolean showAdmin = services.session.isManager();   // MANAGER or ADMIN
        adminButton.setVisible(showAdmin);
        adminButton.setManaged(showAdmin);
        adminButton.setOnAction(e -> navigator.toAdmin());
        boolean showDashboard = services.session.isManager();
        dashboardButton.setVisible(showDashboard);
        dashboardButton.setManaged(showDashboard);
        dashboardButton.setOnAction(e -> navigator.toDashboard());
        returnsButton.setOnAction(e -> navigator.toReturns());
        availabilityButton.setOnAction(e -> navigator.toAvailability());
        checkShift();
    }

    /** First arrival of the day: if the terminal has no open shift, prompt for a cash float. */
    private void checkShift() {
        final ShiftView[] holder = new ShiftView[1];
        FxTasks.run(() -> holder[0] = services.shiftApi.findOpenShift(),
                () -> onShiftChecked(holder[0]),
                err -> {
                    shiftLabel.setText("Shift status unavailable");
                    LOG.log(System.Logger.Level.ERROR, "Open-shift check failed", err);
                });
    }

    private void onShiftChecked(ShiftView open) {
        if (open != null) {
            showShift(open);
            return;
        }
        if (services.session.shiftPromptDismissed()) {
            showNoShift();
            return;
        }
        StartShiftDialog.promptForFloat(services.config.terminalId(), services.session.username())
                .ifPresentOrElse(this::openShift, () -> {
                    services.session.dismissShiftPrompt();
                    showNoShift();
                });
    }

    private void openShift(BigDecimal openingFloat) {
        FxTasks.run(() -> shiftVm.openShift(openingFloat),
                () -> {
                    ShiftView opened = shiftVm.shift().get();
                    if (opened != null) {
                        showShift(opened);
                    } else {
                        shiftLabel.setText(shiftVm.errorMessage().get());
                    }
                },
                err -> {
                    shiftLabel.setText("Couldn't start the shift");
                    LOG.log(System.Logger.Level.ERROR, "Open shift failed", err);
                });
    }

    private void showShift(ShiftView shift) {
        this.openShift = shift;
        shiftLabel.setText("Shift open since " + OPENED_AT.format(shift.openedAt()));
        closeShiftButton.setVisible(true);
        closeShiftButton.setManaged(true);
        drawerButton.setVisible(true);
        drawerButton.setManaged(true);
    }

    private void showNoShift() {
        this.openShift = null;
        shiftLabel.setText("No shift open — cash reports unavailable");
        closeShiftButton.setVisible(false);
        closeShiftButton.setManaged(false);
        drawerButton.setVisible(false);
        drawerButton.setManaged(false);
    }

    /** Drawer peek: fetch activity → show blind-safe view → optionally record a movement → loop. */
    private void openDrawer() {
        if (openShift == null) {
            return;
        }
        final DrawerReconciliation[] holder = new DrawerReconciliation[1];
        FxTasks.run(
                () -> holder[0] = drawerVm.loadActivity(),
                () -> {
                    if (holder[0] == null) {
                        shiftLabel.setText(drawerVm.errorMessage().get());
                        return;
                    }
                    DrawerActivityDialog.promptForAction(holder[0], services.config.terminalId())
                            .flatMap(action -> CashMovementDialog
                                    .prompt(action, services.config.terminalId())
                                    .map(input -> Map.entry(action, input)))
                            .ifPresent(e -> submitMovement(e.getKey(), e.getValue()));
                },
                err -> {
                    shiftLabel.setText("Drawer unavailable");
                    LOG.log(System.Logger.Level.ERROR, "Load drawer activity failed", err);
                });
    }

    private void submitMovement(DrawerAction action, CashMovementInput input) {
        final CashMovementView[] holder = new CashMovementView[1];
        FxTasks.run(
                () -> holder[0] = action == DrawerAction.PAY_IN
                        ? drawerVm.payIn(input.amount(), input.reason())
                        : drawerVm.payOut(input.amount(), input.reason()),
                () -> {
                    if (holder[0] != null) {
                        openDrawer();   // re-fetch → show refreshed activity → allow another
                    } else {
                        shiftLabel.setText(drawerVm.errorMessage().get());
                    }
                },
                err -> {
                    shiftLabel.setText("Couldn't record the movement");
                    LOG.log(System.Logger.Level.ERROR, "Cash movement failed", err);
                });
    }

    /** Close the shift: blind count → close → reconciliation result → no-shift state. */
    private void closeShift() {
        if (openShift == null) {
            return;
        }
        Optional<BigDecimal> counted = CloseShiftDialog.promptForCount(services.config.terminalId());
        if (counted.isEmpty()) {
            return;
        }
        UUID id = openShift.shiftId();
        boolean[] holder = {false};
        FxTasks.run(
                () -> holder[0] = closeVm.closeShift(id, counted.get()),
                () -> {
                    if (holder[0]) {
                        ShiftSummary s = closeVm.summary().get();
                        if (s != null && s.cash() != null) {
                            ShiftResultDialog.show(s.cash());
                        }
                        showNoShift();
                    } else {
                        shiftLabel.setText(closeVm.errorMessage().get());
                    }
                },
                err -> {
                    shiftLabel.setText("Couldn't close the shift");
                    LOG.log(System.Logger.Level.ERROR, "Close shift failed", err);
                });
    }
}
