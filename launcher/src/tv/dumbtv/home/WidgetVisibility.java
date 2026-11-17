package tv.dumbtv.home;

import java.util.Arrays;
import java.util.List;

/** Persistent visibility preferences, separate from the saved widget placements. */
final class WidgetVisibility {
    static final class Definition {
        final String id, label;
        Definition(String id, String label) { this.id = id; this.label = label; }
    }

    static final List<Definition> ALL = Arrays.asList(
            new Definition("clock", "Clock"),
            new Definition("memory", "Memory"),
            new Definition("recent", "Recently opened apps"),
            new Definition("network", "Network"),
            new Definition("storage", "Storage"),
            new Definition("text", "Custom text"),
            new Definition("diagnostics", "DumbTV diagnostics"),
            new Definition("device", "Device information"));

    private WidgetVisibility() {}

    static boolean isEnabled(Config cfg, String id) {
        return !"off".equals(cfg.get("widgets.visible." + id, "on"));
    }

    static void setEnabled(Config cfg, String id, boolean enabled) {
        cfg.set("widgets.visible." + id, enabled ? "on" : "off");
        if ("memory".equals(id)) cfg.set(Config.MEMORY.key, enabled ? "on" : "off");
    }
}
