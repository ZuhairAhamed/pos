package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.dto.SettingView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Terminal-side presentation of the server's settings: which keys to show, their friendly labels,
 *  their category grouping, and the live-critical flag. Server keys not in this catalog are hidden. */
public final class SettingsCatalog {

    private SettingsCatalog() {
    }

    public record Entry(String category, String label, boolean liveCritical) {
    }

    public record Row(SettingView view, String label, boolean liveCritical) {
    }

    public record Group(String category, List<Row> rows) {
    }

    private static final Map<String, Entry> CATALOG = catalog();

    /** Curated keys in display order (insertion order = category order = within-category order). */
    private static Map<String, Entry> catalog() {
        LinkedHashMap<String, Entry> m = new LinkedHashMap<>();
        m.put("STORE_NAME", new Entry("Store info", "Store name", false));
        m.put("CURRENCY_CODE", new Entry("Store info", "Currency code", true));
        m.put("LOCALE", new Entry("Store info", "Locale", false));
        m.put("TAX_INCLUSIVE", new Entry("Tax", "Prices include tax", true));
        m.put("VAT_RATE", new Entry("Tax", "VAT rate (fraction, e.g. 0.15)", true));
        m.put("SERVICE_CHARGE_ENABLED", new Entry("Service charge", "Service charge enabled", true));
        m.put("SERVICE_CHARGE_PERCENT", new Entry("Service charge", "Service charge %", true));
        m.put("SERVICE_CHARGE_LABEL", new Entry("Service charge", "Service charge label", false));
        m.put("DISCOUNT_CASHIER_MAX_PERCENT", new Entry("Discounts", "Cashier max discount %", true));
        m.put("DISCOUNT_CASHIER_MAX_AMOUNT", new Entry("Discounts", "Cashier max discount amount", true));
        m.put("DISCOUNT_REASON_CODES", new Entry("Discounts", "Discount reason codes (CSV)", false));
        m.put("DINING_TABLE_DEFAULT_SEATS", new Entry("Operations", "Default table seats", false));
        m.put("KITCHEN_DEFAULT_STATION", new Entry("Operations", "Default kitchen station", false));
        m.put("DASHBOARD_REVENUE_WINDOW_DAYS", new Entry("Operations", "Dashboard revenue window (days)", false));
        return m;
    }

    public static boolean isLiveCritical(String name) {
        Entry e = CATALOG.get(name);
        return e != null && e.liveCritical();
    }

    /** Group the server settings into ordered category sections, dropping any key not in the catalog. */
    public static List<Group> group(List<SettingView> serverList) {
        Map<String, SettingView> byName = new LinkedHashMap<>();
        if (serverList != null) {
            for (SettingView v : serverList) {
                byName.put(v.name(), v);
            }
        }
        LinkedHashMap<String, List<Row>> buckets = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : CATALOG.entrySet()) {
            SettingView v = byName.get(e.getKey());
            if (v == null) {
                continue;
            }
            buckets.computeIfAbsent(e.getValue().category(), k -> new ArrayList<>())
                    .add(new Row(v, e.getValue().label(), e.getValue().liveCritical()));
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<Row>> b : buckets.entrySet()) {
            groups.add(new Group(b.getKey(), b.getValue()));
        }
        return groups;
    }
}
