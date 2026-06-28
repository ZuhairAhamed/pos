package com.company.pos.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.CashDrawer;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.api.Printer;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class InMemoryDeviceAdapterTest {

    @Autowired
    Printer printer;
    @Autowired
    CashDrawer drawer;
    @Autowired
    InMemoryPrinter inMemoryPrinter;

    @Test
    void printerCapturesLinesAndCuts() {
        // cutCount accumulates across the shared application context, so assert the delta.
        int cutsBefore = inMemoryPrinter.cutCount();
        printer.print(List.of(new PrintLine("HELLO", true), new PrintLine("world", false)));
        printer.cut();

        assertThat(inMemoryPrinter.lastReceipt()).extracting(PrintLine::text)
                .containsExactly("HELLO", "world");
        assertThat(inMemoryPrinter.cutCount()).isEqualTo(cutsBefore + 1);
    }

    @Test
    void drawerOpensAndReportsState() {
        drawer.open();
        assertThat(drawer.isOpen()).isTrue();
    }
}
