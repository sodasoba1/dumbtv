package tv.dumbtv.home;

/** Fire OS 6 pause classification checks; no Android runtime or device required. */
public final class HomeSentinelPauseTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static void amazonPause() {
        check(!HomeSentinelPause.shouldResume("com.amazon.tv.launcher"),
                "Amazon settings remain inside the paused launcher");
        check(!HomeSentinelPause.shouldResume("com.amazon.tv.settings.v2"),
                "Amazon Network and Applications settings keep the pause");
        check(!HomeSentinelPause.shouldResume("com.amazon.tv.notificationcenter"),
                "Amazon notification centre keeps the pause");
        check(!HomeSentinelPause.shouldResume("com.amazon.tv.devicecontrolsettings"),
                "Amazon device control settings keep the pause");
        check(!HomeSentinelPause.shouldResume("tv.dumbtv.home"), "DumbTV does not resume its own pause");
        check(!HomeSentinelPause.shouldResume("com.android.systemui"), "System UI is not an app departure");
        check(!HomeSentinelPause.shouldResume("android"), "Android system UI is not an app departure");
        check(!HomeSentinelPause.shouldResume(null), "unknown foreground does not clear pause");
        check(HomeSentinelPause.shouldResume("com.netflix.ninja"),
                "opening another app resumes takeover");
    }

    public static void main(String[] args) {
        amazonPause();
        System.out.println("HomeSentinelPauseTest passed");
    }
}
