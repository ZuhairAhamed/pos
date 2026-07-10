package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.ModifierOptionView;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side pre-check for modifier selections before {@code addLine}. The server re-validates
 * authoritatively; this only gives fast feedback. A group is <b>forced</b> when
 * {@code minSelections >= 1}: it must have at least {@code minSelections} of its options chosen. No
 * group may exceed {@code maxSelections} (a {@code maxSelections <= 0} means "no upper bound").
 */
public final class ModifierSelectionValidator {

    private ModifierSelectionValidator() {}

    /** @return {@code null} if valid, else a human-readable reason for the first violation. */
    public static String validate(List<ModifierGroupView> groups, Set<UUID> selected) {
        for (ModifierGroupView g : groups) {
            long chosen =
                    g.options().stream()
                            .map(ModifierOptionView::id)
                            .filter(selected::contains)
                            .count();
            if (chosen < g.minSelections()) {
                return "Choose at least " + g.minSelections() + " for " + g.name();
            }
            if (g.maxSelections() > 0 && chosen > g.maxSelections()) {
                return "Choose at most " + g.maxSelections() + " for " + g.name();
            }
        }
        return null;
    }
}
