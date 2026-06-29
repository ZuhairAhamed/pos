package com.company.pos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.api.StockMovementUpload;
import com.company.pos.integration.erp.FakeErpClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeErpClientUploadTest {

    private SaleUpload sampleSale(UUID id) {
        return new SaleUpload(id, "S01-T01-000001", "T01", "MAIN", "SAR",
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"), Instant.EPOCH,
                List.of(new SaleUpload.Line(1, "COLA", "Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("9.00"), new BigDecimal("1.35"),
                        new BigDecimal("10.35"))),
                List.of(new SaleUpload.Payment("CASH", new BigDecimal("10.35"),
                        new BigDecimal("20.00"), new BigDecimal("9.65"), null)));
    }

    @Test
    void uploadSaleIsIdempotentOnSaleId() {
        FakeErpClient fake = new FakeErpClient();
        UUID id = UUID.randomUUID();

        fake.uploadSale(sampleSale(id));
        fake.uploadSale(sampleSale(id)); // retry

        assertThat(fake.uploadedSales()).hasSize(1);
        assertThat(fake.uploadedSales().get(0).saleId()).isEqualTo(id);
    }

    @Test
    void uploadStockMovementsIsIdempotentOnSaleId() {
        FakeErpClient fake = new FakeErpClient();
        List<StockMovementUpload> deltas =
                List.of(new StockMovementUpload("COLA", "MAIN", new BigDecimal("-2"), "SALE"));

        fake.uploadStockMovements("sale-1", deltas);
        fake.uploadStockMovements("sale-1", deltas); // retry

        assertThat(fake.uploadedMovementBatches()).hasSize(1);
        assertThat(fake.uploadedMovementBatches().get("sale-1")).hasSize(1);
    }

    @Test
    void uploadsThrowWhenOffline() {
        FakeErpClient fake = new FakeErpClient();
        fake.setAvailable(false);

        assertThatThrownBy(() -> fake.uploadSale(sampleSale(UUID.randomUUID())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fake.uploadStockMovements("sale-1", List.of()))
                .isInstanceOf(RuntimeException.class);
        assertThat(fake.uploadedSales()).isEmpty();
        assertThat(fake.uploadedMovementBatches()).isEmpty();
    }

    @Test
    void clearResetsUploadsAndAvailability() {
        FakeErpClient fake = new FakeErpClient();
        fake.uploadSale(sampleSale(UUID.randomUUID()));
        fake.setAvailable(false);

        fake.clear();

        assertThat(fake.uploadedSales()).isEmpty();
        // available reset to true -> this upload succeeds
        fake.uploadSale(sampleSale(UUID.randomUUID()));
        assertThat(fake.uploadedSales()).hasSize(1);
    }
}
