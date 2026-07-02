package com.company.pos.dashboard.api;

import com.company.pos.shift.api.ShiftView;
import java.util.List;

public record OpenShifts(List<ShiftView> shifts, List<String> activeCashiers) {
}
