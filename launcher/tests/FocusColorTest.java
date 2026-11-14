package tv.dumbtv.home;

/** Small host-side checks for the pixel path, without a device or test framework. */
public final class FocusColorTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        int[] transparent = new int[100];
        check(FocusColor.representative(transparent) == FocusColor.FALLBACK, "transparent fallback");

        int[] icon = new int[100];
        for (int i = 0; i < icon.length; i++) icon[i] = i < 75 ? 0xFFFFFFFF : 0xFFFF3020;
        int color = FocusColor.representative(icon);
        int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
        check(red > green * 2 && red > blue * 2, "colored logo beats white backdrop");
        check(red < 200, "bright art darkened");

        int[] blueIcon = new int[100];
        for (int i = 0; i < blueIcon.length; i++) blueIcon[i] = 0xFF2050E0;
        color = FocusColor.representative(blueIcon);
        check((color & 255) > (color >> 16 & 255), "blue stays blue");
        check((FocusColor.tint(color) >>> 24) == 61, "tint is 24 percent opaque");
        check((FocusColor.tint(color) & 0xFFFFFF) == (color & 0xFFFFFF), "tint keeps color");
        check("black".equals(Config.BACKGROUND.fallback), "default background unchanged");
        check("Adaptive wallpaper tint".equals(Config.BACKGROUND.name("adaptive-tint")),
                "tint is an Options choice");
        check("Amber".equals(Config.ACCENT.name("amber")), "amber accent unchanged");
        check(Config.GRADIENT.values.length == 9, "four diagonal gradient directions");
        check("top-left".equals(Config.GRADIENT.values[5]), "top-left direction is exposed");
        check("bottom-right".equals(Config.GRADIENT.values[8]), "bottom-right direction is exposed");
        check(Config.ACCENT.values.length == 25, "all legacy accent values remain readable");
        check(Config.ACCENT.optionValues.length == 9, "accent shows eight presets and custom");
        check(Config.BACKGROUND.optionValues.length == 8, "background shows core presets, image modes and custom");
        check(Config.GRADIENT_COLOR.values.length == 26, "all legacy gradient values plus custom remain readable");
        check(Config.GRADIENT_COLOR.optionValues.length == 5, "gradient shows four presets and custom");
        check(Config.paletteColor("blue") == 0xFF3D8BFF, "gradient blue matches accent palette");
        check(Config.paletteColor("black") == 0xFF000000, "gradient defaults to black");
        System.out.println("FocusColorTest passed");
    }
}
