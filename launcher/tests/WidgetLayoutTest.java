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
        check(grid.add("diagnostics", "diagnostics", 0, 2, 4, 2), "accept diagnostics widget");
        check(grid.add("device", "device", 4, 2, 2, 2), "accept device information widget");
        check(!grid.add("too-small", "clock", 0, 2, 1, 2), "reject unreadably narrow widget");
        check(WidgetLayout.defaults().items().size() == 1, "first-run layout starts with the clock only");
        check(grid.move("memory", 4, 0), "move into free cells");
        check(!grid.move("memory", 1, 0), "reject move into occupied cells");
        check(!grid.resize("clock", 5, 2), "reject resize into occupied cells");
        check(grid.resize("clock", 2, 3), "allow free vertical span");
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
        System.out.println("WidgetLayoutTest passed (bounds, collision, move, resize, remove, persistence)");
    }
}
