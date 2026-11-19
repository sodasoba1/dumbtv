package tv.dumbtv.home;

/** State-transition tests for the Fire OS 6 manual Amazon-interface hand-off. */
public final class HomeSentinelPauseTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static void settingsRoute() {
        HomeSentinelPause.State state = HomeSentinelPause.State.AMAZON_SETTINGS;
        state = HomeSentinelPause.afterForeground(state, "com.android.systemui", null);
        check(state == HomeSentinelPause.State.AMAZON_SETTINGS, "System UI keeps settings hand-off");
        state = HomeSentinelPause.afterForeground(state, "com.amazon.tv.settings.v2", null);
        check(state == HomeSentinelPause.State.AMAZON_SETTINGS, "Amazon settings keep hand-off");
        state = HomeSentinelPause.afterForeground(state, "com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.HomeActivity_vNext");
        check(state == HomeSentinelPause.State.ACTIVE, "returning to Amazon Home resumes takeover");
        check(HomeSentinelPause.shouldRecover(state), "Amazon Home is recoverable after settings route");
    }

    private static void explicitAmazonHomeHold() {
        HomeSentinelPause.State state = HomeSentinelPause.State.AMAZON_HOME_HOLD;
        state = HomeSentinelPause.afterForeground(state, "com.amazon.tv.settings.v2", null);
        check(state == HomeSentinelPause.State.AMAZON_HOME_HOLD, "Amazon settings preserve explicit Home hold");
        state = HomeSentinelPause.afterForeground(state, "com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.HomeActivity_vNext");
        check(state == HomeSentinelPause.State.AMAZON_HOME_HOLD, "explicit Amazon Home remains held");
        check(!HomeSentinelPause.shouldRecover(state), "explicit Amazon Home must not be reclaimed");
        state = HomeSentinelPause.afterForeground(state, "com.android.systemui", null);
        check(state == HomeSentinelPause.State.AMAZON_HOME_HOLD, "System UI does not release explicit hold");
        state = HomeSentinelPause.afterForeground(state, "com.netflix.ninja", null);
        check(state == HomeSentinelPause.State.ACTIVE, "leaving Amazon for an app releases explicit hold");
        state = HomeSentinelPause.afterForeground(HomeSentinelPause.State.AMAZON_HOME_HOLD,
                "tv.dumbtv.home", null);
        check(state == HomeSentinelPause.State.ACTIVE, "returning to DumbTV releases explicit hold");
    }

    public static void main(String[] args) {
        settingsRoute();
        explicitAmazonHomeHold();
        System.out.println("HomeSentinelPauseTest passed");
    }
}
