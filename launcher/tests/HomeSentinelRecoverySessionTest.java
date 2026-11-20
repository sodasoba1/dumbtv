package tv.dumbtv.home;

/** Delayed work from an older recovery attempt cannot join a later session. */
public final class HomeSentinelRecoverySessionTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        HomeSentinelRecoverySession sessions = new HomeSentinelRecoverySession();
        long first = sessions.begin();
        check(sessions.isCurrent(first), "new recovery session is current");
        sessions.invalidate();
        check(!sessions.isCurrent(first), "cancelled recovery invalidates delayed work");
        long second = sessions.begin();
        check(sessions.isCurrent(second), "new recovery session becomes current");
        check(!sessions.isCurrent(first), "old retry cannot join a later recovery session");
        sessions.invalidate();
        check(!sessions.isCurrent(second), "screen-off or pause invalidates active session");
        System.out.println("HomeSentinelRecoverySessionTest passed");
    }
}
