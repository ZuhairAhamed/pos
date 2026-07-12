package com.company.pos.integration.erp;

import com.company.pos.integration.api.ErpProduct;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-only seeder: loads a small sample catalogue into the in-memory {@link FakeErpClient} so a
 * fresh dev run has products to sell. The products enter the store catalog on the next ERP
 * down-sync ({@code POST /sync/erp}). Active only under the {@code dev} profile. Module-local
 * (touches only this module's fake client), so it does not affect {@code ModularityTests}.
 */
@Component
@Profile("dev")
class DevCatalogueSeeder implements ApplicationRunner {

    private static final System.Logger LOG = System.getLogger(DevCatalogueSeeder.class.getName());

    private final FakeErpClient fake;

    DevCatalogueSeeder(FakeErpClient fake) {
        this.fake = fake;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ErpProduct> catalogue = List.of(
                new ErpProduct("COLA", "Cola", "BEV", "Beverages", "bcCOLA", "EA",
                        new BigDecimal("4.50"), "SAR", 1, true),
                new ErpProduct("COFFEE", "Coffee", "BEV", "Beverages", "bcCOFFEE", "EA",
                        new BigDecimal("6.00"), "SAR", 1, true),
                new ErpProduct("BURGER", "Cheeseburger", "FOOD", "Mains", "bcBURGER", "EA",
                        new BigDecimal("22.00"), "SAR", 1, true),
                new ErpProduct("FRIES", "Fries", "FOOD", "Mains", "bcFRIES", "EA",
                        new BigDecimal("8.00"), "SAR", 1, true),
                new ErpProduct("PIZZA", "Margherita Pizza", "FOOD", "Mains", "bcPIZZA", "EA",
                        new BigDecimal("30.00"), "SAR", 1, true));
        for (ErpProduct p : catalogue) {
            fake.addProduct(p);
        }
        LOG.log(System.Logger.Level.INFO,
                "[dev-seed] seeded " + catalogue.size()
                        + " products / 2 categories into FakeErpClient (run POST /sync/erp to pull them in)");
    }
}
