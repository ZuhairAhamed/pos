package com.company.pos.terminal.app;

import com.company.pos.terminal.api.ApiClient;
import com.company.pos.terminal.api.AuthApi;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.MenuApi;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.SessionManager;
import com.company.pos.terminal.config.TerminalConfig;

/**
 * Composition root for the terminal. Constructed once in
 * {@link PosTerminalApp#init()} and holds the singleton dependency graph
 * (config, session, shared {@link ApiClient}, and the typed {@code *Api}
 * clients) that screens and view-models are built from.
 */
public final class Services {
    public final TerminalConfig config;
    public final SessionManager session;
    public final ApiClient apiClient;
    public final AuthApi authApi;
    public final ProductApi productApi;
    public final MenuApi menuApi;
    public final DiningApi diningApi;
    public final SalesApi salesApi;

    public Services() {
        this.config = TerminalConfig.load();
        this.session = new SessionManager();
        this.apiClient = new ApiClient(config.serverBaseUrl(), session);
        this.authApi = new AuthApi(apiClient, session);
        this.productApi = new ProductApi(apiClient);
        this.menuApi = new MenuApi(apiClient);
        this.diningApi = new DiningApi(apiClient);
        this.salesApi = new SalesApi(apiClient);
    }
}
