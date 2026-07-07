package com.company.pos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryKitchenPrinter;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class KitchenRoutingTest {

    @Autowired DiningService dining;
    @Autowired KitchenService kitchen;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired InMemoryKitchenPrinter printer;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        printer.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("FRIES", "Fries", "FOOD", "Food", "bcFRIES",
                "EA", new BigDecimal("12.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("WATER", "Water", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();
        kitchen.assignSku("BURGER", "Grill");
        kitchen.assignSku("FRIES", "Fryer");
        // WATER intentionally unmapped -> default station "Kitchen"
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
        printer.clear();
    }

    private UUID openOrderOnFreshTable() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    private static boolean anyTicketContains(List<List<PrintLine>> tickets, String text) {
        return tickets.stream().anyMatch(t -> t.stream().anyMatch(l -> l.text().contains(text)));
    }

    @Test
    void firingPrintsOneTicketPerStation() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), "no onions", CourseTag.MAIN), "alice");
        dining.addLine(orderId, new AddLineCommand("FRIES", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.addLine(orderId, new AddLineCommand("WATER", new BigDecimal("1"), null, CourseTag.DRINK), "alice");

        dining.fireOrder(orderId, "alice");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<List<PrintLine>> tickets = printer.tickets();
            assertThat(tickets).hasSize(3); // Grill, Fryer, Kitchen (default)
            assertThat(anyTicketContains(tickets, "Grill")).isTrue();
            assertThat(anyTicketContains(tickets, "Fryer")).isTrue();
            assertThat(anyTicketContains(tickets, "Kitchen")).isTrue();
            assertThat(anyTicketContains(tickets, "Beef Burger")).isTrue();
            assertThat(anyTicketContains(tickets, "no onions")).isTrue();
        });
    }

    @Test
    void secondFirePrintsOnlyTheNewlyAddedLine() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(printer.tickets()).hasSize(1));
        printer.clear();

        dining.addLine(orderId, new AddLineCommand("FRIES", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<List<PrintLine>> tickets = printer.tickets();
            assertThat(tickets).hasSize(1); // only the Fryer ticket for the new fries line
            assertThat(anyTicketContains(tickets, "Fries")).isTrue();
            assertThat(anyTicketContains(tickets, "Beef Burger")).isFalse();
        });
    }
}
