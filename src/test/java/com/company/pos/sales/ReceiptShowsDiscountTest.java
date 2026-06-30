package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ReceiptShowsDiscountTest {

    @Autowired
    com.company.pos.sales.api.SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPrinter printer;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void receiptShowsTheDiscountTotal() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null), "cashier", false);

        List<PrintLine> receipt = printer.lastReceipt();
        assertThat(receipt).isNotEmpty();
        assertThat(receipt.stream().anyMatch(l -> l.text().contains("Discount"))).isTrue();
    }

    /**
     * 2 × COLA @ 4.50 = gross 9.00; 10 % LOYALTY discount = 0.90;
     * net 8.10 × 15 % VAT = 1.215 → rounded 1.22 (half-up); TOTAL 9.32.
     *
     * The tape must foot: Subtotal (9.00) − Discount (0.90) + Tax (1.22) = TOTAL (9.32).
     */
    @Test
    void receiptTotalsFootAndBothDiscountSurfacesAppear() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null), "cashier", false);

        List<PrintLine> receipt = printer.lastReceipt();
        List<String> lines = receipt.stream().map(PrintLine::text).toList();

        // --- per-line discount surface ---
        // The indented line-level discount: "  Discount: -<amount>"
        assertThat(lines)
                .anyMatch(l -> l.startsWith("  Discount: -") || l.contains("  Discount: -"));

        // --- totals section: locate the four key lines ---
        String subtotalLine = lines.stream()
                .filter(l -> l.startsWith("Subtotal:")).findFirst()
                .orElseThrow(() -> new AssertionError("No Subtotal line found; receipt: " + lines));
        String discountLine = lines.stream()
                .filter(l -> l.startsWith("Discount: -")).findFirst()
                .orElseThrow(() -> new AssertionError("No totals Discount line found; receipt: " + lines));
        String taxLine = lines.stream()
                .filter(l -> l.startsWith("Tax:")).findFirst()
                .orElseThrow(() -> new AssertionError("No Tax line found; receipt: " + lines));
        String totalLine = lines.stream()
                .filter(l -> l.startsWith("TOTAL:")).findFirst()
                .orElseThrow(() -> new AssertionError("No TOTAL line found; receipt: " + lines));

        BigDecimal subtotal = extractAmount(subtotalLine);
        BigDecimal discount = extractAmount(discountLine);
        BigDecimal tax = extractAmount(taxLine);
        BigDecimal total = extractAmount(totalLine);

        // Subtotal must be the PRE-DISCOUNT gross (9.00), not the net (8.10).
        assertThat(subtotal).as("Subtotal should be pre-discount gross 9.00")
                .isEqualByComparingTo("9.00");
        assertThat(discount).as("Discount should be 0.90")
                .isEqualByComparingTo("0.90");
        assertThat(tax).as("Tax should be 1.22")
                .isEqualByComparingTo("1.22");
        assertThat(total).as("TOTAL should be 9.32")
                .isEqualByComparingTo("9.32");

        // Arithmetic must foot: gross − discount + tax == total
        BigDecimal computed = subtotal.subtract(discount).add(tax);
        assertThat(computed).as("tape must foot: subtotal - discount + tax == total")
                .isEqualByComparingTo(total);
    }

    /**
     * Extracts the rightmost decimal number from a receipt line, stripping any
     * currency code/symbol so the assertion is locale-format-agnostic.
     * Example inputs: "Subtotal: SAR 9.00", "Discount: -0.90", "TOTAL:    9.32"
     */
    private static BigDecimal extractAmount(String line) {
        // Match the last occurrence of digits with optional decimal point
        Pattern p = Pattern.compile("[\\d]+(?:[.,][\\d]+)*");
        Matcher m = p.matcher(line);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        if (last == null) {
            throw new AssertionError("No numeric value found in line: " + line);
        }
        // Normalise: replace comma-as-decimal-separator with dot (e.g. German locale)
        // but only when it looks like a decimal comma (one comma, <= 2 digits after)
        String normalised = last;
        if (normalised.contains(",") && !normalised.contains(".")) {
            // count digits after the comma
            int commaIdx = normalised.lastIndexOf(',');
            if (normalised.length() - commaIdx - 1 <= 2) {
                normalised = normalised.replace(",", ".");
            } else {
                // thousands separator only — remove it
                normalised = normalised.replace(",", "");
            }
        } else {
            // remove thousand-separator commas (commas followed by exactly 3 digits)
            normalised = normalised.replaceAll(",(?=\\d{3}(?:[^\\d]|$))", "");
        }
        return new BigDecimal(normalised);
    }
}
