package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.pricing.api.PricedLine;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.application.DiscountCalculator;
import com.company.pos.sales.application.DiscountResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscountCalculatorTest {

    private final DiscountCalculator calc = new DiscountCalculator();
    private static final BigDecimal MAX_PCT = new BigDecimal("10");
    private static final BigDecimal MAX_AMT = new BigDecimal("20.00");
    private static final Set<String> REASONS = Set.of("DAMAGED", "PRICE_MATCH", "LOYALTY", "MANAGER_COMP");

    private PricedLine line(String sku, String qty, String unit, String extended) {
        return new PricedLine(sku, sku, new BigDecimal(qty), new BigDecimal(unit), "SAR",
                new BigDecimal(extended));
    }

    @Test
    void percentLineDiscountResolvesAndReducesExtended() {
        DiscountResult r = calc.apply(List.of(line("COLA", "2", "4.50", "9.00")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null, false, MAX_PCT, MAX_AMT, REASONS);

        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).grossAmount()).isEqualByComparingTo("9.00");
        assertThat(r.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("0.90");
        assertThat(r.lines().get(0).discountedExtended()).isEqualByComparingTo("8.10");
        assertThat(r.lines().get(0).lineDiscountType()).isEqualTo(DiscountType.PERCENT);
        assertThat(r.lines().get(0).lineDiscountReason()).isEqualTo("LOYALTY");
        assertThat(r.discountTotal()).isEqualByComparingTo("0.90");
        assertThat(r.txnDiscountAmount()).isEqualByComparingTo("0");
    }

    @Test
    void fixedAmountLineDiscountCapsToBase() {
        // value 8.00 exceeds the line base 5.00 -> capped to 5.00. Manager (100% of line) so no cap block.
        DiscountResult r = calc.apply(List.of(line("PEN", "1", "5.00", "5.00")),
                Map.of("PEN", new DiscountInput(DiscountType.AMOUNT, new BigDecimal("8.00"), "DAMAGED")),
                null, true, MAX_PCT, MAX_AMT, REASONS);

        assertThat(r.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("5.00");
        assertThat(r.lines().get(0).discountedExtended()).isEqualByComparingTo("0.00");
    }

    @Test
    void transactionDiscountAllocatesProportionallyWithLastLineRemainder() {
        DiscountResult r = calc.apply(
                List.of(line("A", "1", "10.00", "10.00"), line("B", "1", "5.00", "5.00")),
                Map.of(),
                new DiscountInput(DiscountType.AMOUNT, new BigDecimal("3.00"), "MANAGER_COMP"),
                true, MAX_PCT, MAX_AMT, REASONS);

        // cartBase 15.00: A share = 3 * 10/15 = 2.00, B (last) = 3 - 2 = 1.00
        assertThat(r.lines().get(0).discountedExtended()).isEqualByComparingTo("8.00");
        assertThat(r.lines().get(1).discountedExtended()).isEqualByComparingTo("4.00");
        assertThat(r.txnDiscountAmount()).isEqualByComparingTo("3.00");
        assertThat(r.discountTotal()).isEqualByComparingTo("3.00");
    }

    @Test
    void lineAndTransactionDiscountsCombineAndReconcile() {
        DiscountResult r = calc.apply(
                List.of(line("COLA", "2", "4.50", "9.00"), line("WATER", "3", "2.00", "6.00")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                new DiscountInput(DiscountType.AMOUNT, new BigDecimal("4.10"), "MANAGER_COMP"),
                true, MAX_PCT, MAX_AMT, REASONS);

        // COLA: gross 9.00 - 0.90 = postLine 8.10; WATER postLine 6.00; cartBase 14.10
        // COLA txn share = 4.10 * 8.10/14.10 = 2.36; WATER (last) = 4.10 - 2.36 = 1.74
        assertThat(r.lines().get(0).discountedExtended()).isEqualByComparingTo("5.74");
        assertThat(r.lines().get(1).discountedExtended()).isEqualByComparingTo("4.26");
        assertThat(r.discountTotal()).isEqualByComparingTo("5.00"); // 0.90 + 4.10
    }

    @Test
    void unknownReasonCodeIsRejected() {
        assertThatThrownBy(() -> calc.apply(List.of(line("COLA", "1", "4.50", "4.50")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("5"), "BOGUS")),
                null, false, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unknownSkuLineDiscountIsRejected() {
        assertThatThrownBy(() -> calc.apply(List.of(line("COLA", "1", "4.50", "4.50")),
                Map.of("PEPSI", new DiscountInput(DiscountType.PERCENT, new BigDecimal("5"), "LOYALTY")),
                null, false, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void cashierOverPercentCapIsRejected() {
        assertThatThrownBy(() -> calc.apply(List.of(line("COLA", "2", "4.50", "9.00")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("20"), "LOYALTY")),
                null, false, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void cashierOverAmountCapIsRejected() {
        // 25.00 on a 1000.00 line is only 2.5% (under the percent cap) but exceeds the 20.00 amount cap.
        assertThatThrownBy(() -> calc.apply(List.of(line("TV", "1", "1000.00", "1000.00")),
                Map.of("TV", new DiscountInput(DiscountType.AMOUNT, new BigDecimal("25.00"), "PRICE_MATCH")),
                null, false, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void managerBypassesBothCaps() {
        DiscountResult r = calc.apply(List.of(line("COLA", "2", "4.50", "9.00")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("50"), "MANAGER_COMP")),
                null, true, MAX_PCT, MAX_AMT, REASONS);

        assertThat(r.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("4.50");
    }

    @Test
    void percentageOver100IsRejected() {
        assertThatThrownBy(() -> calc.apply(List.of(line("COLA", "1", "4.50", "4.50")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("150"), "LOYALTY")),
                null, true, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }
}
