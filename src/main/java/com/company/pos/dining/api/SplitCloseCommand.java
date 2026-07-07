package com.company.pos.dining.api;

import java.util.List;

/** BY_ITEM populates {@code bills} (and {@code even} is null); EVEN populates {@code even} (and {@code bills} is null). */
public record SplitCloseCommand(SplitMode mode, List<BillInput> bills, EvenSplitInput even,
        boolean waiveServiceCharge) {

    /** Convenience: no waiver (used by existing callers/tests). */
    public SplitCloseCommand(SplitMode mode, List<BillInput> bills, EvenSplitInput even) {
        this(mode, bills, even, false);
    }
}
