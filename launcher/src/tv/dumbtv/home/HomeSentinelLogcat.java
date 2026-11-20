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

    /** Parses the leading seconds.milliseconds field emitted by logcat -v epoch. */
    static long timestampMillis(String line) {
        if (line == null) return -1;
        String trimmed = line.trim();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) end++;
        if (end == 0) return -1;
        try {
            double seconds = Double.parseDouble(trimmed.substring(0, end));
            if (Double.isNaN(seconds) || Double.isInfinite(seconds)
                    || seconds <= 0 || seconds > Long.MAX_VALUE / 1000.0) return -1;
            return Math.round(seconds * 1000.0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Reject buffered logcat records older than the latest accepted foreground event. */
    static boolean isCurrentForegroundEvent(long timestamp, long lastTimestamp,
                                            String lastPackage, String lastClass) {
        if (timestamp <= 0 || timestamp < lastTimestamp) return false;
        if (timestamp == lastTimestamp) {
            return AMAZON_PKG.equals(lastPackage) && lastClass != null
                    && lastClass.contains(AMAZON_HOME);
        }
        return true;
    }
}
