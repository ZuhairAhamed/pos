package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.dto.SettingView;
import com.company.pos.terminal.viewmodel.SettingsCatalog.Group;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsCatalogTest {

    private SettingView v(String name) {
        return new SettingView(name, name.toLowerCase(), "x", "x", "STRING");
    }

    private List<SettingView> fullServerList() {
        return List.of(
                v("STORE_NAME"), v("CURRENCY_CODE"), v("LOCALE"),
                v("TAX_INCLUSIVE"), v("VAT_RATE"),
                v("SERVICE_CHARGE_ENABLED"), v("SERVICE_CHARGE_PERCENT"), v("SERVICE_CHARGE_LABEL"),
                v("DISCOUNT_CASHIER_MAX_PERCENT"), v("DISCOUNT_CASHIER_MAX_AMOUNT"), v("DISCOUNT_REASON_CODES"),
                v("DINING_TABLE_DEFAULT_SEATS"), v("KITCHEN_DEFAULT_STATION"), v("DASHBOARD_REVENUE_WINDOW_DAYS"),
                // hidden keys the server also returns:
                v("STORE_ID"), v("TERMINAL_ID"), v("RECEIPT_PRINTER_PORT"), v("INVENTORY_LOCATION"));
    }

    @Test
    void groupsInFixedCategoryOrder() {
        List<Group> groups = SettingsCatalog.group(fullServerList());
        assertEquals(List.of("Store info", "Tax", "Service charge", "Discounts", "Operations"),
                groups.stream().map(Group::category).toList());
        assertEquals(3, groups.get(0).rows().size());   // Store info
    }

    @Test
    void hiddenKeysAreDropped() {
        boolean anyHidden = SettingsCatalog.group(fullServerList()).stream()
                .flatMap(g -> g.rows().stream())
                .anyMatch(r -> r.view().name().equals("STORE_ID")
                        || r.view().name().equals("TERMINAL_ID")
                        || r.view().name().equals("RECEIPT_PRINTER_PORT")
                        || r.view().name().equals("INVENTORY_LOCATION"));
        assertFalse(anyHidden);
    }

    @Test
    void liveCriticalFlags() {
        assertTrue(SettingsCatalog.isLiveCritical("VAT_RATE"));
        assertFalse(SettingsCatalog.isLiveCritical("STORE_NAME"));
        assertFalse(SettingsCatalog.isLiveCritical("STORE_ID"));   // hidden → not critical
    }
}
