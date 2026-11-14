package tv.dumbtv.home;

/** Foreground package classification for the Fire OS 6 manual Amazon-home pause. */
final class KeeperPause {
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_SETTINGS = "com.amazon.tv.settings.v2";
    private static final String AMAZON_NOTIFICATIONS = "com.amazon.tv.notificationcenter";
    private static final String AMAZON_DEVICE_CONTROL = "com.amazon.tv.devicecontrolsettings";
    private static final String DUMBTV_PKG = "tv.dumbtv.home";
    private static final String SYSTEM_UI = "com.android.systemui";

    private KeeperPause() {}

    /**
     * Amazon's Settings list launches some sections from separate Amazon packages. Keep the
     * pause across those first-party screens; resume only after a real third-party app opens.
     */
    static boolean shouldResume(String foregroundPackage) {
        return foregroundPackage != null
                && !AMAZON_LAUNCHER.equals(foregroundPackage)
                && !AMAZON_SETTINGS.equals(foregroundPackage)
                && !AMAZON_NOTIFICATIONS.equals(foregroundPackage)
                && !AMAZON_DEVICE_CONTROL.equals(foregroundPackage)
                && !DUMBTV_PKG.equals(foregroundPackage)
                && !SYSTEM_UI.equals(foregroundPackage)
                && !"android".equals(foregroundPackage);
    }
}
