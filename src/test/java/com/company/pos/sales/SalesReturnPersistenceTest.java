package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.sales.domain.SalesReturn;
import com.company.pos.sales.domain.SalesReturnLine;
import com.company.pos.sales.infrastructure.SalesReturnLineRepository;
import com.company.pos.sales.infrastructure.SalesReturnRepository;
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

/**
 * Persists a SalesReturn with one line and proves the over-return sum query aggregates by original
 * sale + original line. Non-@Transactional (commits) + DatabaseCleaner.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SalesReturnPersistenceTest {

    @Autowired
    SalesReturnRepository returns;
    @Autowired
    SalesReturnLineRepository returnLines;
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
    void sumReturnedQuantityAggregatesByOriginalLine() {
        UUID originalSaleId = Identifiers.newId();
        assertThat(returnLines.sumReturnedQuantity(originalSaleId, 1)).isEqualByComparingTo("0");

        SalesReturn r = new SalesReturn(Identifiers.newId(), "S01-T01R-000001", originalSaleId,
                "S01", "T01", "manager", "MAIN", "SAR", new BigDecimal("4.50"),
                new BigDecimal("0.68"), new BigDecimal("5.18"), Instant.now());
        r.addLine(new SalesReturnLine(Identifiers.newId(), r, 1, 1, "COLA", "Cola Can",
                new BigDecimal("1.000"), new BigDecimal("4.5000"), new BigDecimal("4.50"),
                new BigDecimal("0.68"), new BigDecimal("5.18"), "SAR"));
        returns.save(r);

        assertThat(returnLines.sumReturnedQuantity(originalSaleId, 1)).isEqualByComparingTo("1.000");
        assertThat(returnLines.sumReturnedQuantity(originalSaleId, 2)).isEqualByComparingTo("0");
    }
}
