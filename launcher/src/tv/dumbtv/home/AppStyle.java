package tv.dumbtv.home;

/** Validated per-package tile overrides. Missing values inherit the global appearance setting. */
final class AppStyle {
    private AppStyle() {}

    static String key(Item item, String property) {
        return "appstyle." + item.id + "." + property;
    }

    static String get(Config config, Item item, String property, String fallback) {
        String value = config.get(key(item, property), fallback);
        String[] allowed;
        if ("radius".equals(property)) allowed = values(0);
        else if ("focus_scale".equals(property)) allowed = values(1);
        else if ("border".equals(property)) allowed = values(2);
        else if ("shadow".equals(property)) allowed = values(3);
        else if ("labels".equals(property)) allowed = values(4);
        else if ("size".equals(property)) allowed = values(5);
        else if ("background".equals(property)) {
            return value.matches("#[0-9A-Fa-f]{6}") ? value : fallback;
        } else return fallback;
        for (String option : allowed) if (option.equals(value)) return value;
        return fallback;
    }

    static int backgroundColor(Config config, Item item, int fallback) {
        String value = get(config, item, "background", "");
        return value.isEmpty() ? fallback : Config.parseColor(value, fallback);
    }

    static void set(Config config, Item item, String property, String value) {
        config.set(key(item, property), value);
    }

    static String[] properties() {
        return new String[] {"Corners", "Focus scale", "Border", "Shadow / glow", "App name", "Tile size", "Background colour"};
    }

    static String[] values(int property) {
        switch (property) {
            case 0: return new String[] {"square", "soft", "rounded"};
            case 1: return new String[] {"off", "subtle", "normal", "strong"};
            case 2: return new String[] {"off", "subtle", "accent"};
            case 3: return new String[] {"off", "soft", "glow"};
            default: return new String[] {"focus", "always", "never"};
        }
    }

    static String[] labels(int property) {
        switch (property) {
            case 0: return new String[] {"Square", "Soft", "Rounded"};
            case 1: return new String[] {"Off", "Subtle", "Normal", "Strong"};
            case 2: return new String[] {"Off", "Subtle", "Accent"};
            case 3: return new String[] {"Off", "Soft shadow", "Focus glow"};
            case 5: return new String[] {"Compact", "Normal", "Large"};
            default: return new String[] {"On focus", "Always", "Never"};
        }
    }

    static String configProperty(int property) {
        switch (property) {
            case 0: return "radius";
            case 1: return "focus_scale";
            case 2: return "border";
            case 3: return "shadow";
            case 5: return "size";
            default: return "labels";
        }
    }

    static String inherited(Config config, int property) {
        switch (property) {
            case 0: return config.get(Config.TILE_RADIUS);
            case 1: return config.get(Config.FOCUS_SCALE);
            case 2: return config.get(Config.TILE_BORDER);
            case 3: return config.get(Config.TILE_SHADOW);
            case 5: return "normal";
            default: return config.get(Config.LABELS);
        }
    }

    static String current(Config config, Item item, int property) {
        return get(config, item, configProperty(property), inherited(config, property));
    }
}
