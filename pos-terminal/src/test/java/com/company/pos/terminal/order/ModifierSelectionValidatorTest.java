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
    private final UUID optC = UUID.randomUUID();
    private final UUID optD = UUID.randomUUID();
    private final UUID optE = UUID.randomUUID();

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

    @Test
    void exactlyMaxSelectionsIsValid() {
        // min=0, max=2 — choosing exactly 2 must be accepted
        ModifierGroupView group =
                new ModifierGroupView(
                        UUID.randomUUID(),
                        "Toppings",
                        0,
                        2,
                        List.of(
                                new ModifierOptionView(optA, "Cheese", BigDecimal.ZERO),
                                new ModifierOptionView(optB, "Bacon", BigDecimal.ZERO)));
        assertNull(ModifierSelectionValidator.validate(List.of(group), Set.of(optA, optB)));
    }

    @Test
    void optionalGroupWithNoSelectionIsValid() {
        // min=0 — choosing nothing must be accepted
        ModifierGroupView group =
                new ModifierGroupView(
                        UUID.randomUUID(),
                        "Extras",
                        0,
                        1,
                        List.of(new ModifierOptionView(optA, "Sauce", BigDecimal.ZERO)));
        assertNull(ModifierSelectionValidator.validate(List.of(group), Set.of()));
    }

    @Test
    void maxZeroMeansUnlimited() {
        // min=1, max=0 — max=0 means no upper bound; choosing 5 must not be rejected
        ModifierGroupView group =
                new ModifierGroupView(
                        UUID.randomUUID(),
                        "Add-ons",
                        1,
                        0,
                        List.of(
                                new ModifierOptionView(optA, "A", BigDecimal.ZERO),
                                new ModifierOptionView(optB, "B", BigDecimal.ZERO),
                                new ModifierOptionView(optC, "C", BigDecimal.ZERO),
                                new ModifierOptionView(optD, "D", BigDecimal.ZERO),
                                new ModifierOptionView(optE, "E", BigDecimal.ZERO)));
        assertNull(
                ModifierSelectionValidator.validate(
                        List.of(group), Set.of(optA, optB, optC, optD, optE)));
    }
}
