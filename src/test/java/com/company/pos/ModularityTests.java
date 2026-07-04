package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(PosApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    @Test
    void detectsTheExpectedPhaseZeroModules() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("common", "database", "configuration", "device");
    }

    @Test
    void detectsTheCheckoutCoreModules() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("cart", "pricing", "tax", "payment", "receipt", "sales");
    }

    @Test
    void detectsTheSyncModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("sync");
    }

    @Test
    void detectsTheNotificationModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("notification");
    }

    @Test
    void detectsTheDashboardModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("dashboard", "reporting");
    }

    @Test
    void detectsTheDiningModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("dining");
    }

    @Test
    void detectsTheMenuModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("menu");
    }
}
