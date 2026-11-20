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

        // Leaving Amazon's interface for a real app ends the explicit HOME hand-off.
        state = HomeSentinelPause.afterForeground(state, "com.netflix.ninja", null);
        check(state == HomeSentinelPause.State.ACTIVE,
                "launching a third-party app releases the explicit Amazon Home hold");
        check(HomeSentinelPause.shouldRecover(state),
                "takeover is enabled again after leaving Amazon's interface");

        // When that app later returns to Amazon HOME, DumbTV must be allowed to reclaim it.
        state = HomeSentinelPause.afterForeground(state, "com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.HomeActivity_vNext");
        check(state == HomeSentinelPause.State.ACTIVE,
                "returning from a third-party app leaves takeover active");
        check(HomeSentinelPause.shouldRecover(state),
                "Amazon Home is recoverable after a third-party app");

        state = HomeSentinelPause.afterForeground(state, "tv.dumbtv.home", null);
        check(state == HomeSentinelPause.State.ACTIVE, "DumbTV foreground remains active");
    }

    public static void main(String[] args) {
        settingsRoute();
        explicitAmazonHomeHold();
        System.out.println("HomeSentinelPauseTest passed");
    }
}
