package com.company.pos.shift.web;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.common.exception.DomainException;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.shift.api.ShiftSummary;
import com.company.pos.shift.api.ShiftView;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ShiftController {

    private final ShiftService shifts;
    private final ConfigurationService config;

    ShiftController(ShiftService shifts, ConfigurationService config) {
        this.shifts = shifts;
        this.config = config;
    }

    record OpenShiftRequest(BigDecimal openingFloat) {
    }

    record CloseShiftRequest(BigDecimal countedCash) {
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    ShiftView open(@RequestBody OpenShiftRequest body, Principal principal) {
        return shifts.openShift(terminal(), body.openingFloat(), principal.getName());
    }

    @PostMapping("/shifts/{shiftId}/close")
    ShiftSummary close(@PathVariable UUID shiftId, @RequestBody CloseShiftRequest body,
            Principal principal) {
        return shifts.closeShift(shiftId, body.countedCash(), principal.getName());
    }

    @GetMapping("/shifts/{shiftId}")
    ShiftSummary summary(@PathVariable UUID shiftId) {
        return shifts.getSummary(shiftId);
    }

    @GetMapping("/shifts/open")
    ShiftView open() {
        return shifts.findOpenShift(terminal())
                .orElseThrow(() -> DomainException.notFound("No open shift for this terminal"));
    }

    private String terminal() {
        return config.getString(SettingKey.TERMINAL_ID);
    }
}
