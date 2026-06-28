package com.company.pos.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.tax.api.TaxLineInput;
import com.company.pos.tax.api.TaxedCart;
import com.company.pos.tax.application.DefaultTaxService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaxServiceTest {

    private final DefaultTaxService tax = new DefaultTaxService();

    private TaxLineInput line(String extended) {
        return new TaxLineInput("COLA", "Cola Can", new BigDecimal("2"),
                new BigDecimal("4.5000"), new BigDecimal(extended), "SAR");
    }

    @Test
    void exclusiveAddsTaxOnTop() {
        TaxedCart cart = tax.applyTax(List.of(line("9.00")), new BigDecimal("0.15"), false, "SAR");

        assertThat(cart.subtotal()).isEqualByComparingTo("9.00");
        assertThat(cart.taxTotal()).isEqualByComparingTo("1.35");   // 9.00 * 0.15
        assertThat(cart.grandTotal()).isEqualByComparingTo("10.35");
        assertThat(cart.lines().get(0).netAmount()).isEqualByComparingTo("9.00");
        assertThat(cart.lines().get(0).taxAmount()).isEqualByComparingTo("1.35");
        assertThat(cart.lines().get(0).lineTotal()).isEqualByComparingTo("10.35");
    }

    @Test
    void inclusiveExtractsTaxFromPrice() {
        // 11.50 inclusive @ 15%: net = 11.50 / 1.15 = 10.00, tax = 1.50
        TaxedCart cart = tax.applyTax(List.of(line("11.50")), new BigDecimal("0.15"), true, "SAR");

        assertThat(cart.subtotal()).isEqualByComparingTo("10.00");
        assertThat(cart.taxTotal()).isEqualByComparingTo("1.50");
        assertThat(cart.grandTotal()).isEqualByComparingTo("11.50");
    }

    @Test
    void zeroRateProducesNoTax() {
        TaxedCart cart = tax.applyTax(List.of(line("9.00")), BigDecimal.ZERO, false, "SAR");

        assertThat(cart.taxTotal()).isEqualByComparingTo("0.00");
        assertThat(cart.grandTotal()).isEqualByComparingTo("9.00");
    }

    @Test
    void inclusiveMultiLineTotalsReconcile() {
        // 3-line inclusive cart: grandTotal = sum of extendedPrices; subtotal + taxTotal = grandTotal
        List<TaxLineInput> lines = List.of(
                new TaxLineInput("A", "Item A", new BigDecimal("1"), new BigDecimal("11.50"), new BigDecimal("11.50"), "SAR"),
                new TaxLineInput("B", "Item B", new BigDecimal("1"), new BigDecimal("5.55"), new BigDecimal("5.55"), "SAR"),
                new TaxLineInput("C", "Item C", new BigDecimal("1"), new BigDecimal("3.33"), new BigDecimal("3.33"), "SAR")
        );
        TaxedCart cart = tax.applyTax(lines, new BigDecimal("0.15"), true, "SAR");

        assertThat(cart.grandTotal()).isEqualByComparingTo("20.38");
        assertThat(cart.subtotal().add(cart.taxTotal())).isEqualByComparingTo(cart.grandTotal());
    }
}
