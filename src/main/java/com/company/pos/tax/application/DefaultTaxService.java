package com.company.pos.tax.application;

import com.company.pos.tax.api.TaxLineInput;
import com.company.pos.tax.api.TaxService;
import com.company.pos.tax.api.TaxedCart;
import com.company.pos.tax.api.TaxedLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DefaultTaxService implements TaxService {

    @Override
    public TaxedCart applyTax(List<TaxLineInput> lines, BigDecimal rate, boolean inclusive,
            String currencyCode) {
        List<TaxedLine> taxed = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (TaxLineInput line : lines) {
            BigDecimal extended = line.extendedPrice();
            BigDecimal net;
            BigDecimal tax;
            BigDecimal lineTotal;
            if (inclusive) {
                net = extended.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
                tax = extended.subtract(net);
                lineTotal = extended;
            } else {
                net = extended.setScale(2, RoundingMode.HALF_UP);
                tax = extended.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                lineTotal = net.add(tax);
            }
            taxed.add(new TaxedLine(line.sku(), line.name(), line.quantity(), line.unitPrice(),
                    net, tax, lineTotal, line.currencyCode()));
            subtotal = subtotal.add(net);
            taxTotal = taxTotal.add(tax);
            grandTotal = grandTotal.add(lineTotal);
        }

        return new TaxedCart(taxed,
                subtotal.setScale(2, RoundingMode.HALF_UP),
                taxTotal.setScale(2, RoundingMode.HALF_UP),
                grandTotal.setScale(2, RoundingMode.HALF_UP),
                currencyCode, rate, inclusive);
    }
}
