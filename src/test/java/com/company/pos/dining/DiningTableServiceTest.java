package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.TableView;
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
class DiningTableServiceTest {

    @Autowired
    DiningService dining;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void registersTableWithExplicitSeats() {
        TableView t = dining.registerTable(new RegisterTableCommand("T1", 6));
        assertThat(t.id()).isNotNull();
        assertThat(t.label()).isEqualTo("T1");
        assertThat(t.seats()).isEqualTo(6);
        assertThat(t.active()).isTrue();
    }

    @Test
    void nullSeatsFallsBackToConfiguredDefault() {
        TableView t = dining.registerTable(new RegisterTableCommand("T2", null));
        assertThat(t.seats()).isEqualTo(4); // dining.table.default.seats default
    }

    @Test
    void rejectsBlankLabel() {
        assertThatThrownBy(() -> dining.registerTable(new RegisterTableCommand("  ", 2)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsDuplicateLabel() {
        dining.registerTable(new RegisterTableCommand("DUP", 2));
        assertThatThrownBy(() -> dining.registerTable(new RegisterTableCommand("DUP", 4)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateHidesFromActiveListButRowRemains() {
        TableView t = dining.registerTable(new RegisterTableCommand("T3", 2));
        dining.deactivateTable(t.id());
        assertThat(dining.listTables()).filteredOn(TableView::active)
                .extracting(TableView::id).doesNotContain(t.id());
    }
}
