package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
import com.company.pos.terminal.api.ReturnApi;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.ManagerAuth;
import com.company.pos.terminal.api.dto.ReturnCommand;
import com.company.pos.terminal.api.dto.ReturnLineRequest;
import com.company.pos.terminal.api.dto.ReturnView;
import com.company.pos.terminal.api.dto.SaleView;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the returns screen. Synchronous (the controller runs it off the FX thread via
 * FxTasks); the only off-thread observable write is {@code errorMessage} inside {@code ui}. The
 * manager gate is enforced here (pinLoginForToken -> isManager) before the one-shot-token POST.
 */
public class ReturnsViewModel {

    private final SalesApi salesApi;
    private final ReturnApi returnApi;
    private final AuthApi authApi;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public ReturnsViewModel(SalesApi salesApi, ReturnApi returnApi, AuthApi authApi) {
        this(salesApi, returnApi, authApi, Runnable::run);
    }

    public ReturnsViewModel(SalesApi salesApi, ReturnApi returnApi, AuthApi authApi,
            Consumer<Runnable> ui) {
        this.salesApi = salesApi;
        this.returnApi = returnApi;
        this.authApi = authApi;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Fetch the sale to return by receipt number; null on failure (message via errorMessage). */
    public SaleView lookup(String receiptNumber) {
        try {
            SaleView sale = salesApi.getSaleByReceipt(receiptNumber.trim());
            ui.accept(() -> errorMessage.set(""));
            return sale;
        } catch (ApiException e) {
            String msg = e.status() == 404 ? "No sale found for that receipt" : messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Elevate via manager PIN then POST the return; null on failure/non-manager (message set). */
    public ReturnView process(UUID saleId, List<ReturnLineRequest> lines, String cashierCode,
            String pin) {
        try {
            ManagerAuth auth = authApi.pinLoginForToken(cashierCode, pin);
            if (!auth.isManager()) {
                ui.accept(() -> errorMessage.set("This account is not a manager"));
                return null;
            }
            ReturnView view = returnApi.process(new ReturnCommand(saleId, null, lines), auth.token());
            ui.accept(() -> errorMessage.set(""));
            return view;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Prefer the server's ProblemDetail (detail, then title), else the exception message. */
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
        return "Request failed";
    }
}
