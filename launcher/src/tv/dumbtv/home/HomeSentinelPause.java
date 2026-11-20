package tv.dumbtv.home;

/** Explicit states for handing the Fire OS 6 interface to Amazon without HOME takeovers. */
final class HomeSentinelPause {
    enum State { ACTIVE, AMAZON_SETTINGS, AMAZON_HOME_HOLD }

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final String AMAZON_SETTINGS = "com.amazon.tv.settings.v2";
    private static final String AMAZON_NOTIFICATIONS = "com.amazon.tv.notificationcenter";
    private static final String AMAZON_DEVICE_CONTROL = "com.amazon.tv.devicecontrolsettings";
    private static final String DUMBTV_PKG = "tv.dumbtv.home";
    private static final String SYSTEM_UI = "com.android.systemui";

    private HomeSentinelPause() {}

    static boolean shouldRecover(State state) {
        return state == State.ACTIVE;
    }

    /** Update the hand-off state from a positively observed foreground event. */
    static State afterForeground(State state, String foregroundPackage, String foregroundClass) {
        if (state == null) state = State.ACTIVE;
        if (foregroundPackage == null || SYSTEM_UI.equals(foregroundPackage)
                || "android".equals(foregroundPackage)) return state;
        if (DUMBTV_PKG.equals(foregroundPackage)) return State.ACTIVE;
        // An explicit Amazon Home hand-off survives launching and closing ordinary apps.
        // Only manually returning to DumbTV releases this stronger hold.
        if (state == State.AMAZON_HOME_HOLD) return state;

        boolean amazonUi = AMAZON_LAUNCHER.equals(foregroundPackage)
                || AMAZON_SETTINGS.equals(foregroundPackage)
                || AMAZON_NOTIFICATIONS.equals(foregroundPackage)
                || AMAZON_DEVICE_CONTROL.equals(foregroundPackage);
        if (amazonUi) {
            // Returning from the settings route to Amazon Home resumes normal takeover.
            if (state == State.AMAZON_SETTINGS && AMAZON_LAUNCHER.equals(foregroundPackage)
                    && AMAZON_HOME.equals(foregroundClass)) return State.ACTIVE;
            return state;
        }
        return State.ACTIVE;
    }
}
