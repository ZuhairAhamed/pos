package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckoutCommandTest {

    @Test
    void convenienceConstructorDefaultsDiscountsToEmpty() {
        CheckoutCommand cmd = new CheckoutCommand(UUID.randomUUID(),
                List.of(new com.company.pos.sales.api.TenderInput(PaymentMethod.CASH, null,
                        new BigDecimal("5.00"))));

        assertThat(cmd.lineDiscounts()).isEmpty();
        assertThat(cmd.transactionDiscount()).isNull();
    }

    @Test
    void compactConstructorNormalizesNullLineDiscounts() {
        CheckoutCommand cmd = new CheckoutCommand(UUID.randomUUID(), List.of(), null, null);

        assertThat(cmd.lineDiscounts()).isEmpty();
    }

    @Test
    void carriesProvidedDiscounts() {
        DiscountInput line = new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY");
        DiscountInput txn = new DiscountInput(DiscountType.AMOUNT, new BigDecimal("5.00"), "MANAGER_COMP");
        CheckoutCommand cmd = new CheckoutCommand(UUID.randomUUID(), List.of(),
                Map.of("COLA", line), txn);

        assertThat(cmd.lineDiscounts()).containsEntry("COLA", line);
        assertThat(cmd.transactionDiscount()).isEqualTo(txn);
        assertThat(txn.type()).isEqualTo(DiscountType.AMOUNT);
    }
}
