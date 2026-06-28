package com.company.pos.device.infrastructure;

import com.company.pos.device.api.CashDrawer;
import org.springframework.stereotype.Component;

/** Default {@link CashDrawer} adapter: tracks open state in memory. */
@Component
public class InMemoryCashDrawer implements CashDrawer {

    private volatile boolean open;

    @Override
    public void open() {
        this.open = true;
    }

    @Override
    public boolean isOpen() {
        return open;
    }
}
