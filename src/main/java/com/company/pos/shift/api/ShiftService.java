package com.company.pos.shift.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface ShiftService {

    ShiftView openShift(String terminalId, BigDecimal openingFloat, String openedBy);

    ShiftSummary closeShift(UUID shiftId, BigDecimal countedCash, String closedBy);

    ShiftSummary getSummary(UUID shiftId);

    Optional<ShiftView> findOpenShift(String terminalId);
}
