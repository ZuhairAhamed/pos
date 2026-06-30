package com.company.pos.sales.application;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import com.company.pos.pricing.api.PricedLine;
import com.company.pos.pricing.api.PricingInput;
import com.company.pos.pricing.api.PricingService;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptPaymentData;
import com.company.pos.receipt.api.ReceiptService;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.sales.api.SaleLineView;
import com.company.pos.sales.api.SalePaymentView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.sales.domain.Sale;
import com.company.pos.sales.domain.SaleLine;
import com.company.pos.sales.infrastructure.SaleRepository;
import com.company.pos.tax.api.TaxLineInput;
import com.company.pos.tax.api.TaxService;
import com.company.pos.tax.api.TaxedCart;
import com.company.pos.tax.api.TaxedLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultSalesService implements SalesService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSalesService.class);

    private final CartService carts;
    private final PricingService pricing;
    private final TaxService tax;
    private final PaymentService payments;
    private final ReceiptService receipts;
    private final ConfigurationService config;
    private final SaleRepository sales;
    private final ReceiptNumbering numbering;
    private final DomainEvents events;
    private final DiscountCalculator discounts;

    DefaultSalesService(CartService carts, PricingService pricing, TaxService tax,
            PaymentService payments, ReceiptService receipts, ConfigurationService config,
            SaleRepository sales, ReceiptNumbering numbering, DomainEvents events,
            DiscountCalculator discounts) {
        this.carts = carts;
        this.pricing = pricing;
        this.tax = tax;
        this.payments = payments;
        this.receipts = receipts;
        this.config = config;
        this.sales = sales;
        this.numbering = numbering;
        this.events = events;
        this.discounts = discounts;
    }

    @Override
    public SaleView checkout(CheckoutCommand command, String cashierUsername) {
        return checkout(command, cashierUsername, false);
    }

    @Override
    public SaleView checkout(CheckoutCommand command, String cashierUsername, boolean callerIsManager) {
        CartView cart = carts.getCart(command.cartId());
        if (!"OPEN".equals(cart.status())) {
            throw DomainException.conflict("Cart " + command.cartId() + " is not open");
        }
        if (cart.lines().isEmpty()) {
            throw DomainException.validation("Cannot checkout an empty cart");
        }
        if (command.tenders() == null || command.tenders().isEmpty()) {
            throw DomainException.validation("At least one tender is required");
        }

        // 1. Price the lines
        List<PricingInput> pricingInputs = cart.lines().stream()
                .map(l -> new PricingInput(l.sku(), l.name(), l.quantity(), l.unitPrice(), l.currencyCode()))
                .toList();
        List<PricedLine> priced = pricing.price(pricingInputs);

        // 2. Apply manual discounts (line, then transaction) BEFORE tax.
        BigDecimal maxPct = new BigDecimal(config.getString(SettingKey.DISCOUNT_CASHIER_MAX_PERCENT));
        BigDecimal maxAmt = new BigDecimal(config.getString(SettingKey.DISCOUNT_CASHIER_MAX_AMOUNT));
        Set<String> reasonCodes = Arrays.stream(
                        config.getString(SettingKey.DISCOUNT_REASON_CODES).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        DiscountResult disc = discounts.apply(priced, command.lineDiscounts(),
                command.transactionDiscount(), callerIsManager, maxPct, maxAmt, reasonCodes);

        // 3. Apply tax on the discounted extended amounts
        String currency = cart.currencyCode() != null
                ? cart.currencyCode()
                : config.getString(SettingKey.CURRENCY_CODE);
        BigDecimal rate = new BigDecimal(config.getString(SettingKey.VAT_RATE));
        boolean inclusive = Boolean.parseBoolean(config.getString(SettingKey.TAX_INCLUSIVE));
        List<TaxLineInput> taxInputs = disc.lines().stream()
                .map(d -> new TaxLineInput(d.sku(), d.name(), d.quantity(), d.unitPrice(),
                        d.discountedExtended(), d.currencyCode()))
                .toList();
        TaxedCart taxed = tax.applyTax(taxInputs, rate, inclusive, currency);
        BigDecimal grandTotal = taxed.grandTotal().setScale(2, RoundingMode.HALF_UP);

        // 3. Take the tenders (cash computes change; card/wallet go through the terminal).
        UUID saleId = Identifiers.newId();
        List<PaymentView> recorded = new ArrayList<>();
        BigDecimal applied = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (TenderInput tender : command.tenders()) {
            PaymentMethod method = tender.method();
            if (method == PaymentMethod.CASH) {
                BigDecimal amount = tender.amount() != null
                        ? tender.amount().setScale(2, RoundingMode.HALF_UP)
                        : grandTotal.subtract(applied);
                if (amount.signum() <= 0) {
                    throw DomainException.validation("Cash tender amount must be positive");
                }
                if (tender.tendered() == null) {
                    throw DomainException.validation("Cash tender requires a tendered amount");
                }
                PaymentView pv = payments.recordCash(saleId, currency, amount, tender.tendered());
                applied = applied.add(pv.amount());
                recorded.add(pv);
            } else {
                if (tender.amount() == null) {
                    throw DomainException.validation(method + " tender requires an amount");
                }
                PaymentView pv = payments.recordTerminalPayment(saleId, currency, tender.amount(),
                        method, saleId.toString());
                applied = applied.add(pv.amount());
                recorded.add(pv);
            }
        }
        if (applied.compareTo(grandTotal) != 0) {
            throw DomainException.validation(
                    "Tenders " + applied + " do not match the total " + grandTotal);
        }

        // 4. Persist the immutable sale
        String storeId = config.getString(SettingKey.STORE_ID);
        String terminalId = config.getString(SettingKey.TERMINAL_ID);
        String location = config.getString(SettingKey.INVENTORY_LOCATION);
        String receiptNumber = numbering.nextReceiptNumber(storeId, terminalId);
        Instant now = Instant.now();
        Sale sale = new Sale(saleId, receiptNumber, storeId, terminalId, cashierUsername,
                location, currency, taxed.subtotal(), taxed.taxTotal(), taxed.grandTotal(), now,
                disc.txnDiscountAmount(),
                disc.txnDiscountType() == null ? null : disc.txnDiscountType().name(),
                disc.txnDiscountReason(), disc.discountTotal());
        int lineNo = 1;
        for (int i = 0; i < taxed.lines().size(); i++) {
            TaxedLine t = taxed.lines().get(i);
            DiscountedLine d = disc.lines().get(i);
            sale.addLine(new SaleLine(Identifiers.newId(), sale, lineNo++, t.sku(), t.name(),
                    t.quantity(), t.unitPrice(), t.netAmount(), t.taxAmount(), t.lineTotal(),
                    t.currencyCode(), d.grossAmount(), d.lineDiscountAmount(),
                    d.lineDiscountType() == null ? null : d.lineDiscountType().name(),
                    d.lineDiscountReason()));
        }
        sales.save(sale);

        // 5. Close the cart
        carts.close(command.cartId());

        // 6. Publish SaleCompleted (in-process; inventory + cashdrawer subscribe synchronously)
        List<SaleCompleted.SoldLine> soldLines = taxed.lines().stream()
                .map(t -> new SaleCompleted.SoldLine(t.sku(), t.quantity()))
                .toList();
        BigDecimal cashTotal = recorded.stream()
                .filter(p -> PaymentMethod.CASH.name().equals(p.method()))
                .map(com.company.pos.payment.api.PaymentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        events.publish(new SaleCompleted(saleId, receiptNumber, terminalId, location, currency,
                taxed.grandTotal(), cashTotal, soldLines));

        // 7. Print the receipt (best-effort — never fails the sale)
        printReceipt(sale, recorded);

        return toView(sale, recorded);
    }

    @Override
    @Transactional(readOnly = true)
    public SaleView getSale(UUID saleId) {
        Sale sale = sales.findById(saleId)
                .orElseThrow(() -> DomainException.notFound("No sale " + saleId));
        return toView(sale, payments.findBySale(saleId));
    }

    @Override
    @Transactional(readOnly = true)
    public void reprint(UUID saleId) {
        Sale sale = sales.findById(saleId)
                .orElseThrow(() -> DomainException.notFound("No sale " + saleId));
        printReceipt(sale, payments.findBySale(saleId));
    }

    private void printReceipt(Sale sale, List<PaymentView> salePayments) {
        try {
            List<ReceiptLineData> lines = sale.getLines().stream()
                    .map(l -> new ReceiptLineData(l.getName(), l.getQuantity(), l.getUnitPrice(),
                            l.getLineTotal()))
                    .toList();
            List<ReceiptPaymentData> pays = salePayments.stream()
                    .map(p -> new ReceiptPaymentData(p.method(), p.amount(), p.amountTendered(),
                            p.changeDue(), p.maskedPan()))
                    .toList();
            receipts.print(new ReceiptData(sale.getReceiptNumber(), sale.getCashierUsername(),
                    sale.getCreatedAt(), lines, sale.getSubtotal(), sale.getTaxTotal(),
                    sale.getGrandTotal(), pays, sale.getCurrencyCode()));
        } catch (RuntimeException ex) {
            log.warn("Receipt print failed for sale {} ({}) — sale is recorded; reprint available",
                    sale.getId(), sale.getReceiptNumber(), ex);
        }
    }

    private SaleView toView(Sale sale, List<PaymentView> salePayments) {
        List<SaleLineView> lines = new ArrayList<>();
        for (SaleLine l : sale.getLines()) {
            lines.add(new SaleLineView(l.getLineNo(), l.getSku(), l.getName(), l.getQuantity(),
                    l.getUnitPrice(), l.getNetAmount(), l.getTaxAmount(), l.getLineTotal(),
                    l.getCurrencyCode(), l.getGrossAmount(), l.getLineDiscountAmount(),
                    l.getLineDiscountType(), l.getLineDiscountReason()));
        }
        List<SalePaymentView> paymentViews = salePayments.stream()
                .map(p -> new SalePaymentView(p.method(), p.amount(), p.amountTendered(),
                        p.changeDue(), p.maskedPan()))
                .toList();
        return new SaleView(sale.getId(), sale.getReceiptNumber(), sale.getStatus(),
                sale.getCurrencyCode(), sale.getSubtotal(), sale.getTaxTotal(), sale.getGrandTotal(),
                sale.getCreatedAt(), lines, paymentViews, sale.getDiscountTotal(),
                sale.getTxnDiscountAmount(), sale.getTxnDiscountType(), sale.getTxnDiscountReason());
    }
}
