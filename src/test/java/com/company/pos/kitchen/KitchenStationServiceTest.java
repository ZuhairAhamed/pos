package com.company.pos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.support.DatabaseCleaner;
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
class KitchenStationServiceTest {

    @Autowired KitchenService kitchen;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void assignsAndResolvesStation() {
        kitchen.assignSku("BURGER", "Grill");
        assertThat(kitchen.stationFor("BURGER")).isEqualTo("Grill");
    }

    @Test
    void reassignUpdatesInPlace() {
        kitchen.assignSku("BURGER", "Grill");
        kitchen.assignSku("BURGER", "Flat-top");
        assertThat(kitchen.stationFor("BURGER")).isEqualTo("Flat-top");
        assertThat(kitchen.listAssignments()).hasSize(1);
    }

    @Test
    void unassignedSkuFallsBackToDefaultStation() {
        // KITCHEN_DEFAULT_STATION default is "Kitchen"
        assertThat(kitchen.stationFor("UNMAPPED")).isEqualTo("Kitchen");
    }

    @Test
    void unassignRemovesTheMapping() {
        kitchen.assignSku("FRIES", "Fryer");
        kitchen.unassignSku("FRIES");
        assertThat(kitchen.listAssignments()).isEmpty();
        assertThat(kitchen.stationFor("FRIES")).isEqualTo("Kitchen");
    }

    @Test
    void listReturnsAllAssignments() {
        kitchen.assignSku("BURGER", "Grill");
        kitchen.assignSku("FRIES", "Fryer");
        assertThat(kitchen.listAssignments()).hasSize(2);
    }
}
