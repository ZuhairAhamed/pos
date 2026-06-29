package com.company.pos.sync.application;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ReturnUpload;
import com.company.pos.sales.api.ReturnCompleted;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleReturnLineView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Uploads a completed return to the ERP as a credit note. Runs after the return commits,
 * asynchronously, in its own transaction, tracked by the Event Publication Registry (Phase 3a).
 * If the ERP is offline the upload throws and the publication stays incomplete for later replay
 * (Phase 3b drain/republish) — the committed return is never affected. Idempotent on returnId.
 */
@Component
class ReturnUploadListener {

    private static final Logger log = LoggerFactory.getLogger(ReturnUploadListener.class);

    private final ReturnService returns;
    private final ErpClient erp;

    ReturnUploadListener(ReturnService returns, ErpClient erp) {
        this.returns = returns;
        this.erp = erp;
    }

    @ApplicationModuleListener
    void on(ReturnCompleted event) {
        ReturnView ret = returns.getReturn(event.returnId());
        erp.uploadReturn(toUpload(event, ret));
        log.info("Uploaded return {} ({} lines) to ERP", ret.creditNoteNumber(), ret.lines().size());
    }

    private ReturnUpload toUpload(ReturnCompleted event, ReturnView ret) {
        List<ReturnUpload.Line> lines = ret.lines().stream()
                .map(this::toLine)
                .toList();
        return new ReturnUpload(ret.id(), ret.creditNoteNumber(), ret.originalSaleId(),
                event.terminalId(), event.locationCode(), ret.currencyCode(), ret.refundSubtotal(),
                ret.refundTaxTotal(), ret.refundGrandTotal(), ret.createdAt(), lines);
    }

    private ReturnUpload.Line toLine(SaleReturnLineView l) {
        return new ReturnUpload.Line(l.lineNo(), l.originalLineNo(), l.sku(), l.name(), l.quantity(),
                l.unitPrice(), l.netAmount(), l.taxAmount(), l.lineTotal());
    }
}
