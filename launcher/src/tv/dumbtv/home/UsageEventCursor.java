package tv.dumbtv.home;

import java.util.HashMap;
import java.util.Map;

/**
 * Deduplicates inclusive UsageStats query windows without discarding distinct events that share
 * the same millisecond timestamp.
 */
final class UsageEventCursor {
    private long lastTimestamp;
    private final Map<String, Integer> consumedAtTimestamp = new HashMap<>();
    private final Map<String, Integer> observedInQuery = new HashMap<>();

    void beginQuery() {
        observedInQuery.clear();
    }

    boolean accept(long timestamp, String packageName, String className) {
        if (timestamp < lastTimestamp) return false;
        if (timestamp > lastTimestamp) {
            lastTimestamp = timestamp;
            consumedAtTimestamp.clear();
            observedInQuery.clear();
        }

        String key = String.valueOf(packageName) + '\u0000' + String.valueOf(className);
        int occurrence = observedInQuery.getOrDefault(key, 0) + 1;
        observedInQuery.put(key, occurrence);

        int consumed = consumedAtTimestamp.getOrDefault(key, 0);
        if (occurrence <= consumed) return false;
        consumedAtTimestamp.put(key, occurrence);
        return true;
    }

    long lastTimestamp() {
        return lastTimestamp;
    }
}
