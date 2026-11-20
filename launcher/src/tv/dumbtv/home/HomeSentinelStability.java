package tv.dumbtv.home;

/** Pure guard for clearing HomeSentinel's persisted recovery circuit. */
final class HomeSentinelStability {
    private HomeSentinelStability() {}

    static boolean canClearCircuit(boolean activityReady, boolean interactive,
                                   boolean usageQuerySucceeded, boolean foregroundConfirmed) {
        return activityReady && interactive && usageQuerySucceeded && foregroundConfirmed;
    }
}
