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
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * Watches for Amazon's launcher coming back to the foreground and restores dumbtv home.
 *
 * Fire OS 6 does not permit the accessibility Guard to run on this device. Keeper therefore
 * uses UsageStats foreground events. It only intervenes after a positive Amazon Home event,
 * and stops as soon as another app is observed.
 */
public final class Keeper extends Service {
    private static final String AMAZON_PKG = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String DUMBTV_PKG = "tv.dumbtv.home";
    private static final long POLL_MS = 250;
    private static final long VERIFY_MS = 800;
    private static final long RETRY_MS = 650;
    private static final int MAX_ATTEMPTS = 3;
    private static final long LOGCAT_RESTART_MIN_MS = 5_000;
    private static final long LOGCAT_RESTART_MAX_MS = 60_000;
    private static final long DEBUG_LOG_MAX_BYTES = 128 * 1024;

    private static volatile boolean paused;
    private HandlerThread thread;
    private Handler worker;
    private UsageStatsManager usage;
    private long lastEventTime;
    private long screenOffTime;
    private String lastTop;
    private String lastTopClass;
    private boolean recovering;
    private int attempts;
    private volatile boolean logcatStopping;
    private long lastLogcatRecoveryMs;
    private Thread logcatThread;
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

    private final Runnable verify = this::verifyRecovery;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (worker != null) worker.post(() -> handleScreen(action));
        }
    };

    /** All foreground/recovery state is confined to the Keeper worker thread. */
    private void handleScreen(String action) {
        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            long now = System.currentTimeMillis();
            // Refresh the event history before recording the screen-off state. The polling
            // window may have missed a delayed UsageStats event immediately before standby.
            updateTop(now - 10_000, now);
            screenOffTime = now;
            note("screen off; last foreground=" + lastTop + "/" + lastTopClass);
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            if (paused) return;
            long now = System.currentTimeMillis();
            // Do not require a successful screen-off snapshot. A missed/stale snapshot must
            // not permanently suppress wake recovery after a long suspend.
            updateTop(Math.max(0, (screenOffTime == 0 ? now : screenOffTime) - 10_000), now);
            if (isAmazonHome()) {
                note("screen on; restoring last foreground Amazon Home");
                beginRecovery();
            } else {
                cancelRecovery("screen on; foreground=" + lastTop + "/" + lastTopClass);
            }
            screenOffTime = 0;
        }
    }

    static void pause() {
        paused = true;
    }

    static void start(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 26) return;
        c.startService(new Intent(c, Keeper.class));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();
        usage = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        startForeground(1, new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("dumbtv")
                .setContentText("home keeper")
                .build());
        thread = new HandlerThread("keeper");
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
        worker.post(poll);
        worker.postDelayed(this::firstStart, 2500);
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.removeCallbacks(poll);
            worker.removeCallbacks(verify);
        }
        try {
            unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
        }
        stopLogcatReader();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    /** Writes to logcat, and to keeper.log when the optional debug marker exists. */
    static void note(String msg) {
        android.util.Log.i("dumbtvtest", msg);
        java.io.File marker = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/tv.dumbtv.home/files/debug");
        if (!marker.exists()) return;
        try {
            java.io.File f = new java.io.File(marker.getParentFile(), "keeper.log");
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
            android.util.Log.w("dumbtvtest", "could not append keeper log", e);
        }
    }


    /**
     * Reads only ActivityManager's log stream. Fire OS 6 installation grants READ_LOGS
     * through ADB; if unavailable, UsageStats and screen broadcasts remain active.
     */
    private void startLogcatReader() {
        if (android.os.Build.VERSION.SDK_INT >= 28) return;
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            note("logcat reader unavailable: READ_LOGS not granted");
            return;
        }
        logcatStopping = false;
        logcatThread = new Thread(this::readLogcatLoop, "keeper-logcat");
        logcatThread.setDaemon(true);
        logcatThread.start();
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
                process = new ProcessBuilder("/system/bin/logcat", "-v", "brief",
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
                    if (isAmazonHomeLogLine(line) && worker != null) {
                        // Do not persist raw system lines; they can contain other apps' data.
                        worker.post(this::handleAmazonHomeDetected);
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

    /** Match the exact Amazon Home activity and real activity-transition records. */
    private boolean isAmazonHomeLogLine(String line) {
        if (line == null || !line.contains(AMAZON_PKG)
                || !line.contains("HomeActivity_vNext")) return false;
        // START only means an activity launch was requested, not that it became visible.
        // Require a displayed/resumed record to avoid stealing focus during ordinary navigation.
        return line.contains("Displayed ") || line.contains("Resumed ")
                || line.contains("topResumedActivity");
    }

    private void handleAmazonHomeDetected() {
        if (paused) return;
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
        lastTop = AMAZON_PKG;
        lastTopClass = AMAZON_HOME;
        note("logcat detected Amazon Home foreground transition");
        beginRecovery();
    }

    private void firstStart() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null || !manager.getAppTasks().isEmpty()) return;
        note("first start: no DumbTV task");
        raiseHome();
    }

    private void check() {
        long now = System.currentTimeMillis();
        // Query from the last event we actually consumed, not an arbitrary four-second
        // window. Handler callbacks pause during deep sleep, so the next poll may run long
        // after wake and otherwise miss the foreground event that should trigger recovery.
        long from = lastEventTime == 0 ? now - 10_000 : Math.max(0, lastEventTime - 1);
        boolean changed = updateTop(from, now);
        if (!changed) return;
        if (isAmazonHome()) {
            if (!paused) beginRecovery();
            return;
        }
        if (lastTop == null || SYSTEM_UI.equals(lastTop) || "android".equals(lastTop)) {
            cancelRecovery("system UI foreground");
            return;
        }
        if (!DUMBTV_PKG.equals(lastTop)) {
            cancelRecovery("another app foreground: " + lastTop);
            paused = false;
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
        if (usage == null) return false;
        try {
            UsageEvents events = usage.queryEvents(Math.max(0, from), now);
            if (events == null) return false;
            boolean changed = false;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND
                        || event.getTimeStamp() < lastEventTime) continue;
                lastEventTime = event.getTimeStamp();
                lastTop = event.getPackageName();
                lastTopClass = event.getClassName();
                changed = true;
            }
            return changed;
        } catch (RuntimeException e) {
            note("UsageStats query failed: " + e);
            return false;
        }
    }

    private boolean isAmazonHome() {
        return AMAZON_PKG.equals(lastTop) && AMAZON_HOME.equals(lastTopClass);
    }

    private void beginRecovery() {
        if (paused || !isAmazonHome() || recovering) return;
        recovering = true;
        attempts = 0;
        worker.removeCallbacks(verify);
        note("recovery started");
        attemptRecovery();
    }

    private void attemptRecovery() {
        if (!recovering || paused || !isAmazonHome()) {
            cancelRecovery("recovery no longer eligible");
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            cancelRecovery("recovery exhausted after " + attempts + " attempts");
            return;
        }
        attempts++;
        note("recovery attempt " + attempts + "/" + MAX_ATTEMPTS);
        boolean moved = false;
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            try {
                for (ActivityManager.AppTask task : manager.getAppTasks()) {
                    String top = task.getTaskInfo().topActivity == null ? ""
                            : task.getTaskInfo().topActivity.getClassName();
                    String base = task.getTaskInfo().baseActivity == null ? ""
                            : task.getTaskInfo().baseActivity.getClassName();
                    if (top.isEmpty() && base.isEmpty()) continue;
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
        worker.removeCallbacks(verify);
        worker.postDelayed(verify, VERIFY_MS);
    }

    private void verifyRecovery() {
        updateTop(System.currentTimeMillis() - 10_000, System.currentTimeMillis());
        if (DUMBTV_PKG.equals(lastTop)) {
            note("recovery verified: DumbTV foreground");
            cancelRecovery("verified");
            return;
        }
        if (!isAmazonHome() || paused) {
            cancelRecovery("recovery stopped; foreground=" + lastTop + "/" + lastTopClass);
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            cancelRecovery("recovery failed after " + attempts + " attempts");
            return;
        }
        worker.postDelayed(this::attemptRecovery, RETRY_MS);
    }

    private void cancelRecovery(String reason) {
        if (recovering) note("recovery stopped: " + reason);
        recovering = false;
        attempts = 0;
        if (worker != null) worker.removeCallbacks(verify);
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
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
