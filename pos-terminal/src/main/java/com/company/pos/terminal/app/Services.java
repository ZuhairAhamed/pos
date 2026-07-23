package com.company.pos.terminal.app;

import com.company.pos.terminal.api.ApiClient;
import com.company.pos.terminal.api.AuthApi;
import com.company.pos.terminal.api.CartApi;
import com.company.pos.terminal.api.CashDrawerApi;
import com.company.pos.terminal.api.ConfigApi;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.KitchenApi;
import com.company.pos.terminal.api.MenuAdminApi;
import com.company.pos.terminal.api.MenuApi;
import com.company.pos.terminal.api.VariantAdminApi;
import com.company.pos.terminal.api.TableAdminApi;
import com.company.pos.terminal.api.ProductAdminApi;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.RealtimeClient;
import com.company.pos.terminal.api.RealtimeClients;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.SessionManager;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.UsersApi;
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
    public final ProductAdminApi productAdminApi;
    public final MenuApi menuApi;
    public final MenuAdminApi menuAdminApi;
    public final VariantAdminApi variantAdminApi;
    public final DiningApi diningApi;
    public final SalesApi salesApi;
    public final CartApi cartApi;
    public final ShiftApi shiftApi;
    public final CashDrawerApi cashDrawerApi;
    public final UsersApi usersApi;
    public final KitchenApi kitchenApi;
    public final TableAdminApi tableAdminApi;
    public final ConfigApi configApi;

    public Services() {
        this.config = TerminalConfig.load();
        this.session = new SessionManager();
        this.apiClient = new ApiClient(config.serverBaseUrl(), session);
        this.authApi = new AuthApi(apiClient, session);
        this.productApi = new ProductApi(apiClient);
        this.productAdminApi = new ProductAdminApi(apiClient);
        this.menuApi = new MenuApi(apiClient);
        this.menuAdminApi = new MenuAdminApi(apiClient);
        this.variantAdminApi = new VariantAdminApi(apiClient);
        this.diningApi = new DiningApi(apiClient);
        this.salesApi = new SalesApi(apiClient);
        this.cartApi = new CartApi(apiClient);
        this.shiftApi = new ShiftApi(apiClient);
        this.cashDrawerApi = new CashDrawerApi(apiClient);
        this.usersApi = new UsersApi(apiClient);
        this.kitchenApi = new KitchenApi(apiClient);
        this.tableAdminApi = new TableAdminApi(apiClient);
        this.configApi = new ConfigApi(apiClient);
    }

    /** A fresh realtime push client for one screen's lifecycle (connect on enter, close on leave).
     *  Returns a no-op client when {@code realtime.enabled=false}. */
    public RealtimeClient newRealtimeClient() {
        return RealtimeClients.create(config, session);
    }
}
