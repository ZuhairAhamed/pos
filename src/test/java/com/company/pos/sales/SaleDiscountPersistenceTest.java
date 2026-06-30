package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.sales.domain.Sale;
import com.company.pos.sales.domain.SaleLine;
import com.company.pos.sales.infrastructure.SaleRepository;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
@Transactional
class SaleDiscountPersistenceTest {

    @Autowired
    SaleRepository sales;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void clean() {
        databaseCleaner.clean();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
    }

    @Test
    void discountFieldsRoundTrip() {
        UUID saleId = Identifiers.newId();
        Sale sale = new Sale(saleId, "R-DISC-1", "S01", "T01", "cashier", "MAIN", "SAR",
                new BigDecimal("8.10"), new BigDecimal("1.22"), new BigDecimal("9.32"), Instant.now(),
                new BigDecimal("0.00"), null, null, new BigDecimal("0.90"));
        sale.addLine(new SaleLine(Identifiers.newId(), sale, 1, "COLA", "Cola Can",
                new BigDecimal("2.000"), new BigDecimal("4.5000"), new BigDecimal("8.10"),
                new BigDecimal("1.22"), new BigDecimal("9.32"), "SAR",
                new BigDecimal("9.00"), new BigDecimal("0.90"), "PERCENT", "LOYALTY"));
        sales.save(sale);

        Sale loaded = sales.findById(saleId).orElseThrow();
        assertThat(loaded.getDiscountTotal()).isEqualByComparingTo("0.90");
        assertThat(loaded.getTxnDiscountAmount()).isEqualByComparingTo("0.00");
        SaleLine line = loaded.getLines().get(0);
        assertThat(line.getGrossAmount()).isEqualByComparingTo("9.00");
        assertThat(line.getLineDiscountAmount()).isEqualByComparingTo("0.90");
        assertThat(line.getLineDiscountType()).isEqualTo("PERCENT");
        assertThat(line.getLineDiscountReason()).isEqualTo("LOYALTY");
    }
}
