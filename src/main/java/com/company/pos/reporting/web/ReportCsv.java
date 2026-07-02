package com.company.pos.reporting.web;

import com.company.pos.reporting.api.CashierReport;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.api.TaxSummaryReport;

final class ReportCsv {

    private ReportCsv() {
    }

    static String of(SalesSummaryReport r) {
        String header = "from,to,currencyCode,saleCount,subtotal,lineDiscounts,txnDiscounts,"
                + "taxTotal,grossSales,returnCount,refundTotal,netSales";
        String row = String.join(",", s(r.from()), s(r.to()), r.currencyCode(),
                String.valueOf(r.saleCount()), s(r.subtotal()), s(r.lineDiscounts()),
                s(r.txnDiscounts()), s(r.taxTotal()), s(r.grossSales()),
                String.valueOf(r.returnCount()), s(r.refundTotal()), s(r.netSales()));
        return header + "\n" + row + "\n";
    }

    static String of(TaxSummaryReport r) {
        String header = "from,to,currencyCode,taxableAmount,taxCollected,refundTax,netTax";
        String row = String.join(",", s(r.from()), s(r.to()), r.currencyCode(),
                s(r.taxableAmount()), s(r.taxCollected()), s(r.refundTax()), s(r.netTax()));
        return header + "\n" + row + "\n";
    }

    static String of(PaymentBreakdownReport r) {
        StringBuilder sb = new StringBuilder("method,count,collected,refunded\n");
        for (PaymentBreakdownReport.PaymentLine l : r.lines()) {
            sb.append(String.join(",", l.method(), String.valueOf(l.count()),
                    s(l.collected()), s(l.refunded()))).append('\n');
        }
        return sb.toString();
    }

    static String of(CashierReport r) {
        StringBuilder sb = new StringBuilder("cashierUsername,saleCount,totalSales,totalDiscounts\n");
        for (CashierReport.CashierLine l : r.lines()) {
            sb.append(String.join(",", l.cashierUsername(), String.valueOf(l.saleCount()),
                    s(l.totalSales()), s(l.totalDiscounts()))).append('\n');
        }
        return sb.toString();
    }

    static String of(ProductPerformanceReport r) {
        StringBuilder sb = new StringBuilder("sku,name,quantitySold,revenue,discounts\n");
        for (ProductPerformanceReport.ProductLine l : r.lines()) {
            sb.append(String.join(",", l.sku(), csv(l.name()), s(l.quantitySold()),
                    s(l.revenue()), s(l.discounts()))).append('\n');
        }
        return sb.toString();
    }

    private static String s(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
