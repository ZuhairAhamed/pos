package com.company.pos.device.api;

import java.util.List;

public interface Printer {

    void print(List<PrintLine> lines);

    void cut();
}
