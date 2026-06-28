package com.company.pos.cashdrawer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryCashDrawer;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class CashDrawerServiceTest {

    @Autowired
    CashDrawerService drawer;
    @Autowired
    InMemoryCashDrawer device;

    @Test
    void openSessionRecordsFloatAndOpensDrawer() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");

        assertThat(session.status()).isEqualTo("OPEN");
        assertThat(session.openingFloat()).isEqualByComparingTo("100.00");
        assertThat(device.isOpen()).isTrue();
        assertThat(drawer.findOpenSession("T01")).isPresent();
    }

    @Test
    void cannotOpenTwoSessionsForSameTerminal() {
        drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        assertThatThrownBy(() -> drawer.openSession("T01", new BigDecimal("50.00"), "SAR", "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void reconciliationSumsFloatSalesPayInsAndPayOuts() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        drawer.recordCashSale("T01", new BigDecimal("10.35"), "sale-1");
        drawer.recordCashSale("T01", new BigDecimal("4.65"), "sale-2");
        drawer.payIn("T01", new BigDecimal("20.00"), "change fund", "cashier");
        drawer.payOut("T01", new BigDecimal("5.00"), "milk run", "cashier");

        DrawerReconciliation recon = drawer.reconcile(session.sessionId());
        assertThat(recon.openingFloat()).isEqualByComparingTo("100.00");
        assertThat(recon.cashSales()).isEqualByComparingTo("15.00");
        assertThat(recon.cashSalesCount()).isEqualTo(2);
        assertThat(recon.payIns()).isEqualByComparingTo("20.00");
        assertThat(recon.payOuts()).isEqualByComparingTo("5.00");
        // 100 + 15 + 20 - 5 = 130.00
        assertThat(recon.expectedCash()).isEqualByComparingTo("130.00");
        assertThat(recon.countedCash()).isNull();
        assertThat(recon.variance()).isNull();
    }

    @Test
    void closingComputesVariance() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        drawer.recordCashSale("T01", new BigDecimal("10.00"), "sale-1");
        // expected 110.00; count 108.50 -> short 1.50
        DrawerReconciliation recon = drawer.closeSession(session.sessionId(), new BigDecimal("108.50"));

        assertThat(recon.expectedCash()).isEqualByComparingTo("110.00");
        assertThat(recon.countedCash()).isEqualByComparingTo("108.50");
        assertThat(recon.variance()).isEqualByComparingTo("-1.50");
        assertThat(drawer.findOpenSession("T01")).isEmpty();
    }

    @Test
    void recordCashSaleWithNoOpenSessionIsNoOp() {
        // no session open for T02
        drawer.recordCashSale("T02", new BigDecimal("9.99"), "sale-x");
        assertThat(drawer.findOpenSession("T02")).isEmpty();
    }

    @Test
    void payInWithoutOpenSessionIsRejected() {
        assertThatThrownBy(() -> drawer.payIn("T09", new BigDecimal("5.00"), "x", "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void payOutMustBePositive() {
        drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        assertThatThrownBy(() -> drawer.payOut("T01", BigDecimal.ZERO, "x", "cashier"))
                .isInstanceOf(DomainException.class);
    }
}
