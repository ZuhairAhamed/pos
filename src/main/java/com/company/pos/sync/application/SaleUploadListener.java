package com.company.pos.sync.application;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.api.StockMovementUpload;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Uploads a completed sale and its stock-movement deltas to the ERP. Runs after the sale commits,
 * asynchronously, in its own transaction, tracked by the Spring Modulith Event Publication Registry
 * (Phase 3a). If the ERP is offline the upload throws and the publication stays incomplete for
 * later replay (Phase 3b scheduled/manual drain + republish-on-restart) — the committed sale is
 * never affected. Uploads are idempotent on saleId, so at-least-once replay never double-posts.
 */
@Component
class SaleUploadListener {

    private static final Logger log = LoggerFactory.getLogger(SaleUploadListener.class);

    private final SalesService sales;
    private final ErpClient erp;

    SaleUploadListener(SalesService sales, ErpClient erp) {
        this.sales = sales;
        this.erp = erp;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        SaleView sale = sales.getSale(event.saleId());
        erp.uploadSale(toUpload(event, sale));
        erp.uploadStockMovements(event.saleId().toString(), toMovements(event, sale));
        log.info("Uploaded sale {} ({} lines) to ERP", sale.receiptNumber(), sale.lines().size());
    }

    private SaleUpload toUpload(SaleCompleted event, SaleView sale) {
        List<SaleUpload.Line> lines = sale.lines().stream()
                .map(l -> new SaleUpload.Line(l.lineNo(), l.sku(), l.name(), l.quantity(),
                        l.unitPrice(), l.netAmount(), l.taxAmount(), l.lineTotal(),
                        l.grossAmount(), l.lineDiscountAmount(), l.lineDiscountReason()))
                .toList();
        List<SaleUpload.Payment> payments = sale.payments().stream()
                .map(p -> new SaleUpload.Payment(p.method(), p.amount(), p.amountTendered(),
                        p.changeDue(), p.maskedPan()))
                .toList();
        return new SaleUpload(sale.id(), sale.receiptNumber(), event.terminalId(),
                event.locationCode(), sale.currencyCode(), sale.subtotal(), sale.taxTotal(),
                sale.grandTotal(), sale.createdAt(), lines, payments, sale.discountTotal(),
                sale.txnDiscountAmount(), sale.txnDiscountType(), sale.txnDiscountReason());
    }

    private List<StockMovementUpload> toMovements(SaleCompleted event, SaleView sale) {
        return sale.lines().stream()
                .map(l -> new StockMovementUpload(l.sku(), event.locationCode(),
                        l.quantity().negate(), "SALE"))
                .toList();
    }
}
