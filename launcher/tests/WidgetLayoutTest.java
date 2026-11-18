package tv.dumbtv.home;

/** Host-side bounds, overlap, resizing and persistence checks for the fixed widget grid. */
public final class WidgetLayoutTest {
    private WidgetLayoutTest() {}

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        WidgetLayout grid = new WidgetLayout();
        check(grid.add("clock", "clock", 0, 0, 2, 2), "place clock");
        check(grid.add("memory", "memory", 2, 0, 2, 2), "place memory beside clock");
        check(!grid.add("overlap", "text", 1, 0, 2, 1), "reject overlap");
        check(!grid.add("outside", "text", 5, 3, 2, 1), "reject horizontal overflow");
        check(!grid.add("bad", "unknown", 0, 2, 1, 1), "reject unknown widget type");
        check(grid.add("single-row", "text", 0, 3, 1, 1), "accept a single row widget");
        check(!grid.add("recent", "recent", 0, 2, 1, 2),
                "Recently Opened is no longer an available widget");
        WidgetLayout newWidgets = new WidgetLayout();
        check(newWidgets.add("diagnostics", "diagnostics", 0, 0, 3, 3), "accept diagnostics widget");
        check(newWidgets.add("device", "device", 3, 0, 3, 2), "accept device information widget");
        check(newWidgets.add("wide", "clock", 3, 2, 2, 1), "accept default 1 row x 2 columns widget size");
        check(WidgetLayout.defaults().items().size() == 1, "first-run layout starts with the clock only");
        check(WidgetLayout.defaults().find("clock").width == 2
                && WidgetLayout.defaults().find("clock").height == 1, "clock default is 1 row x 2 columns");
        WidgetLayout legacy = new WidgetLayout();
        check(legacy.add("clock", "clock", 0, 0, 2, 2), "load existing user-sized clock");
        check(legacy.add("network", "network", 2, 0, 2, 2), "load existing user-sized network");
        check(WidgetLayout.decode(legacy.encode()).find("clock").width == 2,
                "preserve existing 2x2 widget size");
        check(WidgetLayout.decode(legacy.encode()).find("network").width == 2,
                "preserve all existing widget sizes");
        check(grid.move("memory", 4, 0), "move into free cells");
        check(!grid.move("memory", 1, 0), "reject move into occupied cells");
        check(!grid.resize("clock", 5, 2), "reject resize into occupied cells");
        check(grid.resize("clock", 2, 3), "allow free vertical span");
        WidgetLayout full = new WidgetLayout();
        check(full.add("full", "clock", 0, 0, 2, 2), "place full-grid resize fixture");
        check(full.resize("full", 6, 4), "allow maximum full-grid size");
        check(!full.resize("full", 7, 4), "reject width beyond full-grid size");
        check(grid.remove("memory"), "remove widget");
        check(grid.add("text_1", "text", 2, 0, 4, 2), "reuse freed area with span");
        String encoded = grid.encode();
        WidgetLayout decoded = WidgetLayout.decode(encoded);
        check(encoded.equals(decoded.encode()), "round-trip persistence");
        check(decoded.items().size() == 2, "retain both valid widgets");
        WidgetLayout repaired = WidgetLayout.decode(encoded
                + ";bad,clock,99,0,1,1;clock,memory,x,0,1,1;clock,clock,0,0,1,1");
        check(repaired.items().size() == 2, "ignore malformed, out-of-bounds and duplicate records");
        check(!repaired.add("clock", "clock", 0, 3, 1, 1), "prevent duplicate widget IDs");
        WidgetLayout oldSaved = WidgetLayout.decodeLegacy(
                "clock,clock,0,0,1,2;recent,recent,1,0,2,2;network,network,3,0,1,2");
        check(oldSaved.find("clock") != null && oldSaved.find("network") != null
                && oldSaved.find("recent") == null,
                "ignore the removed widget in existing saved layouts without losing other widgets");
        check(oldSaved.find("clock").width == 2 && oldSaved.find("clock").height == 1,
                "migrate legacy 1x2 column/row dimensions to 1 row x 2 columns");
        check(oldSaved.find("network").width == 2 && oldSaved.find("network").height == 1,
                "migrate every existing widget size");
        System.out.println("WidgetLayoutTest passed (bounds, collision, move, resize, remove, persistence, removed widget migration)");
    }
}
