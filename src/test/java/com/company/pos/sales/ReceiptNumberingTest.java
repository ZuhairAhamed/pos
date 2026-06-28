package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.sales.application.ReceiptNumbering;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ReceiptNumberingTest {

    @Autowired
    ReceiptNumbering numbering;

    private long seq(String receiptNumber) {
        return Long.parseLong(receiptNumber.substring(receiptNumber.lastIndexOf('-') + 1));
    }

    @Test
    void issuesPaddedMonotonicNumbersPerTerminal() {
        // Distinct store/terminal ids (not the S01/T01 used by checkout tests) avoid
        // cross-test contamination: nextReceiptNumber commits in REQUIRES_NEW, so its
        // increment survives even a @Transactional caller's rollback in the shared context.
        String first = numbering.nextReceiptNumber("RNT", "TA");
        String second = numbering.nextReceiptNumber("RNT", "TA");

        assertThat(first).matches("RNT-TA-\\d{6}");
        assertThat(second).matches("RNT-TA-\\d{6}");
        assertThat(seq(second)).isEqualTo(seq(first) + 1);
    }

    @Test
    void differentTerminalsHaveIndependentSequences() {
        String terminalA = numbering.nextReceiptNumber("RNT", "TB");
        String terminalC = numbering.nextReceiptNumber("RNT", "TC");

        // Independent sequences: TB's value does not advance TC's. Both are first-touch
        // for their key within this run (unique ids), so each is 1.
        assertThat(seq(terminalA)).isEqualTo(1L);
        assertThat(seq(terminalC)).isEqualTo(1L);
    }
}
