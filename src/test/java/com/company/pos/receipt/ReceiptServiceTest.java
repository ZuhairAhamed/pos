package com.company.pos.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.EmailMessage;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryEmailer;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptPaymentData;
import com.company.pos.receipt.api.ReceiptService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ReceiptServiceTest {

    @Autowired
    ReceiptService receipts;
    @Autowired
    InMemoryPrinter printer;
    @Autowired
    InMemoryEmailer emailer;

    @BeforeEach
    void clearEmail() {
        emailer.clear();
    }

    @Test
    void printsCashAndCardTendersWithTotals() {
        ReceiptData data = new ReceiptData("S01-T01-000001", "cashier", Instant.now(),
                List.of(new ReceiptLineData("Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("10.35"))),
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"),
                List.of(
                        new ReceiptPaymentData("CARD", new BigDecimal("5.00"),
                                new BigDecimal("5.00"), new BigDecimal("0.00"), "**** **** **** 4242"),
                        new ReceiptPaymentData("CASH", new BigDecimal("5.35"),
                                new BigDecimal("10.00"), new BigDecimal("4.65"), null)),
                "SAR");

        receipts.print(data);

        List<String> text = printer.lastReceipt().stream().map(PrintLine::text).toList();
        assertThat(text).anyMatch(t -> t.contains("S01-T01-000001"));
        assertThat(text).anyMatch(t -> t.contains("Cola Can"));
        assertThat(text).anyMatch(t -> t.contains("TOTAL"));
        assertThat(text).anyMatch(t -> t.contains("CARD"));
        assertThat(text).anyMatch(t -> t.contains("**** **** **** 4242"));
        assertThat(text).anyMatch(t -> t.contains("CASH"));
        assertThat(text).anyMatch(t -> t.contains("Change"));
        assertThat(printer.cutCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void emailReceiptSendsRenderedBodyToEmailer() {
        ReceiptData data = new ReceiptData("S01-T01-000042", "cashier", Instant.now(),
                List.of(new ReceiptLineData("Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("10.35"))),
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"),
                List.of(new ReceiptPaymentData("CASH", new BigDecimal("10.35"),
                        new BigDecimal("11.00"), new BigDecimal("0.65"), null)),
                "SAR");

        receipts.emailReceipt("guest@example.com", data);

        assertThat(emailer.sent()).hasSize(1);
        EmailMessage msg = emailer.sent().get(0);
        assertThat(msg.to()).isEqualTo("guest@example.com");
        assertThat(msg.subject()).contains("S01-T01-000042");
        assertThat(msg.body()).contains("Cola Can");
        assertThat(msg.body()).contains("TOTAL");
        assertThat(msg.body()).contains("\n");
    }
}
