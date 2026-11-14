package tv.dumbtv.home;

/** Inclusive UsageStats query-window deduplication checks. */
public final class UsageEventCursorTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        UsageEventCursor cursor = new UsageEventCursor();

        cursor.beginQuery();
        check(cursor.accept(100, "amazon", "Home"), "first event is accepted");
        check(cursor.accept(100, "netflix", "Player"), "different same-ms event is accepted");

        cursor.beginQuery();
        check(!cursor.accept(100, "amazon", "Home"), "inclusive window does not replay first event");
        check(!cursor.accept(100, "netflix", "Player"), "inclusive window does not replay second event");
        check(cursor.accept(100, "settings", "Network"), "new same-ms event is not lost");

        cursor.beginQuery();
        check(!cursor.accept(100, "amazon", "Home"), "old first event remains deduplicated");
        check(!cursor.accept(100, "netflix", "Player"), "old second event remains deduplicated");
        check(!cursor.accept(100, "settings", "Network"), "newly consumed event is deduplicated");
        check(cursor.accept(100, "amazon", "Home"), "identical same-ms occurrence count is preserved");

        cursor.beginQuery();
        check(!cursor.accept(100, "amazon", "Home"), "both identical occurrences are now consumed");
        check(cursor.accept(101, "amazon", "Home"), "later timestamp advances the cursor");
        check(cursor.lastTimestamp() == 101, "cursor exposes latest consumed timestamp");

        cursor.beginQuery();
        check(!cursor.accept(100, "late", "stale"), "older event cannot move cursor backwards");
        check(cursor.accept(101, "netflix", "Player"), "distinct event at current timestamp is accepted");
        check(cursor.accept(102, "amazon", "Home"), "next timestamp advances normally");

        System.out.println("UsageEventCursorTest passed");
    }
}
