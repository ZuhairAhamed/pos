package com.company.pos.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dashboard.api.DashboardService;
import com.company.pos.dashboard.api.DashboardSnapshot;
import com.company.pos.dashboard.api.LowStockTile;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
@TestPropertySource(properties = "pos.inventory.reorder-level=100")
class DashboardServiceTest {

    @Autowired DashboardService dashboard;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired InventorySync inventorySync;
    @Autowired ShiftService shifts;
    @Autowired ConfigurationService configService;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;
    @Autowired ReturnService returns;
    @Autowired InMemoryPaymentTerminal terminal;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        terminal.setApprove(true);
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private void sellTwoColas() {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2")); // 2 x 4.50 = 9.00 net, tax 1.35, grand 10.35
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");
    }

    @Test
    void snapshotAggregatesEveryTile() {
        shifts.openShift("T01", new BigDecimal("100.00"), "alice");
        sellTwoColas();

        DashboardSnapshot snap = dashboard.snapshot();

        assertThat(snap.asOfDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(snap.currencyCode()).isEqualTo("SAR");

        // Today's sales
        assertThat(snap.todaysSales().saleCount()).isEqualTo(1);
        assertThat(snap.todaysSales().grossSales()).isEqualByComparingTo("10.35");

        // Revenue: today's net; window (default 7) also contains the same sale
        assertThat(snap.revenue().windowDays()).isEqualTo(7);
        assertThat(snap.revenue().today()).isEqualByComparingTo("10.35");
        assertThat(snap.revenue().window()).isEqualByComparingTo("10.35");

        // Best sellers
        assertThat(snap.bestSellers()).extracting(l -> l.sku()).contains("COLA");

        // Low stock, name-enriched from product catalog (5 on hand < reorder 100)
        assertThat(snap.lowStock()).extracting(LowStockTile::sku).containsExactly("COLA");
        assertThat(snap.lowStock().get(0).name()).isEqualTo("Cola Can");

        // Open shifts + derived active cashiers
        assertThat(snap.openShifts()).extracting(s -> s.terminalId()).containsExactly("T01");
        assertThat(snap.activeCashiers()).containsExactly("alice");
    }

    @Test
    void lowStockFallsBackToSkuWhenProductMissing() {
        // Stock row exists for a SKU with no product record -> name falls back to sku
        fake.addStockLevel(new ErpStockLevel("GHOST", "MAIN", new BigDecimal("1"), 2));
        inventorySync.sync();

        List<LowStockTile> low = dashboard.lowStock();

        assertThat(low).anySatisfy(t -> {
            assertThat(t.sku()).isEqualTo("GHOST");
            assertThat(t.name()).isEqualTo("GHOST");
        });
    }

    @Test
    void revenueWindowHonoursConfigOverride() {
        // Override the window and confirm it plumbs through to the response.
        configService.put(SettingKey.DASHBOARD_REVENUE_WINDOW_DAYS, "3");
        sellTwoColas();

        assertThat(dashboard.revenue().windowDays()).isEqualTo(3);
        // Window [today-2, today] still contains today's sale, so window net == today net.
        assertThat(dashboard.revenue().window()).isEqualByComparingTo("10.35");
        assertThat(dashboard.revenue().today()).isEqualByComparingTo("10.35");
    }

    @Test
    void revenueTodayIsNetOfRefunds() {
        // Sell 2x COLA (grand total 10.35), then process a full return.
        // net = gross - refund = 10.35 - 10.35 = 0.00
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2"));
        SaleView sold = salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");
        assertThat(sold.grandTotal()).isEqualByComparingTo("10.35");

        // Full return of line 1, qty 2
        returns.processReturn(
                new ReturnCommand(sold.id(), null,
                        List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("2")))),
                "manager");

        // revenue().today() must be netSales (0.00), NOT grossSales (10.35)
        assertThat(dashboard.revenue().today()).isEqualByComparingTo("0.00");
        assertThat(dashboard.revenue().today()).isNotEqualByComparingTo("10.35");
    }
}
