package com.company.pos.reporting.infrastructure;

import com.company.pos.reporting.api.SalesSummaryReport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReportingQueries {

    private final JdbcTemplate jdbc;

    public ReportingQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SalesSummaryReport salesSummary(LocalDate from, LocalDate to, Instant fromTs, Instant toTs,
            String currency) {
        // Bind as java.sql.Timestamp so SQLite JDBC can compare against its TIMESTAMP columns
        // (which Hibernate stores as ISO-8601 strings). Timestamp.from() converts the UTC Instant
        // to a Timestamp that the SQLite JDBC driver serialises correctly for string comparison.
        Timestamp lo = Timestamp.from(fromTs);
        Timestamp hi = Timestamp.from(toTs);

        SaleAgg s = jdbc.queryForObject(
                "SELECT COUNT(*) AS cnt, "
                        + "COALESCE(SUM(subtotal), 0) AS subtotal, "
                        + "COALESCE(SUM(discount_total), 0) AS line_disc, "
                        + "COALESCE(SUM(txn_discount_amount), 0) AS txn_disc, "
                        + "COALESCE(SUM(tax_total), 0) AS tax, "
                        + "COALESCE(SUM(grand_total), 0) AS gross "
                        + "FROM sale WHERE status = 'COMPLETED' AND created_at >= ? AND created_at < ?",
                (rs, n) -> new SaleAgg(rs.getLong("cnt"), rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("line_disc"), rs.getBigDecimal("txn_disc"),
                        rs.getBigDecimal("tax"), rs.getBigDecimal("gross")),
                lo, hi);

        ReturnAgg r = jdbc.queryForObject(
                "SELECT COUNT(*) AS cnt, "
                        + "COALESCE(SUM(refund_grand_total), 0) AS refund, "
                        + "COALESCE(SUM(refund_tax_total), 0) AS refund_tax "
                        + "FROM sales_return WHERE created_at >= ? AND created_at < ?",
                (rs, n) -> new ReturnAgg(rs.getLong("cnt"), rs.getBigDecimal("refund"),
                        rs.getBigDecimal("refund_tax")),
                lo, hi);

        BigDecimal net = s.gross().subtract(r.refund());
        return new SalesSummaryReport(from, to, currency, s.cnt(), s.subtotal(), s.lineDisc(),
                s.txnDisc(), s.tax(), s.gross(), r.cnt(), r.refund(), net);
    }

    record SaleAgg(long cnt, BigDecimal subtotal, BigDecimal lineDisc, BigDecimal txnDisc,
            BigDecimal tax, BigDecimal gross) {
    }

    record ReturnAgg(long cnt, BigDecimal refund, BigDecimal refundTax) {
    }
}
