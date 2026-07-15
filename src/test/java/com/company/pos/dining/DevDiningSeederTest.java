package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.TableView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"embedded", "dev"})
class DevDiningSeederTest {

    @Autowired DiningService dining;

    @Test
    void seedsDineInAndCounterTables() {
        var labels = dining.listTables().stream().map(TableView::label).toList();
        assertThat(labels).contains("T1", "T6", "Counter 1", "Counter 3");
        long counters = labels.stream().filter(l -> l.startsWith("Counter ")).count();
        assertThat(counters).isEqualTo(3);
    }
}
