package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
import java.util.function.Consumer;
import javafx.beans.property.*;

/**
 * ViewModel for the login screen. Holds all login logic and exposes JavaFX
 * observable properties; it is unit-testable without the FX toolkit.
 *
 * <p>{@link #login()} and {@link #pinLogin(String)} run the network call on the
 * <b>calling thread</b>; every write to an observable property is marshalled
 * through the injected {@code ui} dispatcher. In production {@code ui} is
 * {@code Platform::runLater}, so the bound controls are only ever mutated on the
 * FX Application Thread. The default constructor uses a direct executor
 * ({@code Runnable::run}) so tests observe the writes synchronously.
 */
public class LoginViewModel {
    private final AuthApi auth;
    private final Consumer<Runnable> ui;
    private final StringProperty username = new SimpleStringProperty("");
    private final StringProperty passwordOrPin = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final BooleanProperty loggedIn = new SimpleBooleanProperty(false);

    public LoginViewModel(AuthApi auth) {
        this(auth, Runnable::run);
    }

    public LoginViewModel(AuthApi auth, Consumer<Runnable> ui) {
        this.auth = auth;
        this.ui = ui;
    }

    public StringProperty username() { return username; }
    public StringProperty passwordOrPin() { return passwordOrPin; }
    public ReadOnlyBooleanProperty busy() { return busy; }
    public ReadOnlyStringProperty errorMessage() { return errorMessage; }
    public ReadOnlyBooleanProperty loggedIn() { return loggedIn; }

    public void login() {
        run(() -> auth.login(username.get(), passwordOrPin.get()));
    }

    public void pinLogin(String cashierCode) {
        run(() -> auth.pinLogin(cashierCode, passwordOrPin.get()));
    }

    private void run(Runnable call) {
        ui.accept(() -> {
            busy.set(true);
            errorMessage.set("");
            loggedIn.set(false);
        });
        try {
            call.run();
            ui.accept(() -> loggedIn.set(true));
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> {
                loggedIn.set(false);
                errorMessage.set(msg);
            });
        } finally {
            ui.accept(() -> busy.set(false));
        }
    }

    /**
     * Prefer the server's ProblemDetail (detail, then title) when present,
     * else fall back to the exception message, else a generic label.
     */
    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Login failed";
    }
}
