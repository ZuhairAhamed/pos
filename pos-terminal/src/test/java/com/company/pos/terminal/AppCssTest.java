package com.company.pos.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the shared stylesheet: guards that the semantic emerald token
 * system and the retail/total-bar style classes remain present. Not a visual test —
 * it asserts the design-system vocabulary later screens depend on cannot silently
 * vanish. Loading the resource also fails loudly if the file goes missing.
 */
class AppCssTest {

    private static String css() throws Exception {
        try (InputStream in = AppCssTest.class.getResourceAsStream("/css/app.css")) {
            assertNotNull(in, "app.css must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void definesSemanticEmeraldTokens() throws Exception {
        String css = css();
        assertTrue(css.contains("-fx-primary: #0E7C66"), "deep emerald primary token");
        assertTrue(css.contains("-fx-primary-press: #0A5E4D"), "primary pressed token");
        assertTrue(css.contains("-fx-accent: #E8A32C"), "amber accent token");
        assertTrue(css.contains("-fx-canvas: #F6F4EF"), "warm canvas token");
        assertTrue(css.contains("-fx-ink: #16202E"), "ink token");
        assertTrue(css.contains("-fx-surface:"), "alias token for existing screens");
        assertTrue(css.contains("-fx-primary-hover:"), "alias token for existing screens");
    }

    @Test
    void definesCategoryAndTenderAndTotalBarClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".cat-drinks", ".cat-food", ".cat-merch", ".cat-sides",
            ".tender-cash", ".tender-card", ".tender-wallet",
            ".total-bar", ".total-bar-grand", ".total-bar-pulse",
            ".cart-line", ".qty-stepper", ".empty-cart", ".home-tile", ".money"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }

    @Test
    void definesSliceFourClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".denom-line-total",
            ".estimate-line", ".quote-badge",
            ".denom-chip",
            ".success-banner", ".success-check"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }

    @Test
    void definesSliceFiveClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".discount-chip", ".approval-hint", ".reason-chip"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }

    @Test
    void definesSliceSixSplitClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".mode-toggle", ".guest-tab", ".split-line", ".split-line-assigned",
            ".guest-badge", ".split-amount-row"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }

    @Test
    void definesSliceSevenClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".segmented", ".segment", ".table-seated", ".table-active",
            ".attention-badge", ".takeaway-row"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }

    @Test
    void definesSliceEightClasses() throws Exception {
        String css = css();
        for (String cls : new String[] { ".email-dialog", ".email-confirm" }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }

    @Test
    void definesSliceNineClasses() throws Exception {
        String css = css();
        for (String cls : new String[] { ".course-chip", ".line-remove" }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
        assertTrue(css.contains(".cart-line.order-line-fired"),
                "fired-row muted style must use a live (non-.list-cell) selector");
    }

    @Test
    void definesSliceTenVoidClass() throws Exception {
        assertTrue(css().contains(".btn-danger"), "missing style class: .btn-danger");
    }
}
