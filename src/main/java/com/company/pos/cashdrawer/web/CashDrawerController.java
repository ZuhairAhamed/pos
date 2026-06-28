package com.company.pos.cashdrawer.web;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.CashMovementView;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import java.math.BigDecimal;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CashDrawerController {

    private final CashDrawerService drawer;
    private final ConfigurationService config;

    CashDrawerController(CashDrawerService drawer, ConfigurationService config) {
        this.drawer = drawer;
        this.config = config;
    }

    record CashMovementRequest(BigDecimal amount, String reason) {
    }

    @PostMapping("/cash-drawer/pay-in")
    CashMovementView payIn(@RequestBody CashMovementRequest body, Principal principal) {
        return drawer.payIn(terminal(), body.amount(), body.reason(), principal.getName());
    }

    @PostMapping("/cash-drawer/pay-out")
    CashMovementView payOut(@RequestBody CashMovementRequest body, Principal principal) {
        return drawer.payOut(terminal(), body.amount(), body.reason(), principal.getName());
    }

    @GetMapping("/cash-drawer/reconciliation")
    DrawerReconciliation reconciliation() {
        DrawerSessionView session = drawer.findOpenSession(terminal())
                .orElseThrow(() -> DomainException.notFound("No open drawer session for this terminal"));
        return drawer.reconcile(session.sessionId());
    }

    private String terminal() {
        return config.getString(SettingKey.TERMINAL_ID);
    }
}
