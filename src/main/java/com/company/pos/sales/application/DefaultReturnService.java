package com.company.pos.sales.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptPaymentData;
import com.company.pos.receipt.api.ReceiptService;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnCompleted;
import com.company.pos.sales.api.ReturnPaymentView;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleReturnLineView;
import com.company.pos.sales.domain.Sale;
import com.company.pos.sales.domain.SaleLine;
import com.company.pos.sales.domain.SalesReturn;
import com.company.pos.sales.domain.SalesReturnLine;
import com.company.pos.sales.infrastructure.SaleRepository;
import com.company.pos.sales.infrastructure.SalesReturnLineRepository;
import com.company.pos.sales.infrastructure.SalesReturnRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultReturnService implements ReturnService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReturnService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final SaleRepository sales;
    private final SalesReturnRepository returns;
    private final SalesReturnLineRepository returnLines;
    private final PaymentService payments;
    private final ReceiptService receipts;
    private final ConfigurationService config;
    private final ReceiptNumbering numbering;
    private final DomainEvents events;

    DefaultReturnService(SaleRepository sales, SalesReturnRepository returns,
            SalesReturnLineRepository returnLines, PaymentService payments, ReceiptService receipts,
            ConfigurationService config, ReceiptNumbering numbering, DomainEvents events) {
        this.sales = sales;
        this.returns = returns;
        this.returnLines = returnLines;
        this.payments = payments;
        this.receipts = receipts;
        this.config = config;
        this.numbering = numbering;
        this.events = events;
    }

    @Override
    public ReturnView processReturn(ReturnCommand command, String managerUsername) {
        Sale sale = resolveSale(command);
        if (command.lines() == null || command.lines().isEmpty()) {
            throw DomainException.validation("A return must have at least one line");
        }

        UUID returnId = Identifiers.newId();
        String currency = sale.getCurrencyCode();
        String storeId = config.getString(SettingKey.STORE_ID);
        String terminalId = config.getString(SettingKey.TERMINAL_ID);
        String location = config.getString(SettingKey.INVENTORY_LOCATION);
        String creditNoteNumber = numbering.nextReceiptNumber(storeId, terminalId + "R");

        // 1. Validate each requested line and compute its proportional refund (single-pass).
        record LineRefund(int originalLineNo, String sku, String name, BigDecimal quantity,
                BigDecimal unitPrice, BigDecimal refundNet, BigDecimal refundTax,
                BigDecimal refundLineTotal) {
        }

        List<LineRefund> lineRefunds = new ArrayList<>();
        List<ReturnCompleted.ReturnedLine> returnedLines = new ArrayList<>();
        BigDecimal refundSubtotal = ZERO;
        BigDecimal refundTaxTotal = ZERO;
        BigDecimal refundGrandTotal = ZERO;

        for (ReturnCommand.ReturnLineRequest req : command.lines()) {
            SaleLine original = findOriginalLine(sale, req.lineNo());
            BigDecimal requested = req.quantity();
            if (requested == null || requested.signum() <= 0) {
                throw DomainException.validation("Return quantity must be positive for line "
                        + req.lineNo());
            }
            BigDecimal sold = original.getQuantity();
            BigDecimal already = returnLines.sumReturnedQuantity(sale.getId(), req.lineNo());
            if (already.add(requested).compareTo(sold) > 0) {
                throw DomainException.conflict("Line " + req.lineNo() + " return exceeds sold quantity"
                        + " (sold " + sold + ", already returned " + already + ", requested "
                        + requested + ")");
            }

            BigDecimal refundNet = proportion(original.getNetAmount(), requested, sold);
            BigDecimal refundTax = proportion(original.getTaxAmount(), requested, sold);
            BigDecimal refundLineTotal = refundNet.add(refundTax);
            refundSubtotal = refundSubtotal.add(refundNet);
            refundTaxTotal = refundTaxTotal.add(refundTax);
            refundGrandTotal = refundGrandTotal.add(refundLineTotal);

            lineRefunds.add(new LineRefund(original.getLineNo(), original.getSku(),
                    original.getName(), requested, original.getUnitPrice(), refundNet, refundTax,
                    refundLineTotal));
            returnedLines.add(new ReturnCompleted.ReturnedLine(original.getSku(), requested));
        }

        // 2. Construct the SalesReturn aggregate once with final totals.
        SalesReturn finalReturn = new SalesReturn(returnId, creditNoteNumber, sale.getId(), storeId,
                terminalId, managerUsername, location, currency, refundSubtotal, refundTaxTotal,
                refundGrandTotal, Instant.now());
        int lineNo = 1;
        for (LineRefund lr : lineRefunds) {
            finalReturn.addLine(new SalesReturnLine(Identifiers.newId(), finalReturn, lineNo++,
                    lr.originalLineNo(), lr.sku(), lr.name(), lr.quantity(), lr.unitPrice(),
                    lr.refundNet(), lr.refundTax(), lr.refundLineTotal(), currency));
        }

        // 3. Allocate the refund across the original tenders and execute each.
        List<PaymentView> originalPayments = payments.findBySale(sale.getId());
        BigDecimal originalGrand = sale.getGrandTotal().setScale(2, RoundingMode.HALF_UP);
        BigDecimal allocated = ZERO;
        BigDecimal cashRefundTotal = ZERO;
        for (int i = 0; i < originalPayments.size(); i++) {
            PaymentView op = originalPayments.get(i);
            BigDecimal portion = (i == originalPayments.size() - 1 || originalGrand.signum() == 0)
                    ? refundGrandTotal.subtract(allocated)
                    : refundGrandTotal.multiply(op.amount())
                            .divide(originalGrand, 2, RoundingMode.HALF_UP);
            allocated = allocated.add(portion);
            if (portion.signum() <= 0) {
                continue;
            }
            PaymentMethod method = PaymentMethod.valueOf(op.method());
            if (method == PaymentMethod.CASH) {
                payments.refundCash(returnId, currency, portion);
                cashRefundTotal = cashRefundTotal.add(portion);
            } else {
                payments.refundTerminalPayment(returnId, currency, portion, method,
                        returnId.toString());
            }
        }

        // 4. Persist the immutable return.
        returns.save(finalReturn);

        // 5. Publish the reversal event (recorded to the outbox for after-commit fan-out).
        events.publish(new ReturnCompleted(returnId, sale.getId(), finalReturn.getCreditNoteNumber(),
                terminalId, location, currency, refundGrandTotal, cashRefundTotal, returnedLines));

        // 6. Print the credit note (best-effort — never fails the return).
        printCreditNote(finalReturn);

        return toView(finalReturn);
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnView getReturn(UUID returnId) {
        SalesReturn r = returns.findById(returnId)
                .orElseThrow(() -> DomainException.notFound("No return " + returnId));
        return toView(r);
    }

    private Sale resolveSale(ReturnCommand command) {
        if (command.originalSaleId() != null) {
            return sales.findById(command.originalSaleId())
                    .orElseThrow(() -> DomainException.notFound("No sale " + command.originalSaleId()));
        }
        if (command.receiptNumber() != null && !command.receiptNumber().isBlank()) {
            return sales.findByReceiptNumber(command.receiptNumber())
                    .orElseThrow(() -> DomainException.notFound(
                            "No sale with receipt " + command.receiptNumber()));
        }
        throw DomainException.validation("A return must reference a sale id or receipt number");
    }

    private SaleLine findOriginalLine(Sale sale, int lineNo) {
        return sale.getLines().stream()
                .filter(l -> l.getLineNo() == lineNo)
                .findFirst()
                .orElseThrow(() -> DomainException.validation(
                        "Sale " + sale.getId() + " has no line " + lineNo));
    }

    private BigDecimal proportion(BigDecimal originalAmount, BigDecimal returnQty,
            BigDecimal soldQty) {
        return originalAmount.multiply(returnQty).divide(soldQty, 2, RoundingMode.HALF_UP);
    }

    private void printCreditNote(SalesReturn r) {
        try {
            List<ReceiptLineData> lines = r.getLines().stream()
                    .map(l -> new ReceiptLineData(l.getName(), l.getQuantity(), l.getUnitPrice(),
                            l.getLineTotal()))
                    .toList();
            List<ReceiptPaymentData> pays = payments.findByReturn(r.getId()).stream()
                    .map(p -> new ReceiptPaymentData(p.method(), p.amount(), p.amountTendered(),
                            p.changeDue(), p.maskedPan()))
                    .toList();
            receipts.print(new ReceiptData(r.getCreditNoteNumber(), "RETURN", r.getCreatedAt(), lines,
                    r.getRefundSubtotal(), r.getRefundTaxTotal(), r.getRefundGrandTotal(), pays,
                    r.getCurrencyCode()));
        } catch (RuntimeException ex) {
            log.warn("Credit-note print failed for return {} ({}) — return is recorded",
                    r.getId(), r.getCreditNoteNumber(), ex);
        }
    }

    private ReturnView toView(SalesReturn r) {
        List<SaleReturnLineView> lines = new ArrayList<>();
        for (SalesReturnLine l : r.getLines()) {
            lines.add(new SaleReturnLineView(l.getLineNo(), l.getOriginalLineNo(), l.getSku(),
                    l.getName(), l.getQuantity(), l.getUnitPrice(), l.getNetAmount(), l.getTaxAmount(),
                    l.getLineTotal(), l.getCurrencyCode()));
        }
        List<ReturnPaymentView> refunds = payments.findByReturn(r.getId()).stream()
                .map(p -> new ReturnPaymentView(p.method(), p.amount(), p.maskedPan()))
                .toList();
        return new ReturnView(r.getId(), r.getCreditNoteNumber(), r.getOriginalSaleId(),
                r.getStatus(), r.getCurrencyCode(), r.getRefundSubtotal(), r.getRefundTaxTotal(),
                r.getRefundGrandTotal(), r.getCreatedAt(), lines, refunds);
    }
}
