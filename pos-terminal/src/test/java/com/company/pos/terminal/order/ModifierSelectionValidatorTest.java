package com.company.pos.terminal.order;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.ModifierOptionView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModifierSelectionValidatorTest {

    private final UUID optA = UUID.randomUUID();
    private final UUID optB = UUID.randomUUID();

    private ModifierGroupView forcedOneOf() {
        return new ModifierGroupView(
                UUID.randomUUID(),
                "Doneness",
                1,
                1,
                List.of(
                        new ModifierOptionView(optA, "Rare", BigDecimal.ZERO),
                        new ModifierOptionView(optB, "Well", BigDecimal.ZERO)));
    }

    @Test
    void forcedGroupWithNoSelectionIsInvalid() {
        assertNotNull(ModifierSelectionValidator.validate(List.of(forcedOneOf()), Set.of()));
    }

    @Test
    void exceedingMaxIsInvalid() {
        assertNotNull(
                ModifierSelectionValidator.validate(List.of(forcedOneOf()), Set.of(optA, optB)));
    }

    @Test
    void validSingleSelectionPasses() {
        assertNull(ModifierSelectionValidator.validate(List.of(forcedOneOf()), Set.of(optA)));
    }
}
