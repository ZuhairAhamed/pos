package com.company.pos.terminal.view;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.GridPane;

/** Shared 3×4 numeric pad appending to a target field — the modal dialogs' common form language. */
final class Keypads {

    private Keypads() {}

    static GridPane numericPad(TextInputControl target) {
        GridPane pad = new GridPane();
        pad.getStyleClass().add("pin-pad");
        pad.setAlignment(Pos.CENTER);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {".", "0", "⌫"}};
        for (int r = 0; r < keys.length; r++) {
            for (int c = 0; c < keys[r].length; c++) {
                String key = keys[r][c];
                Button b = new Button(key);
                b.getStyleClass().add("pin-key");
                if (".".equals(key) || "⌫".equals(key)) {
                    b.getStyleClass().add("pin-key-alt");
                }
                b.setOnAction(e -> {
                    String t = target.getText() == null ? "" : target.getText();
                    if ("⌫".equals(key)) {
                        if (!t.isEmpty()) {
                            target.setText(t.substring(0, t.length() - 1));
                        }
                    } else {
                        target.setText(t + key);
                    }
                });
                pad.add(b, c, r);
            }
        }
        return pad;
    }
}
