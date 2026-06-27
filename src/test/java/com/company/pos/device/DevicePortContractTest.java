package com.company.pos.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.CashDrawer;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.api.Printer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DevicePortContractTest {

    /** A fake fulfilling the Printer port — proves the interface is implementable/usable. */
    static final class FakePrinter implements Printer {
        final List<PrintLine> printed = new ArrayList<>();
        int cuts = 0;

        @Override
        public void print(List<PrintLine> lines) {
            printed.addAll(lines);
        }

        @Override
        public void cut() {
            cuts++;
        }
    }

    /** A fake fulfilling the CashDrawer port. */
    static final class FakeCashDrawer implements CashDrawer {
        private boolean open = false;

        @Override
        public void open() {
            this.open = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    @Test
    void printerAcceptsLinesAndCuts() {
        FakePrinter printer = new FakePrinter();

        printer.print(List.of(new PrintLine("Total: 15.00 SAR", true)));
        printer.cut();

        assertThat(printer.printed).hasSize(1);
        assertThat(printer.printed.get(0).bold()).isTrue();
        assertThat(printer.cuts).isEqualTo(1);
    }

    @Test
    void cashDrawerOpensFromClosed() {
        FakeCashDrawer drawer = new FakeCashDrawer();

        assertThat(drawer.isOpen()).isFalse();
        drawer.open();
        assertThat(drawer.isOpen()).isTrue();
    }
}
