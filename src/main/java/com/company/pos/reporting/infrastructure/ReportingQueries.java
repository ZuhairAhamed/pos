package com.company.pos.reporting.infrastructure;

import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.PaymentBreakdownReport.PaymentLine;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.api.TaxSummaryReport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    public PaymentBreakdownReport paymentBreakdown(LocalDate from, LocalDate to, Instant fromTs,
            Instant toTs, String currency) {
        List<MethodDirTotal> rows = jdbc.query(
                "SELECT method, txn_type AS direction, COUNT(*) AS cnt, "
                        + "COALESCE(SUM(amount), 0) AS total "
                        + "FROM payment WHERE created_at >= ? AND created_at < ? "
                        + "GROUP BY method, txn_type",
                (rs, n) -> new MethodDirTotal(rs.getString("method"), rs.getString("direction"),
                        rs.getLong("cnt"), rs.getBigDecimal("total")),
                Timestamp.from(fromTs), Timestamp.from(toTs));

        java.util.Map<String, PaymentLine> byMethod = new java.util.LinkedHashMap<>();
        BigDecimal totalCollected = BigDecimal.ZERO;
        BigDecimal totalRefunded = BigDecimal.ZERO;
        for (MethodDirTotal row : rows) {
            PaymentLine cur = byMethod.getOrDefault(row.method(),
                    new PaymentLine(row.method(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
            if ("SALE".equals(row.direction())) {
                cur = new PaymentLine(row.method(), row.cnt(), cur.collected().add(row.total()),
                        cur.refunded());
                totalCollected = totalCollected.add(row.total());
            } else {
                cur = new PaymentLine(row.method(), cur.count(), cur.collected(),
                        cur.refunded().add(row.total()));
                totalRefunded = totalRefunded.add(row.total());
            }
            byMethod.put(row.method(), cur);
        }
        return new PaymentBreakdownReport(from, to, currency, new ArrayList<>(byMethod.values()),
                totalCollected, totalRefunded);
    }

    public TaxSummaryReport taxSummary(LocalDate from, LocalDate to, Instant fromTs, Instant toTs,
            String currency) {
        Timestamp lo = Timestamp.from(fromTs);
        Timestamp hi = Timestamp.from(toTs);
        SaleAgg s = jdbc.queryForObject(
                "SELECT COUNT(*) AS cnt, COALESCE(SUM(subtotal), 0) AS subtotal, "
                        + "0 AS line_disc, 0 AS txn_disc, COALESCE(SUM(tax_total), 0) AS tax, "
                        + "0 AS gross FROM sale "
                        + "WHERE status = 'COMPLETED' AND created_at >= ? AND created_at < ?",
                (rs, n) -> new SaleAgg(rs.getLong("cnt"), rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("line_disc"), rs.getBigDecimal("txn_disc"),
                        rs.getBigDecimal("tax"), rs.getBigDecimal("gross")),
                lo, hi);
        BigDecimal refundTax = jdbc.queryForObject(
                "SELECT COALESCE(SUM(refund_tax_total), 0) FROM sales_return "
                        + "WHERE created_at >= ? AND created_at < ?",
                BigDecimal.class, lo, hi);
        BigDecimal netTax = s.tax().subtract(refundTax);
        return new TaxSummaryReport(from, to, currency, s.subtotal(), s.tax(), refundTax, netTax);
    }

    record SaleAgg(long cnt, BigDecimal subtotal, BigDecimal lineDisc, BigDecimal txnDisc,
            BigDecimal tax, BigDecimal gross) {
    }

    record ReturnAgg(long cnt, BigDecimal refund, BigDecimal refundTax) {
    }

    record MethodDirTotal(String method, String direction, long cnt, BigDecimal total) {
    }
}
