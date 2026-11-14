package android.graphics;

/** Minimal #RRGGBB parser for Config's host-side colour persistence tests. */
public final class Color {
    private Color() {}
    public static int parseColor(String value) {
        if (value != null && value.matches("#[0-9a-fA-F]{6}")) {
            return 0xFF000000 | Integer.parseInt(value.substring(1), 16);
        }
        throw new IllegalArgumentException("unsupported colour: " + value);
    }
}
