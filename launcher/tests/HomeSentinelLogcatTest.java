package tv.dumbtv.home;

/** ActivityManager log matcher tests; no Android runtime or device required. */
public final class HomeSentinelLogcatTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        check(HomeSentinelLogcat.matches("I/ActivityManager: Displayed com.amazon.tv.launcher/.ui.HomeActivity_vNext"),
                "Displayed exact Amazon Home is accepted");
        check(HomeSentinelLogcat.matches("I/ActivityManager: Resumed com.amazon.tv.launcher/.ui.HomeActivity_vNext"),
                "Resumed exact Amazon Home is accepted");
        check(HomeSentinelLogcat.matches("topResumedActivity=ActivityRecord{u0 com.amazon.tv.launcher/.ui.HomeActivity_vNext}"),
                "topResumed exact Amazon Home is accepted");
        check(!HomeSentinelLogcat.matches("I/ActivityManager: START u0 com.amazon.tv.launcher/.ui.HomeActivity_vNext"),
                "START without foreground proof is ignored");
        check(!HomeSentinelLogcat.matches("I/ActivityManager: Displayed com.amazon.tv.launcher/.ui.MainSettingsActivity"),
                "Amazon settings activity is ignored");
        check(!HomeSentinelLogcat.matches("I/ActivityManager: Displayed com.netflix.ninja/.MainActivity"),
                "other applications are ignored");
        check(!HomeSentinelLogcat.matches(null), "null log line is ignored");
        System.out.println("HomeSentinelLogcatTest passed");
    }
}
