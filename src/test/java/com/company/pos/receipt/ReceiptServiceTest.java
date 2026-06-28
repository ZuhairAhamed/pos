package com.company.pos.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    @Test
    void printsReceiptHeaderLinesAndTotals() {
        ReceiptData data = new ReceiptData("S01-T01-000001", "cashier", Instant.now(),
                List.of(new ReceiptLineData("Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("10.35"))),
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"),
                new BigDecimal("20.00"), new BigDecimal("9.65"), "SAR");

        receipts.print(data);

        List<String> text = printer.lastReceipt().stream().map(PrintLine::text).toList();
        assertThat(text).anyMatch(t -> t.contains("S01-T01-000001"));
        assertThat(text).anyMatch(t -> t.contains("Cola Can"));
        assertThat(text).anyMatch(t -> t.contains("TOTAL"));
        assertThat(printer.cutCount()).isGreaterThanOrEqualTo(1);
    }
}
