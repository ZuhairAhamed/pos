package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.dto.SalesSummaryReport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReportingApiTest {

    private static final LocalDate FROM = LocalDate.parse("2026-07-01");
    private static final LocalDate TO   = LocalDate.parse("2026-07-24");

    @Test
    void salesReportRequestsCorrectPathAndParses() throws Exception {
        String json = "{\"from\":\"2026-07-01\",\"to\":\"2026-07-24\",\"currencyCode\":\"SAR\","
                + "\"saleCount\":34,\"subtotal\":1000,\"lineDiscounts\":0,\"txnDiscounts\":0,"
                + "\"taxTotal\":150,\"grossSales\":1240,\"returnCount\":0,\"refundTotal\":0,"
                + "\"netSales\":1240}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            ReportingApi api = new ReportingApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SalesSummaryReport r = api.salesReport(FROM, TO);
            assertEquals(34, r.saleCount());
            assertEquals("/reports/sales", stub.lastPath);
            assertTrue(stub.lastQuery.contains("from=2026-07-01"));
            assertTrue(stub.lastQuery.contains("to=2026-07-24"));
        }
    }

    @Test
    void exportCsvHitsCsvPathAndReturnsRawBody() throws Exception {
        String body = "a,b\n1,2\n";
        try (StubServer stub = new StubServer(200, body, "text/csv")) {
            ReportingApi api = new ReportingApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            String csv = api.exportCsv(ReportType.PRODUCTS, FROM, TO, 50);
            assertEquals(body, csv);
            assertEquals("/reports/products", stub.lastPath);
            assertTrue(stub.lastQuery.contains("format=csv"));
            assertTrue(stub.lastQuery.contains("limit=50"));
        }
    }
}
