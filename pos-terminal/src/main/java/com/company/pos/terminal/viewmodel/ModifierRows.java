package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;

/** Pure display formatters for the modifier builder. */
public final class ModifierRows {

    private ModifierRows() {
    }

    /** "min–max" selection range, e.g. "1–3". */
    public static String selectionsLabel(int min, int max) {
        return min + "–" + max;
    }

    /** Signed 2-dp price delta, e.g. "+2.50", "-1.00", "0.00". */
    public static String priceDeltaLabel(BigDecimal delta) {
        if (delta == null) {
            return "";
        }
        BigDecimal d = delta.setScale(2, java.math.RoundingMode.HALF_UP);
        return d.signum() > 0 ? "+" + d.toPlainString() : d.toPlainString();
    }

    /** "sku — name" if the sku is a known product, else the bare sku. */
    public static String skuLabel(String sku, List<ProductView> products) {
        if (products != null) {
            for (ProductView p : products) {
                if (p.sku().equals(sku)) {
                    return sku + " — " + p.name();
                }
            }
        }
        return sku;
    }
}
