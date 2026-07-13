package com.company.pos.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for FXML resources: guards that fx:ids injected by controllers stay
 * present. FXML is plain XML on the classpath, so this stays headless like AppCssTest.
 */
class FxmlContractTest {

    static String resource(String path) throws Exception {
        try (InputStream in = FxmlContractTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void homeDeclaresShiftStatusLabel() throws Exception {
        assertTrue(resource("/fxml/home.fxml").contains("fx:id=\"shiftLabel\""),
                "home.fxml must declare the shift status label");
    }

    @Test
    void paymentDeclaresQuotePresentationNodes() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        assertTrue(fxml.contains("fx:id=\"estimateLabel\""), "muted estimate line");
        assertTrue(fxml.contains("fx:id=\"quoteBadge\""), "server-quote badge");
    }

    @Test
    void paymentDeclaresDenominationChips() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        for (String id : new String[] {
            "denomExactButton", "denom50Button", "denom100Button",
            "denom200Button", "denom500Button"
        }) {
            assertTrue(fxml.contains("fx:id=\"" + id + "\""), "missing denomination chip: " + id);
        }
    }

    @Test
    void paymentDeclaresSuccessBanner() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        assertTrue(fxml.contains("fx:id=\"successBanner\""));
        assertTrue(fxml.contains("fx:id=\"successCheck\""));
        assertTrue(fxml.contains("fx:id=\"paidLabel\""));
    }

    @Test
    void paymentDeclaresDiscountControls() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        for (String id : new String[] {
            "discountButton", "discountChipRow", "discountChipLabel", "removeDiscountButton"
        }) {
            assertTrue(fxml.contains("fx:id=\"" + id + "\""), "missing discount control: " + id);
        }
    }
}
