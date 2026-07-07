package com.company.pos.device.infrastructure;

import com.company.pos.device.api.KitchenPrinter;
import com.company.pos.device.api.PrintLine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Default {@link KitchenPrinter} adapter: records each printed ticket in memory (one per print call). */
@Component
public class InMemoryKitchenPrinter implements KitchenPrinter {

    private final List<List<PrintLine>> tickets = new ArrayList<>();

    @Override
    public synchronized void print(List<PrintLine> lines) {
        tickets.add(List.copyOf(lines));
    }

    @Override
    public synchronized void cut() {
        // no-op for the in-memory fake; a real adapter would issue a paper cut
    }

    public synchronized List<List<PrintLine>> tickets() {
        return List.copyOf(tickets);
    }

    public synchronized void clear() {
        tickets.clear();
    }
}
