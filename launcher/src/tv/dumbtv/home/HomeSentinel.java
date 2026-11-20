package tv.dumbtv.home;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * Watches for Amazon's launcher coming back to the foreground and restores dumbtv home.
 *
 * This build targets Fire OS 6 (Android 7.1.2, API 25). HomeSentinel combines UsageStats
 * foreground events with ActivityManager logcat when READ_LOGS is granted. It only
 * intervenes after a positive Amazon Home event and stops as soon as another app is observed.
 */
public final class HomeSentinel extends Service {
    static final String AMAZON_PKG = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String DUMBTV_PKG = "tv.dumbtv.home";
    private static final long INITIAL_EVENT_LOOKBACK_MS = 24L * 60 * 60 * 1000;
    private static final long POLL_MS = 250;
    private static final long VERIFY_MS = 800;
    private static final long RETRY_MS = 650;
    private static final int MAX_ATTEMPTS = 3;
    private static final long STABLE_HOME_MS = 30_000;
    private static final String STATE_PREFS = "homesentinel_state";
    private static final String TAKEOVER_SETTING = "dumbtv_homesentinel_enabled";
    private static final String ACTION_HOME_VISIBILITY = "tv.dumbtv.home.action.HOME_VISIBILITY";
    private static final String EXTRA_HOME_READY = "home_ready";
    private static final long LOGCAT_RESTART_MIN_MS = 5_000;
    private static final long LOGCAT_RESTART_MAX_MS = 60_000;
    // A quiet ActivityManager stream is normal; this only detects a reader thread that died.
    private static final long LOGCAT_WATCHDOG_MS = 30_000;
    private static final long DEBUG_LOG_MAX_BYTES = 128 * 1024;

    private static volatile HomeSentinelPause.State pauseState = HomeSentinelPause.State.ACTIVE;
    private HandlerThread thread;
    private Handler worker;
    private UsageStatsManager usage;
    private final UsageEventCursor eventCursor = new UsageEventCursor();
    private final HomeSentinelRecoverySession recoverySession = new HomeSentinelRecoverySession();
    private Runnable pendingRetry;
    private Runnable pendingVerify;
    private long screenOffTime;
    private String lastTop;
    private String lastTopClass;
    private long lastTopTimestamp;
    private boolean lastUsageQuerySucceeded;
    private boolean homeReady;
    private long stableHomeStartedAt;
    private boolean recovering;
    private int attempts;
    private volatile boolean logcatStopping;
    private long lastLogcatRecoveryMs;
    private volatile Thread logcatThread;
    private Process logcatProcess;
    private final Object logcatLock = new Object();

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try {
                check();
            } catch (RuntimeException e) {
                note("poll failed: " + e);
            }
            if (worker != null) worker.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable stableHome = this::verifyStableHome;

    /** Restarts only a dead reader thread; an idle logcat stream is not considered unhealthy. */
    private final Runnable logcatWatchdog = new Runnable() {
        @Override public void run() {
            if (logcatStopping) return;
            Thread reader = logcatThread;
            if (reader != null && !reader.isAlive()) {
                note("ActivityManager logcat reader thread stopped unexpectedly; restarting");
                synchronized (logcatLock) {
                    if (logcatProcess != null) {
                        logcatProcess.destroy();
                        logcatProcess = null;
                    }
                }
                startLogcatReader();
            }
            if (worker != null && !logcatStopping) {
                worker.postDelayed(this, LOGCAT_WATCHDOG_MS);
            }
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (worker != null) worker.post(() -> handleScreen(action));
        }
    };

    /** All foreground/recovery state is confined to the HomeSentinel worker thread. */
    private void handleScreen(String action) {
        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            long now = System.currentTimeMillis();
            // Refresh the event history before recording the screen-off state. The polling
            // window may have missed a delayed UsageStats event immediately before standby.
            updateTop(now - 10_000, now);
            invalidateStableHome();
            cancelRecovery("screen off");
            screenOffTime = now;
            note("screen off; last foreground=" + lastTop + "/" + lastTopClass);
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            long now = System.currentTimeMillis();
            // Do not require a successful screen-off snapshot. A missed/stale snapshot must
            // not permanently suppress wake recovery after a long suspend.
            long from = eventCursor.lastTimestamp() == 0
                    ? Math.max(0, now - INITIAL_EVENT_LOOKBACK_MS)
                    : Math.max(0, (screenOffTime == 0 ? now : screenOffTime) - 10_000);
            updateTop(from, now);
            updatePauseState(HomeSentinelPause.afterForeground(pauseState, lastTop, lastTopClass));
            if (isAmazonHome() && HomeSentinelPause.shouldRecover(pauseState)) {
                note("screen on; restoring last foreground Amazon Home");
                beginRecovery();
            } else if (DUMBTV_PKG.equals(lastTop)) {
                scheduleStableHome();
                cancelRecovery("screen on; DumbTV remains foreground");
            } else {
                invalidateStableHome();
                cancelRecovery("screen on; foreground=" + lastTop + "/" + lastTopClass);
            }
            screenOffTime = 0;
        }
    }

    static void pause(Context context) {
        setPersistedPauseState(context, HomeSentinelPause.State.AMAZON_SETTINGS);
    }

    private static void setPersistedPauseState(Context context, HomeSentinelPause.State state) {
        pauseState = state;
        if (!context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("pause_state", state.name()).commit()) {
            note("could not persist HomeSentinel pause state");
        }
    }

    private void updatePauseState(HomeSentinelPause.State state) {
        if (state == null) state = HomeSentinelPause.State.ACTIVE;
        if (pauseState == state) return;
        setPersistedPauseState(this, state);
        if (!HomeSentinelPause.shouldRecover(state)) cancelRecovery("takeover pause state changed");
    }

    static boolean takeoverEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), TAKEOVER_SETTING, 1) == 1;
    }

    private boolean takeoverEnabled() {
        return takeoverEnabled(this);
    }

    private boolean displayInteractive() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    private boolean recoveryEligible() {
        return lastUsageQuerySucceeded && lastTopTimestamp > 0 && takeoverEnabled()
                && displayInteractive() && HomeSentinelPause.shouldRecover(pauseState)
                && isAmazonHome();
    }

    /** Reports real Home-window focus from the Activity, not merely a task-ordering result. */
    static void reportHomeVisibility(Context context, boolean ready) {
        Intent intent = new Intent(context, HomeSentinel.class)
                .setAction(ACTION_HOME_VISIBILITY)
                .putExtra(EXTRA_HOME_READY, ready);
        context.startService(intent);
    }

    private void handleHomeVisibility(boolean ready) {
        if (!ready || !displayInteractive()) {
            invalidateStableHome();
            cancelRecovery("Home window lost focus");
            return;
        }
        homeReady = true;
        long now = System.currentTimeMillis();
        updateTop(Math.max(0, now - 10_000), now);
        if (DUMBTV_PKG.equals(lastTop)) scheduleStableHome();
        else note("Home window has focus; waiting for UsageStats foreground confirmation");
    }

    private int storedFailures() {
        return getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getInt("recovery_failures", 0);
    }

    private boolean storeFailures(int failures) {
        attempts = failures;
        boolean saved = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putInt("recovery_failures", failures).commit();
        if (!saved) note("could not persist recovery failure count");
        return saved;
    }

    /** Opens Amazon's own home/settings screen without allowing HomeSentinel to reclaim it. */
    static void openAmazonHome(Context c) {
        setPersistedPauseState(c, HomeSentinelPause.State.AMAZON_HOME_HOLD);
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(AMAZON_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            setPersistedPauseState(c, HomeSentinelPause.State.ACTIVE);
            note("Amazon Home could not be opened: " + e);
        }
    }

    static void start(Context c) {
        c.startService(new Intent(c, HomeSentinel.class));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();
        usage = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        String savedPause = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getString("pause_state", HomeSentinelPause.State.ACTIVE.name());
        try {
            pauseState = HomeSentinelPause.State.valueOf(savedPause);
        } catch (IllegalArgumentException ignored) {
            pauseState = HomeSentinelPause.State.ACTIVE;
        }
        attempts = storedFailures();
        startForeground(1, new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("dumbtv")
                .setContentText("HomeSentinel")
                .build());
        thread = new HandlerThread("homesentinel");
        thread.start();
        worker = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            registerReceiver(screen, filter);
            note("screen receiver registered");
        } catch (RuntimeException e) {
            // UsageStats polling remains an independent recovery path if Fire OS refuses
            // or drops this dynamic receiver.
            note("screen receiver registration failed: " + e);
        }
        startLogcatReader();
        worker.postDelayed(logcatWatchdog, LOGCAT_WATCHDOG_MS);
        worker.post(poll);
        worker.postDelayed(this::firstStart, 2500);
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.removeCallbacks(poll);
            clearPendingRecoveryCallbacks();
            worker.removeCallbacks(logcatWatchdog);
            worker.removeCallbacks(stableHome);
        }
        try {
            unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
        }
        stopLogcatReader();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    /** Writes to logcat, and to homesentinel.log when the optional debug marker exists. */
    static void note(String msg) {
        android.util.Log.i("dumbtvtest", msg);
        java.io.File marker = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/tv.dumbtv.home/files/debug");
        if (!marker.exists()) return;
        try {
            java.io.File f = new java.io.File(marker.getParentFile(), "homesentinel.log");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            if (f.length() > DEBUG_LOG_MAX_BYTES) {
                // Bound optional diagnostics so the debug file cannot grow indefinitely.
                new FileOutputStream(f, false).close();
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            try {
                out.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception e) {
            android.util.Log.w("dumbtvtest", "could not append HomeSentinel log", e);
        }
    }


    /**
     * Reads only ActivityManager's log stream. Fire OS 6 installation grants READ_LOGS
     * through ADB; if unavailable, UsageStats and screen broadcasts remain active.
     */
    private void startLogcatReader() {
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            note("logcat reader unavailable: READ_LOGS not granted");
            return;
        }
        synchronized (logcatLock) {
            if (logcatThread != null && logcatThread.isAlive()) return;
            if (logcatProcess != null) {
                logcatProcess.destroy();
                logcatProcess = null;
            }
            logcatStopping = false;
            Thread reader = new Thread(this::readLogcatLoop, "homesentinel-logcat");
            reader.setDaemon(true);
            logcatThread = reader;
            reader.start();
        }
    }

    private void stopLogcatReader() {
        logcatStopping = true;
        synchronized (logcatLock) {
            if (logcatProcess != null) {
                logcatProcess.destroy();
                logcatProcess = null;
            }
        }
        if (logcatThread != null) logcatThread.interrupt();
    }

    private void readLogcatLoop() {
        long restartDelay = LOGCAT_RESTART_MIN_MS;
        while (!logcatStopping) {
            Process process = null;
            BufferedReader reader = null;
            boolean streamStarted = false;
            try {
                // Start at the live edge; replaying the existing ring buffer could mistake
                // an old Amazon Home transition for a fresh wake event.
                process = new ProcessBuilder("/system/bin/logcat", "-T", "1", "-v", "epoch",
                        "-s", "ActivityManager:I").redirectErrorStream(true).start();
                synchronized (logcatLock) {
                    if (logcatStopping) {
                        process.destroy();
                        break;
                    }
                    logcatProcess = process;
                }
                reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                note("ActivityManager logcat reader started");
                streamStarted = true;
                String line;
                while (!logcatStopping && (line = reader.readLine()) != null) {
                    if (HomeSentinelLogcat.matches(line) && worker != null) {
                        // Epoch timestamps let the worker reject buffered records that arrive
                        // after a newer UsageStats foreground transition.
                        final long eventTime = HomeSentinelLogcat.timestampMillis(line);
                        if (eventTime > 0) worker.post(() -> handleAmazonHomeDetected(eventTime));
                    }
                }
                // An unexpected EOF is a failed stream, even if process creation succeeded.
                streamStarted = false;
                if (!logcatStopping) note("ActivityManager logcat stream ended");
            } catch (Exception e) {
                if (!logcatStopping) note("ActivityManager logcat reader failed: " + e.getClass().getSimpleName());
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Exception ignored) {}
                }
                if (process != null) process.destroy();
                synchronized (logcatLock) {
                    if (logcatProcess == process) logcatProcess = null;
                }
            }
            if (logcatStopping) break;
            try {
                Thread.sleep(restartDelay);
            } catch (InterruptedException ignored) {
                if (logcatStopping) break;
            }
            restartDelay = streamStarted ? LOGCAT_RESTART_MIN_MS
                    : Math.min(LOGCAT_RESTART_MAX_MS, restartDelay * 2);
        }
    }

    private void handleAmazonHomeDetected(long eventTimestamp) {
        if (!HomeSentinelLogcat.isCurrentForegroundEvent(eventTimestamp, lastTopTimestamp,
                lastTop, lastTopClass)) {
            note("ignored stale ActivityManager foreground record");
            return;
        }
        lastTopTimestamp = eventTimestamp;
        lastTop = AMAZON_PKG;
        lastTopClass = AMAZON_HOME;
        updatePauseState(HomeSentinelPause.afterForeground(pauseState, AMAZON_PKG, AMAZON_HOME));
        if (!HomeSentinelPause.shouldRecover(pauseState)) return;
        // Never launch an activity in response to a log event while the TV display is off.
        // This watcher must not turn standby into an unintended screen wake.
        android.os.PowerManager power = (android.os.PowerManager)
                getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        // A single foreground transition can produce both Displayed and Resumed records.
        // Debounce them so one Amazon Home launch cannot cause repeated takeovers.
        if (lastLogcatRecoveryMs != 0 && now - lastLogcatRecoveryMs < 3_000) return;
        lastLogcatRecoveryMs = now;
        updatePauseState(HomeSentinelPause.afterForeground(pauseState, lastTop, lastTopClass));
        note("logcat detected Amazon Home foreground transition");
        beginRecovery();
    }

    private void firstStart() {
        // Service restarts are not permission to steal foreground. Only recover after a
        // positively observed Amazon Home event while the display is awake and takeover
        // remains enabled. Unknown foreground state means wait.
        long now = System.currentTimeMillis();
        updateTop(Math.max(0, now - INITIAL_EVENT_LOOKBACK_MS), now);
        updatePauseState(HomeSentinelPause.afterForeground(pauseState, lastTop, lastTopClass));
        // The 24-hour query reconstructs standby history; it is not fresh permission
        // to take focus when a sticky service starts. A live transition or screen-on path
        // is required to authorize recovery.
        if (recoveryEligible() && lastTopTimestamp >= now - 5_000) {
            note("service start: fresh Amazon Home foreground; recovery eligible");
            beginRecovery();
        } else if (DUMBTV_PKG.equals(lastTop)) {
            note("service start: DumbTV already foreground; checking for a stable session");
            scheduleStableHome();
        } else {
            note("service start: no eligible Amazon Home takeover; foreground="
                    + lastTop + "/" + lastTopClass);
        }
    }

    private void check() {
        long now = System.currentTimeMillis();
        // Query from the last event we actually consumed, not an arbitrary four-second
        // window. Handler callbacks pause during deep sleep, so the next poll may run long
        // after wake and otherwise miss the foreground event that should trigger recovery.
        long from = eventCursor.lastTimestamp() == 0 ? now - 10_000
                : Math.max(0, eventCursor.lastTimestamp() - 1);
        boolean changed = updateTop(from, now);
        if (!changed) return;
        if (isAmazonHome()) {
            updatePauseState(HomeSentinelPause.afterForeground(pauseState, lastTop, lastTopClass));
            if (recoveryEligible()) beginRecovery();
            else cancelRecovery("Amazon Home takeover is paused or disabled");
            return;
        }
        updatePauseState(HomeSentinelPause.afterForeground(pauseState, lastTop, lastTopClass));
        if (DUMBTV_PKG.equals(lastTop)) {
            if (homeReady) scheduleStableHome();
            cancelRecovery("DumbTV foreground");
            return;
        }
        invalidateStableHome();
        if (lastTop == null || SYSTEM_UI.equals(lastTop) || "android".equals(lastTop)) {
            cancelRecovery("system UI foreground");
            return;
        }
        // Amazon's launcher package contains screens other than Home (for example its
        // settings grid). Treat those as part of the paused Amazon interface, not as the
        // user leaving it. Resume takeover only after a genuinely different app appears.
        if (AMAZON_PKG.equals(lastTop)) {
            cancelRecovery("Amazon launcher foreground outside Home");
            return;
        }
        if (!DUMBTV_PKG.equals(lastTop)) {
            cancelRecovery("another app foreground: " + lastTop);

        } else {
            cancelRecovery("DumbTV foreground");
        }
    }

    /**
     * UsageStats is an event history, not a current-window API. Keep the latest positively
     * observed foreground event across empty polling windows; never infer a new foreground app
     * from an empty result.
     */
    private boolean updateTop(long from, long now) {
        lastUsageQuerySucceeded = false;
        if (usage == null) return false;
        try {
            UsageEvents events = usage.queryEvents(Math.max(0, from), now);
            if (events == null) return false;
            boolean changed = false;
            eventCursor.beginQuery();
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND
                        || !eventCursor.accept(event.getTimeStamp(),
                                event.getPackageName(), event.getClassName())) continue;
                long eventTime = event.getTimeStamp();
                String eventPackage = event.getPackageName();
                String eventClass = event.getClassName();
                // UsageStats can return events buffered before a newer logcat record.
                // Consume them in the cursor, but never let them roll foreground state back.
                if (eventTime < lastTopTimestamp
                        || (eventTime == lastTopTimestamp
                        && (!equals(eventPackage, lastTop) || !equals(eventClass, lastTopClass)))) continue;
                lastTop = eventPackage;
                lastTopClass = eventClass;
                lastTopTimestamp = eventTime;
                changed = true;
            }
            lastUsageQuerySucceeded = true;
            return changed;
        } catch (RuntimeException e) {
            note("UsageStats query failed: " + e);
            return false;
        }
    }

    private static boolean equals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean isAmazonHome() {
        return AMAZON_PKG.equals(lastTop) && AMAZON_HOME.equals(lastTopClass);
    }

    private void beginRecovery() {
        if (!recoveryEligible() || recovering) return;
        invalidateStableHome();
        attempts = storedFailures();
        if (attempts >= MAX_ATTEMPTS) {
            note("automatic takeover circuit open after " + attempts + " failed attempts; leaving Amazon Home available");
            return;
        }
        recovering = true;
        long session = recoverySession.begin();
        note("recovery started with " + attempts + " prior failed attempts");
        attemptRecovery(session);
    }

    private void attemptRecovery(long session) {
        if (!recovering || !recoverySession.isCurrent(session)) return;
        long now = System.currentTimeMillis();
        updateTop(Math.max(0, eventCursor.lastTimestamp() - 1), now);
        if (!lastUsageQuerySucceeded || !recoveryEligible()) {
            cancelRecovery("recovery no longer eligible after foreground recheck");
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            cancelRecovery("recovery exhausted after " + attempts + " attempts");
            return;
        }
        attempts++;
        if (!storeFailures(attempts)) {
            cancelRecovery("could not persist the recovery budget; leaving Amazon Home available");
            return;
        }
        note("recovery attempt " + attempts + "/" + MAX_ATTEMPTS);
        boolean moved = false;
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            try {
                for (ActivityManager.AppTask task : manager.getAppTasks()) {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    String topPackage = info.topActivity == null ? ""
                            : info.topActivity.getPackageName();
                    String basePackage = info.baseActivity == null ? ""
                            : info.baseActivity.getPackageName();
                    // Never reorder an unrelated app task. Only the DumbTV task is eligible.
                    if (!DUMBTV_PKG.equals(topPackage) && !DUMBTV_PKG.equals(basePackage)) continue;
                    task.moveToFront();
                    moved = true;
                    break;
                }
            } catch (RuntimeException e) {
                note("moveToFront failed: " + e);
            }
        }
        // If task reordering did not verify on the first pass, use the independent direct
        // activity-launch path on subsequent attempts instead of repeating moveToFront only.
        if (!moved || attempts > 1) raiseHome();
        scheduleVerify(session);
    }

    private void scheduleVerify(long session) {
        if (pendingVerify != null) worker.removeCallbacks(pendingVerify);
        pendingVerify = () -> {
            pendingVerify = null;
            verifyRecovery(session);
        };
        worker.postDelayed(pendingVerify, VERIFY_MS);
    }

    private void scheduleRetry(long session) {
        if (pendingRetry != null) worker.removeCallbacks(pendingRetry);
        pendingRetry = () -> {
            pendingRetry = null;
            attemptRecovery(session);
        };
        worker.postDelayed(pendingRetry, RETRY_MS);
    }

    private void verifyRecovery(long session) {
        if (!recovering || !recoverySession.isCurrent(session)) return;
        long now = System.currentTimeMillis();
        updateTop(Math.max(0, eventCursor.lastTimestamp() - 1), now);
        if (!lastUsageQuerySucceeded) {
            cancelRecovery("foreground verification unavailable; retaining recovery budget");
            return;
        }
        if (DUMBTV_PKG.equals(lastTop)) {
            note("recovery verified: DumbTV foreground; waiting for Home window readiness");
            if (homeReady) scheduleStableHome();
            recovering = false;
            recoverySession.invalidate();
            clearPendingRecoveryCallbacks();
            return;
        }
        if (!recoveryEligible()) {
            cancelRecovery("recovery stopped; foreground=" + lastTop + "/" + lastTopClass);
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            cancelRecovery("recovery failed after " + attempts + " attempts");
            return;
        }
        scheduleRetry(session);
    }

    private void clearPendingRecoveryCallbacks() {
        if (worker != null) {
            if (pendingRetry != null) worker.removeCallbacks(pendingRetry);
            if (pendingVerify != null) worker.removeCallbacks(pendingVerify);
        }
        pendingRetry = null;
        pendingVerify = null;
    }

    private void cancelRecovery(String reason) {
        if (recovering) note("recovery stopped: " + reason);
        recovering = false;
        recoverySession.invalidate();
        clearPendingRecoveryCallbacks();
    }

    private void scheduleStableHome() {
        if (worker == null || !homeReady || !displayInteractive()) return;
        worker.removeCallbacks(stableHome);
        stableHomeStartedAt = System.currentTimeMillis();
        worker.postDelayed(stableHome, STABLE_HOME_MS);
    }

    private void invalidateStableHome() {
        homeReady = false;
        stableHomeStartedAt = 0;
        if (worker != null) worker.removeCallbacks(stableHome);
    }

    /** Requires a successful foreground-history query whose latest event is DumbTV. */
    private boolean confirmDumbTvForeground(long since, long now) {
        if (usage == null) return false;
        try {
            UsageEvents events = usage.queryEvents(Math.max(0, since), now);
            if (events == null) return false;
            long latest = 0;
            String latestPackage = null;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
                if (event.getTimeStamp() >= latest) {
                    latest = event.getTimeStamp();
                    latestPackage = event.getPackageName();
                }
            }
            return latest >= since && DUMBTV_PKG.equals(latestPackage);
        } catch (RuntimeException e) {
            note("stable Home UsageStats confirmation failed: " + e);
            return false;
        }
    }

    private void verifyStableHome() {
        long startedAt = stableHomeStartedAt;
        long now = System.currentTimeMillis();
        boolean confirmed = startedAt > 0 && confirmDumbTvForeground(startedAt - 5_000, now);
        if (HomeSentinelStability.canClearCircuit(homeReady, displayInteractive(),
                lastUsageQuerySucceeded, confirmed)) {
            note("DumbTV rendered Home and remained foreground for " + STABLE_HOME_MS
                    + "ms; clearing recovery circuit");
            if (!storeFailures(0)) note("could not persist cleared recovery budget");
        } else {
            note("DumbTV Home stability was not confirmed; retaining recovery failure count");
        }
        stableHomeStartedAt = 0;
    }

    private void raiseHome() {
        Intent intent = new Intent(this, Home.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // Fire OS 6 is Android 7.1 (API 25), before background-activity launch limits.
        // Try the direct route first; the exact alarm remains a fallback if the request throws.
        try {
            startActivity(intent);
            note("Home activity launch requested directly");
            return;
        } catch (RuntimeException e) {
            note("direct Home launch failed: " + e);
        }

        PendingIntent pending = PendingIntent.getActivity(this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            try {
                alarms.setExact(AlarmManager.RTC, System.currentTimeMillis(), pending);
                note("Home activity alarm fallback requested");
            } catch (RuntimeException e) {
                note("Home activity alarm fallback failed: " + e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        // Permissions may have been repaired while this sticky service was already running.
        // Re-check on every start request so ActivityManager monitoring can resume immediately.
        startLogcatReader();
        if (intent != null && ACTION_HOME_VISIBILITY.equals(intent.getAction()) && worker != null) {
            final boolean ready = intent.getBooleanExtra(EXTRA_HOME_READY, false);
            worker.post(() -> handleHomeVisibility(ready));
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
