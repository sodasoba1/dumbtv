package tv.dumbtv.home;

/** Invalidates delayed recovery work when foreground eligibility changes. */
final class HomeSentinelRecoverySession {
    private long generation;

    long begin() {
        return advance();
    }

    long invalidate() {
        return advance();
    }

    boolean isCurrent(long token) {
        return token != 0 && token == generation;
    }

    private long advance() {
        generation++;
        if (generation == 0) generation++;
        return generation;
    }
}
