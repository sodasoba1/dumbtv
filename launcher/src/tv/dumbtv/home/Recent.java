package tv.dumbtv.home;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import java.util.HashMap;
import java.util.Map;

/**
 * When each app was last opened, for Options › App order › Recently used.
 *
 * Opens from this launcher are recorded on every Fire OS with no permission. Where the app has
 * usage access (Fire OS 6, where install.sh grants it for the Keeper), UsageStats adds apps
 * opened some other way, by voice or from another app. The times are kept in private
 * preferences rather than home.conf: they are history, not settings.
 */
final class Recent {
    private static final String PREFS = "recent";
    private static final long WINDOW_MS = 90L * 24 * 60 * 60 * 1000;

    private Recent() {}

    static void opened(Context c, String pkg) {
        prefs(c).edit().putLong(pkg, System.currentTimeMillis()).apply();
    }

    /** Lightweight editor preview from locally recorded launches; never queries UsageStats. */
    static String preview(Context c) {
        List<Map.Entry<String, ?>> entries = new ArrayList<>(prefs(c).getAll().entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(
                b.getValue() instanceof Long ? (Long) b.getValue() : 0L,
                a.getValue() instanceof Long ? (Long) a.getValue() : 0L));
        StringBuilder out = new StringBuilder("Recently opened");
        int shown = 0;
        PackageManager pm = c.getPackageManager();
        for (Map.Entry<String, ?> entry : entries) {
            if (!(entry.getValue() instanceof Long)) continue;
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(entry.getKey(), 0));
                out.append(shown == 0 ? "\n" : "  ·  ").append(label);
                if (++shown == 3) break;
            } catch (PackageManager.NameNotFoundException ignored) {
                // The app may have been uninstalled since it was last opened.
            }
        }
        if (shown == 0) out.append("\nNo recent apps");
        return out.toString();
    }

    /** Last-used time per package. Does a binder call: call off the UI thread. */
    static Map<String, Long> times(Context c) {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (e.getValue() instanceof Long) out.put(e.getKey(), (Long) e.getValue());
        }
        UsageStatsManager usage = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage == null) return out;
        long now = System.currentTimeMillis();
        try {
            // Without usage access this returns an empty map rather than throwing.
            for (UsageStats s : usage.queryAndAggregateUsageStats(now - WINDOW_MS, now).values()) {
                Long seen = out.get(s.getPackageName());
                if (s.getLastTimeUsed() > (seen == null ? 0 : seen)) {
                    out.put(s.getPackageName(), s.getLastTimeUsed());
                }
            }
        } catch (RuntimeException ignored) {
            // Some builds throw instead; the launcher's own record still applies.
        }
        return out;
    }

    /** Newest first; apps with no record keep the order they came in. */
    static int compare(Map<String, Long> times, String a, String b) {
        Long ta = times.get(a), tb = times.get(b);
        return Long.compare(tb == null ? 0 : tb, ta == null ? 0 : ta);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
