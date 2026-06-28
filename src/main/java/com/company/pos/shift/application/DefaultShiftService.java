package com.company.pos.shift.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.shift.api.ShiftSummary;
import com.company.pos.shift.api.ShiftView;
import com.company.pos.shift.domain.Shift;
import com.company.pos.shift.infrastructure.ShiftRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultShiftService implements ShiftService {

    private final ShiftRepository shifts;
    private final CashDrawerService drawer;
    private final ConfigurationService config;

    DefaultShiftService(ShiftRepository shifts, CashDrawerService drawer, ConfigurationService config) {
        this.shifts = shifts;
        this.drawer = drawer;
        this.config = config;
    }

    @Override
    public ShiftView openShift(String terminalId, BigDecimal openingFloat, String openedBy) {
        shifts.findByTerminalIdAndStatus(terminalId, "OPEN").ifPresent(s -> {
            throw DomainException.conflict("A shift is already open for terminal " + terminalId);
        });
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        DrawerSessionView session = drawer.openSession(terminalId, openingFloat, currency, openedBy);
        Shift shift = new Shift(Identifiers.newId(), terminalId, openedBy, session.sessionId(),
                currency, Instant.now());
        shifts.save(shift);
        return toView(shift);
    }

    @Override
    public ShiftSummary closeShift(UUID shiftId, BigDecimal countedCash, String closedBy) {
        Shift shift = load(shiftId);
        if (!shift.isOpen()) {
            throw DomainException.conflict("Shift " + shiftId + " is not open");
        }
        DrawerReconciliation cash = drawer.closeSession(shift.getDrawerSessionId(), countedCash);
        shift.close(countedCash, Instant.now());
        return toSummary(shift, cash);
    }

    @Override
    @Transactional(readOnly = true)
    public ShiftSummary getSummary(UUID shiftId) {
        Shift shift = load(shiftId);
        DrawerReconciliation cash = drawer.reconcile(shift.getDrawerSessionId());
        return toSummary(shift, cash);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShiftView> findOpenShift(String terminalId) {
        return shifts.findByTerminalIdAndStatus(terminalId, "OPEN").map(this::toView);
    }

    private Shift load(UUID shiftId) {
        return shifts.findById(shiftId)
                .orElseThrow(() -> DomainException.notFound("No shift " + shiftId));
    }

    private ShiftView toView(Shift shift) {
        return new ShiftView(shift.getId(), shift.getTerminalId(), shift.getOpenedBy(),
                shift.getStatus(), shift.getCurrencyCode(), shift.getOpenedAt(), shift.getClosedAt());
    }

    private ShiftSummary toSummary(Shift shift, DrawerReconciliation cash) {
        return new ShiftSummary(shift.getId(), shift.getTerminalId(), shift.getOpenedBy(),
                shift.getStatus(), shift.getOpenedAt(), shift.getClosedAt(), cash);
    }
}
