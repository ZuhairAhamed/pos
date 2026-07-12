package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ShiftView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.StartShiftViewModel;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    @FXML private Label userLabel;
    @FXML private Label shiftLabel;
    @FXML private Button signOutButton;
    @FXML private Button dineInButton;
    @FXML private Button retailButton;

    public HomeController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.shiftVm = new StartShiftViewModel(services.shiftApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        String user = services.session.username();
        userLabel.setText(user == null ? "" : "Signed in: " + user);
        shiftLabel.setText("");
        dineInButton.setOnAction(e -> navigator.toTableMap());
        retailButton.setOnAction(e -> navigator.toRetail());
        signOutButton.setOnAction(e -> {
            services.session.clear();
            navigator.toLogin();
        });
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
        shiftLabel.setText("Shift open since " + OPENED_AT.format(shift.openedAt()));
    }

    private void showNoShift() {
        shiftLabel.setText("No shift open — cash reports unavailable");
    }
}
