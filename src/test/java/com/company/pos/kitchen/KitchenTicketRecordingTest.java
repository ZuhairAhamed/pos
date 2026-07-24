package com.company.pos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.kitchen.api.KitchenTicketState;
import com.company.pos.kitchen.infrastructure.KitchenTicketRepository;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
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
class KitchenTicketRecordingTest {

    @Autowired DiningService dining;
    @Autowired KitchenService kitchen;
    @Autowired KitchenTicketRepository repo;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("COLA", "Cola", "DRINK", "Drink", "bcCOLA",
                "EA", new BigDecimal("6.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void fireCreatesOneTicketPerStation() {
        kitchen.assignSku("BURGER", "Grill");
        kitchen.assignSku("COLA", "Bar");
        UUID tableId = dining.registerTable(new RegisterTableCommand("KT1", 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.addLine(orderId, new AddLineCommand("COLA", new BigDecimal("2"), null, CourseTag.DRINK), "alice");

        dining.fireOrder(orderId, "alice");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repo.findByOrderId(orderId)).hasSize(2);
            assertThat(repo.findByOrderId(orderId))
                    .allMatch(t -> t.getState() == KitchenTicketState.FIRED)
                    .anyMatch(t -> t.getStation().equals("Grill"))
                    .anyMatch(t -> t.getStation().equals("Bar"));
        });
    }
}
