package com.company.pos.device.api;

import java.util.List;

/** Outbound port for the kitchen ticket printer. Distinct from {@link Printer} (receipts). */
public interface KitchenPrinter {

    void print(List<PrintLine> lines);

    void cut();
}
