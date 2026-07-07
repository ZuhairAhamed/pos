package com.company.pos.kitchen.application;

import com.company.pos.device.api.KitchenPrinter;
import com.company.pos.device.api.PrintLine;
import com.company.pos.dining.api.KitchenTicketsFired;
import com.company.pos.kitchen.api.KitchenService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Prints fired order lines as kitchen tickets, one per station (after-commit, async, own tx —
 * a dead printer never rolls back or blocks the fire). At-least-once outbox delivery means a
 * replay can reprint a ticket; not deduped (same known gap as inventory/cashdrawer listeners).
 */
@Component
class KitchenTicketsFiredListener {

    private final KitchenService kitchen;
    private final KitchenPrinter printer;

    KitchenTicketsFiredListener(KitchenService kitchen, KitchenPrinter printer) {
        this.kitchen = kitchen;
        this.printer = printer;
    }

    @ApplicationModuleListener
    void on(KitchenTicketsFired event) {
        Map<String, List<KitchenTicketsFired.FiredLine>> byStation = new LinkedHashMap<>();
        for (KitchenTicketsFired.FiredLine line : event.lines()) {
            String station = kitchen.stationFor(line.sku());
            byStation.computeIfAbsent(station, s -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<String, List<KitchenTicketsFired.FiredLine>> entry : byStation.entrySet()) {
            printer.print(buildTicket(event, entry.getKey(), entry.getValue()));
            printer.cut();
        }
    }

    private List<PrintLine> buildTicket(KitchenTicketsFired event, String station,
            List<KitchenTicketsFired.FiredLine> lines) {
        List<PrintLine> out = new ArrayList<>();
        out.add(new PrintLine("*** " + station + " ***", true));
        out.add(new PrintLine("Table " + event.tableLabel() + "   " + event.firedAt(), false));
        for (KitchenTicketsFired.FiredLine line : lines) {
            out.add(new PrintLine(line.qty().stripTrailingZeros().toPlainString() + " x " + line.name(), true));
            for (KitchenTicketsFired.FiredModifier m : line.modifiers()) {
                out.add(new PrintLine("   + " + m.name(), false));
            }
            if (line.note() != null && !line.note().isBlank()) {
                out.add(new PrintLine("   note: " + line.note(), false));
            }
            if (line.course() != null) {
                out.add(new PrintLine("   [" + line.course() + "]", false));
            }
        }
        return out;
    }
}
