# Bundled fonts (drop-in)

JavaFX needs local font files, not web fonts. Drop `SpaceGrotesk-*.ttf` (or
`Archivo-*.ttf`) and `Inter-*.ttf` here, then load them once at startup in
`PosTerminalApp.init()`:

```java
javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/fonts/Inter-Regular.ttf"), 16);
javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/fonts/SpaceGrotesk-Bold.ttf"), 44);
```

`app.css` already names "Inter" / "Space Grotesk" first in its font stacks, so the
bundled faces take effect automatically once loaded. Both families are OFL-licensed
and redistributable. Until then the system fallback stack is used.
