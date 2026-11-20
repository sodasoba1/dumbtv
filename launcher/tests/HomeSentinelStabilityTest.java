package tv.dumbtv.home;

/** Circuit-breaker reset requires every independent readiness signal. */
public final class HomeSentinelStabilityTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check(HomeSentinelStability.canClearCircuit(true, true, true, true),
                "confirmed stable Home clears circuit");
        check(!HomeSentinelStability.canClearCircuit(false, true, true, true),
                "activity pause prevents circuit reset");
        check(!HomeSentinelStability.canClearCircuit(true, false, true, true),
                "screen-off prevents circuit reset");
        check(!HomeSentinelStability.canClearCircuit(true, true, false, true),
                "failed UsageStats query prevents circuit reset");
        check(!HomeSentinelStability.canClearCircuit(true, true, true, false),
                "missing foreground event prevents circuit reset");
        System.out.println("HomeSentinelStabilityTest passed");
    }
}
