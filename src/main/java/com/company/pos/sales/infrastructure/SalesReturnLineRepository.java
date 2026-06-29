package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.SalesReturnLine;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesReturnLineRepository extends JpaRepository<SalesReturnLine, UUID> {

    /** Total quantity already returned for an original sale line (0 when none). */
    @Query("select coalesce(sum(l.quantity), 0) from SalesReturnLine l "
            + "where l.salesReturn.originalSaleId = :saleId and l.originalLineNo = :lineNo")
    BigDecimal sumReturnedQuantity(@Param("saleId") UUID saleId, @Param("lineNo") int lineNo);
}
