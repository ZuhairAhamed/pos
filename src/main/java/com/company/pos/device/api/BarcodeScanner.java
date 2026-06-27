package com.company.pos.device.api;

import java.util.function.Consumer;

public interface BarcodeScanner {

    void onScan(Consumer<String> handler);
}
