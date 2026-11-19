package tv.dumbtv.home;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.text.format.Formatter;

import java.util.List;
import java.util.Locale;

/** Read-only process, permission and HomeSentinel-service diagnostics; never changes HomeSentinel state. */
final class WidgetDiagnostics {
    private WidgetDiagnostics() {}

    static String snapshot(Context context) {
        return "DumbTV diagnostics\nHomeSentinel · " + HomeSentinelState(context)
                + "\nApp memory · " + memory(context)
                + "\nPermissions · " + permissionSummary(context);
    }

    private static String memory(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return "Unavailable";
            android.os.Debug.MemoryInfo[] infos = manager.getProcessMemoryInfo(
                    new int[] {Process.myPid()});
            if (infos == null || infos.length == 0) return "Unavailable";
            return Formatter.formatFileSize(context, infos[0].getTotalPss() * 1024L) + " PSS";
        } catch (RuntimeException e) {
            return "Unavailable";
        }
    }

    private static String HomeSentinelState(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return "Status unavailable";
            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(100);
            String name = context.getPackageName() + ".HomeSentinel";
            if (services != null) {
                for (ActivityManager.RunningServiceInfo service : services) {
                    if (service.service != null && name.equals(service.service.getClassName())) {
                        return "Service running · internals unverified";
                    }
                }
            }
            return "Service not reported running";
        } catch (RuntimeException e) {
            return "Status unavailable";
        }
    }

    private static String permissionSummary(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            String[] requested = info.requestedPermissions;
            if (requested == null || requested.length == 0) return "No permissions requested";
            int granted = 0;
            for (int i = 0; i < requested.length; i++) {
                String permission = requested[i];
                boolean allowed;
                if ("android.permission.PACKAGE_USAGE_STATS".equals(permission)) {
                    allowed = usageAccess(context);
                } else {
                    allowed = pm.checkPermission(permission, context.getPackageName())
                            == PackageManager.PERMISSION_GRANTED;
                }
                if (allowed) granted++;
            }
            return granted + "/" + requested.length + " granted";
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "Status unavailable";
        }
    }

    private static boolean usageAccess(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Device details stay separate from app and HomeSentinel diagnostics. */
    static String deviceInformation() {
        long minutes = android.os.SystemClock.elapsedRealtime() / 60000L;
        long days = minutes / 1440L;
        long hours = (minutes / 60L) % 24L;
        long mins = minutes % 60L;
        String uptime = days > 0 ? days + "d " + hours + "h"
                : hours > 0 ? hours + "h " + mins + "m" : mins + "m";
        return android.os.Build.MODEL + "\nAndroid " + android.os.Build.VERSION.RELEASE
                + " · API " + android.os.Build.VERSION.SDK_INT + "\nUptime " + uptime;
    }
}
