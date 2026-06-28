package com.company.pos.device.infrastructure;

import com.company.pos.device.api.PrintLine;
import com.company.pos.device.api.Printer;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Default {@link Printer} adapter: records output in memory. Replaced by a JavaPOS/ESC-POS adapter later. */
@Component
public class InMemoryPrinter implements Printer {

    private final List<PrintLine> lastReceipt = new ArrayList<>();
    private int cutCount;

    @Override
    public synchronized void print(List<PrintLine> lines) {
        lastReceipt.clear();
        lastReceipt.addAll(lines);
    }

    @Override
    public synchronized void cut() {
        cutCount++;
    }

    public synchronized List<PrintLine> lastReceipt() {
        return List.copyOf(lastReceipt);
    }

    public synchronized int cutCount() {
        return cutCount;
    }
}
