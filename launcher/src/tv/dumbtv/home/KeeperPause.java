package tv.dumbtv.home;

/** Foreground package classification for the Fire OS 6 manual Amazon-home pause. */
final class KeeperPause {
    private static final String AMAZON_PKG = "com.amazon.tv.launcher";
    private static final String DUMBTV_PKG = "tv.dumbtv.home";
    private static final String SYSTEM_UI = "com.android.systemui";

    private KeeperPause() {}

    /** Only a real third-party app leaving Amazon's package resumes the takeover. */
    static boolean shouldResume(String foregroundPackage) {
        return foregroundPackage != null
                && !AMAZON_PKG.equals(foregroundPackage)
                && !DUMBTV_PKG.equals(foregroundPackage)
                && !SYSTEM_UI.equals(foregroundPackage)
                && !"android".equals(foregroundPackage);
    }
}
