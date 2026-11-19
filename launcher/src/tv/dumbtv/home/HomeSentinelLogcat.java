package tv.dumbtv.home;

/** Pure matcher for ActivityManager records that prove Amazon Home became foreground. */
final class HomeSentinelLogcat {
    private static final String AMAZON_PKG = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "HomeActivity_vNext";
    private HomeSentinelLogcat() {}

    static boolean matches(String line) {
        if (line == null || !line.contains(AMAZON_PKG) || !line.contains(AMAZON_HOME)) return false;
        // START only means a launch was requested, not that Home became visible.
        return line.contains("Displayed ") || line.contains("Resumed ")
                || line.contains("topResumedActivity");
    }
}
