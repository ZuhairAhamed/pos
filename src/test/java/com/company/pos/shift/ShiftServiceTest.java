package com.company.pos.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.shift.api.ShiftSummary;
import com.company.pos.shift.api.ShiftView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ShiftServiceTest {

    @Autowired
    ShiftService shifts;

    @Test
    void openShiftSeedsFloatAndIsFindable() {
        ShiftView shift = shifts.openShift("T01", new BigDecimal("100.00"), "cashier");

        assertThat(shift.status()).isEqualTo("OPEN");
        assertThat(shift.terminalId()).isEqualTo("T01");
        assertThat(shifts.findOpenShift("T01")).isPresent();

        ShiftSummary summary = shifts.getSummary(shift.shiftId());
        assertThat(summary.cash().openingFloat()).isEqualByComparingTo("100.00");
        assertThat(summary.cash().expectedCash()).isEqualByComparingTo("100.00");
    }

    @Test
    void cannotOpenTwoShiftsForSameTerminal() {
        shifts.openShift("T01", new BigDecimal("100.00"), "cashier");
        assertThatThrownBy(() -> shifts.openShift("T01", new BigDecimal("50.00"), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void closeShiftReportsVarianceAndClears() {
        ShiftView shift = shifts.openShift("T01", new BigDecimal("100.00"), "cashier");
        // no sales; count 99.00 -> short 1.00
        ShiftSummary summary = shifts.closeShift(shift.shiftId(), new BigDecimal("99.00"), "cashier");

        assertThat(summary.status()).isEqualTo("CLOSED");
        assertThat(summary.cash().expectedCash()).isEqualByComparingTo("100.00");
        assertThat(summary.cash().countedCash()).isEqualByComparingTo("99.00");
        assertThat(summary.cash().variance()).isEqualByComparingTo("-1.00");
        assertThat(summary.closedBy()).isEqualTo("cashier");
        assertThat(shifts.findOpenShift("T01")).isEmpty();
    }

    @Test
    void closingAlreadyClosedShiftIsRejected() {
        ShiftView shift = shifts.openShift("T01", new BigDecimal("100.00"), "cashier");
        shifts.closeShift(shift.shiftId(), new BigDecimal("100.00"), "cashier");
        assertThatThrownBy(() -> shifts.closeShift(shift.shiftId(), new BigDecimal("100.00"), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void listOpenShiftsReturnsOnlyOpenOnes() {
        ShiftView open = shifts.openShift("T01", new BigDecimal("100.00"), "alice");
        ShiftView toClose = shifts.openShift("T02", new BigDecimal("50.00"), "bob");
        shifts.closeShift(toClose.shiftId(), new BigDecimal("50.00"), "bob");

        List<ShiftView> openShifts = shifts.listOpenShifts();

        assertThat(openShifts).extracting(ShiftView::terminalId).containsExactly("T01");
        assertThat(openShifts).extracting(ShiftView::openedBy).containsExactly("alice");
        assertThat(openShifts.get(0).shiftId()).isEqualTo(open.shiftId());
    }
}
