package tv.dumbtv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent 6x4 home widget placement model. It deliberately contains no Android Views:
 * placement, bounds and collision rules can be tested on the host JVM before UI integration.
 */
final class WidgetLayout {
    static final int COLUMNS = 6;
    static final int ROWS = 4;
    static final String CONFIG_KEY = "widgets.layout";

    static final class Widget {
        final String id;
        final String type;
        final int x;
        final int y;
        final int width;
        final int height;

        Widget(String id, String type, int x, int y, int width, int height) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean occupies(int column, int row) {
            return column >= x && column < x + width && row >= y && row < y + height;
        }
    }

    private final List<Widget> widgets = new ArrayList<>();

    static WidgetLayout defaults() {
        WidgetLayout result = new WidgetLayout();
        result.add("clock", "clock", 0, 0, 2, 1);
        result.add("date", "date", 2, 0, 2, 1);
        return result;
    }

    /** Adds a separate day/date widget when an older saved layout contains a clock only. */
    void ensureDateWidget() {
        if (find("date") != null || find("clock") == null) return;
        // Prefer the cell block immediately beside the clock, then the first available 2x1 slot.
        if (add("date", "date", 2, 0, 2, 1)) return;
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x <= COLUMNS - 2; x++) {
                if (add("date", "date", x, y, 2, 1)) return;
            }
        }
    }

    List<Widget> items() {
        return Collections.unmodifiableList(new ArrayList<>(widgets));
    }

    Widget find(String id) {
        for (Widget widget : widgets) if (widget.id.equals(id)) return widget;
        return null;
    }

    boolean add(String id, String type, int x, int y, int width, int height) {
        if (!validId(id) || !validType(type) || find(id) != null
                || !inside(x, y, width, height) || !validSize(width, height) || collides(null, x, y, width, height)) return false;
        widgets.add(new Widget(id, type, x, y, width, height));
        return true;
    }

    boolean move(String id, int x, int y) {
        Widget old = find(id);
        return old != null && replace(old, x, y, old.width, old.height);
    }

    boolean resize(String id, int width, int height) {
        Widget old = find(id);
        return old != null && replace(old, old.x, old.y, width, height);
    }

    boolean remove(String id) {
        for (int i = 0; i < widgets.size(); i++) {
            if (widgets.get(i).id.equals(id)) {
                widgets.remove(i);
                return true;
            }
        }
        return false;
    }

    private boolean replace(Widget old, int x, int y, int width, int height) {
        if (!inside(x, y, width, height) || !validSize(width, height)
                || collides(old.id, x, y, width, height)) return false;
        widgets.set(widgets.indexOf(old), new Widget(old.id, old.type, x, y, width, height));
        return true;
    }

    private boolean collides(String exceptId, int x, int y, int width, int height) {
        for (Widget other : widgets) {
            if (exceptId != null && exceptId.equals(other.id)) continue;
            boolean separated = x + width <= other.x || other.x + other.width <= x
                    || y + height <= other.y || other.y + other.height <= y;
            if (!separated) return true;
        }
        return false;
    }

    private static boolean inside(int x, int y, int width, int height) {
        return x >= 0 && y >= 0 && width > 0 && height > 0
                && width <= COLUMNS && height <= ROWS
                && x + width <= COLUMNS && y + height <= ROWS;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static boolean validType(String type) {
        return "clock".equals(type) || "memory".equals(type) || "network".equals(type) || "storage".equals(type) || "text".equals(type)
                || "diagnostics".equals(type) || "device".equals(type) || "date".equals(type);
    }

    /** Width is columns across; height is rows down. Every in-bounds rectangle is valid. */
    private static boolean validSize(int width, int height) {
        return width >= 1 && width <= COLUMNS && height >= 1 && height <= ROWS;
    }

    /** Stable compact format: id,type,x,y,width,height;... */
    String encode() {
        StringBuilder out = new StringBuilder();
        for (Widget widget : widgets) {
            if (out.length() > 0) out.append(';');
            out.append(widget.id).append(',').append(widget.type).append(',')
                    .append(widget.x).append(',').append(widget.y).append(',')
                    .append(widget.width).append(',').append(widget.height);
        }
        return out.toString();
    }

    /**
     * Migrates v1.14 saved sizes, whose UI described columns x rows, to the corrected
     * rows x columns presentation. Existing coordinates are retained where possible;
     * if a transposed rectangle no longer fits, find the nearest free placement.
     */
    static WidgetLayout decodeLegacy(String encoded) {
        WidgetLayout result = new WidgetLayout();
        if (encoded == null || encoded.trim().isEmpty()) return result;
        for (String record : encoded.split(";")) {
            String[] fields = record.split(",", -1);
            if (fields.length != 6) continue;
            try {
                int x = Integer.parseInt(fields[2]);
                int y = Integer.parseInt(fields[3]);
                int oldWidth = Integer.parseInt(fields[4]);
                int oldHeight = Integer.parseInt(fields[5]);
                int width = oldHeight;
                int height = oldWidth;
                if (result.add(fields[0], fields[1], x, y, width, height)) continue;
                boolean placed = false;
                for (int row = 0; row + height <= ROWS && !placed; row++) {
                    for (int column = 0; column + width <= COLUMNS; column++) {
                        if (result.add(fields[0], fields[1], column, row, width, height)) {
                            placed = true;
                            break;
                        }
                    }
                }
                // Retain the old valid rectangle rather than discard a widget if no
                // transposed location is available.
                if (!placed) result.add(fields[0], fields[1], x, y, oldWidth, oldHeight);
            } catch (NumberFormatException ignored) {
                // Ignore corrupt records; retain every other valid placement.
            }
        }
        return result;
    }

    static WidgetLayout decode(String encoded) {
        WidgetLayout result = new WidgetLayout();
        if (encoded == null || encoded.trim().isEmpty()) return result;
        for (String record : encoded.split(";")) {
            String[] fields = record.split(",", -1);
            if (fields.length != 6) continue;
            try {
                result.add(fields[0], fields[1], Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
                        Integer.parseInt(fields[5]));
            } catch (NumberFormatException ignored) {
                // Ignore corrupt records; retain every other valid placement.
            }
        }
        return result;
    }
}
