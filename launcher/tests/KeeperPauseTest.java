package tv.dumbtv.home;

/** Fire OS 6 pause classification checks; no Android runtime or device required. */
public final class KeeperPauseTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static void amazonPause() {
        check(!KeeperPause.shouldResume("com.amazon.tv.launcher"),
                "Amazon settings remain inside the paused launcher");
        check(!KeeperPause.shouldResume("com.amazon.tv.settings.v2"),
                "Amazon Network and Applications settings keep the pause");
        check(!KeeperPause.shouldResume("com.amazon.tv.notificationcenter"),
                "Amazon notification centre keeps the pause");
        check(!KeeperPause.shouldResume("com.amazon.tv.devicecontrolsettings"),
                "Amazon device control settings keep the pause");
        check(!KeeperPause.shouldResume("tv.dumbtv.home"), "DumbTV does not resume its own pause");
        check(!KeeperPause.shouldResume("com.android.systemui"), "System UI is not an app departure");
        check(!KeeperPause.shouldResume("android"), "Android system UI is not an app departure");
        check(!KeeperPause.shouldResume(null), "unknown foreground does not clear pause");
        check(KeeperPause.shouldResume("com.netflix.ninja"),
                "opening another app resumes takeover");
    }

    public static void main(String[] args) {
        amazonPause();
        System.out.println("KeeperPauseTest passed");
    }
}
