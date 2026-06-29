package com.company.pos.cashdrawer.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.CashMovementView;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.cashdrawer.domain.CashMovement;
import com.company.pos.cashdrawer.domain.DrawerSession;
import com.company.pos.cashdrawer.infrastructure.CashMovementRepository;
import com.company.pos.cashdrawer.infrastructure.DrawerSessionRepository;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.device.api.CashDrawer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultCashDrawerService implements CashDrawerService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCashDrawerService.class);

    private final DrawerSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CashDrawer device;

    DefaultCashDrawerService(DrawerSessionRepository sessions, CashMovementRepository movements,
            CashDrawer device) {
        this.sessions = sessions;
        this.movements = movements;
        this.device = device;
    }

    @Override
    public DrawerSessionView openSession(String terminalId, BigDecimal openingFloat,
            String currencyCode, String openedBy) {
        sessions.findByTerminalIdAndStatus(terminalId, "OPEN").ifPresent(s -> {
            throw DomainException.conflict("A drawer session is already open for terminal " + terminalId);
        });
        BigDecimal floatAmount = scale(openingFloat);
        if (floatAmount.signum() < 0) {
            throw DomainException.validation("Opening float cannot be negative");
        }
        DrawerSession session = new DrawerSession(Identifiers.newId(), terminalId, floatAmount,
                currencyCode, openedBy, Instant.now());
        sessions.save(session);
        appendMovement(session.getId(), "OPENING_FLOAT", floatAmount, "opening float", openedBy);
        device.open();
        return toSessionView(session);
    }

    @Override
    public void recordCashSale(String terminalId, BigDecimal amount, String reference) {
        Optional<DrawerSession> open = sessions.findByTerminalIdAndStatus(terminalId, "OPEN");
        if (open.isEmpty()) {
            log.info("Cash sale {} on terminal {} not captured — no open drawer session",
                    reference, terminalId);
            return;
        }
        appendMovement(open.get().getId(), "CASH_SALE", scale(amount), reference, null);
        device.open();
    }

    @Override
    public void recordCashRefund(String terminalId, BigDecimal amount, String reference) {
        Optional<DrawerSession> open = sessions.findByTerminalIdAndStatus(terminalId, "OPEN");
        if (open.isEmpty()) {
            log.info("Cash refund {} on terminal {} not captured — no open drawer session",
                    reference, terminalId);
            return;
        }
        appendMovement(open.get().getId(), "PAY_OUT", scale(amount), reference, null);
        device.open();
    }

    @Override
    public CashMovementView payIn(String terminalId, BigDecimal amount, String reason,
            String performedBy) {
        return record(terminalId, "PAY_IN", amount, reason, performedBy);
    }

    @Override
    public CashMovementView payOut(String terminalId, BigDecimal amount, String reason,
            String performedBy) {
        return record(terminalId, "PAY_OUT", amount, reason, performedBy);
    }

    private CashMovementView record(String terminalId, String type, BigDecimal amount, String reason,
            String performedBy) {
        BigDecimal value = scale(amount);
        if (value.signum() <= 0) {
            throw DomainException.validation(type + " amount must be positive");
        }
        DrawerSession session = sessions.findByTerminalIdAndStatus(terminalId, "OPEN")
                .orElseThrow(() -> DomainException.conflict(
                        "No open drawer session for terminal " + terminalId));
        CashMovement movement = appendMovement(session.getId(), type, value, reason, performedBy);
        device.open();
        return toMovementView(movement);
    }

    @Override
    @Transactional(readOnly = true)
    public DrawerReconciliation reconcile(UUID sessionId) {
        return reconcileSession(loadSession(sessionId));
    }

    @Override
    public DrawerReconciliation closeSession(UUID sessionId, BigDecimal countedAmount) {
        DrawerSession session = loadSession(sessionId);
        if (!session.isOpen()) {
            throw DomainException.conflict("Drawer session " + sessionId + " is not open");
        }
        session.close(scale(countedAmount), Instant.now());
        sessions.save(session);
        return reconcileSession(session);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DrawerSessionView> findOpenSession(String terminalId) {
        return sessions.findByTerminalIdAndStatus(terminalId, "OPEN").map(this::toSessionView);
    }

    private DrawerReconciliation reconcileSession(DrawerSession session) {
        List<CashMovement> ledger = movements.findBySessionIdOrderByCreatedAtAsc(session.getId());
        BigDecimal cashSales = sum(ledger, "CASH_SALE");
        BigDecimal payIns = sum(ledger, "PAY_IN");
        BigDecimal payOuts = sum(ledger, "PAY_OUT");
        int cashSalesCount = Math.toIntExact(ledger.stream()
                .filter(m -> "CASH_SALE".equals(m.getType())).count());
        BigDecimal expected = scale(session.getOpeningFloat().add(cashSales).add(payIns).subtract(payOuts));
        BigDecimal counted = session.getCountedAmount();
        BigDecimal variance = counted != null ? scale(counted.subtract(expected)) : null;
        return new DrawerReconciliation(session.getId(), session.getOpeningFloat(), cashSales,
                cashSalesCount, payIns, payOuts, expected, counted, variance, session.getCurrencyCode());
    }

    private BigDecimal sum(List<CashMovement> ledger, String type) {
        return scale(ledger.stream()
                .filter(m -> type.equals(m.getType()))
                .map(CashMovement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private CashMovement appendMovement(UUID sessionId, String type, BigDecimal amount,
            String reference, String createdBy) {
        CashMovement movement = new CashMovement(Identifiers.newId(), sessionId, type, amount,
                reference, createdBy, Instant.now());
        return movements.save(movement);
    }

    private DrawerSession loadSession(UUID sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> DomainException.notFound("No drawer session " + sessionId));
    }

    private BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private DrawerSessionView toSessionView(DrawerSession s) {
        return new DrawerSessionView(s.getId(), s.getTerminalId(), s.getStatus(), s.getOpeningFloat(),
                s.getCurrencyCode(), s.getOpenedBy(), s.getOpenedAt());
    }

    private CashMovementView toMovementView(CashMovement m) {
        return new CashMovementView(m.getId(), m.getSessionId(), m.getType(), m.getAmount(),
                m.getReference(), m.getCreatedAt());
    }
}
