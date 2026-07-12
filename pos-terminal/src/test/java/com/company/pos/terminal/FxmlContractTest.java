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
}
