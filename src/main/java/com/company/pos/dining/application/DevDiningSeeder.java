package com.company.pos.dining.application;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.RegisterTableCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-only seeder: registers a small dining floor so a freshly started backend is demoable.
 * There is no table-registration UI in the terminal, so without this a fresh dev run has an
 * empty floor. Seeds six dine-in tables (T1..T6) and three counter tables (Counter 1..3);
 * counter labels use the same prefix the terminal defaults to ({@code "Counter "}), so the
 * terminal buckets them as takeaway stations. Active only under the {@code dev} profile;
 * idempotent (skips when any table already exists). Module-local (touches only this module's
 * api facade), so it does not affect {@code ModularityTests}.
 */
@Component
@Profile("dev")
class DevDiningSeeder implements ApplicationRunner {

    private static final System.Logger LOG = System.getLogger(DevDiningSeeder.class.getName());

    private final DiningService dining;

    DevDiningSeeder(DiningService dining) {
        this.dining = dining;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!dining.listTables().isEmpty()) {
            return;
        }
        for (int i = 1; i <= 6; i++) {
            dining.registerTable(new RegisterTableCommand("T" + i, i % 2 == 0 ? 4 : 2));
        }
        for (int i = 1; i <= 3; i++) {
            dining.registerTable(new RegisterTableCommand("Counter " + i, 1));
        }
        LOG.log(System.Logger.Level.INFO,
                "[dev-seed] registered 6 dine-in tables (T1..T6) and 3 counters (Counter 1..3)");
    }
}
