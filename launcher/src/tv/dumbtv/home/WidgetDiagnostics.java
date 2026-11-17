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

/** Read-only process, permission and Keeper-service diagnostics; never changes Keeper state. */
final class WidgetDiagnostics {
    private WidgetDiagnostics() {}

    static String snapshot(Context context) {
        StringBuilder out = new StringBuilder("DumbTV diagnostics");
        out.append("\nCPU time since start · ").append(cpuTime(Process.getElapsedCpuTime()));
        out.append("\nApp memory · ").append(memory(context));
        out.append("\nKeeper · ").append(keeperState(context));
        out.append("\nPermissions · ").append(permissionSummary(context));
        return out.toString();
    }

    private static String cpuTime(long millis) {
        return String.format(Locale.ROOT, "%.1f s", Math.max(0, millis) / 1000.0);
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

    private static String keeperState(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return "Status unavailable";
            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(100);
            String name = context.getPackageName() + ".Keeper";
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
            StringBuilder details = new StringBuilder();
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
                if (details.length() > 0) details.append(", ");
                details.append(shortName(permission)).append(allowed ? " ✓" : " ✕");
            }
            if (!java.util.Arrays.asList(requested).contains("android.permission.INTERNET")) {
                if (details.length() > 0) details.append(", ");
                details.append("INTERNET not requested");
            }
            return granted + "/" + requested.length + " granted\n" + details;
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

    private static String shortName(String permission) {
        int dot = permission.lastIndexOf('.');
        return dot < 0 ? permission : permission.substring(dot + 1);
    }
}
