package tv.dumbtv.home;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Locale;

/** Preview built from the same widget views and placement engine used on the home screen. */
final class WidgetPreview extends WidgetGridView {
    private final TextView customText;
    private final TextView clockTime;
    private final TextView clockDate;

    WidgetPreview(Context context) {
        super(context);
        int surface = Ui.tileSurface(context, true);
        boolean backgrounds = "on".equals(Config.get(context).get(Config.WIDGET_BACKGROUNDS));

        LinearLayout clock = new LinearLayout(context);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        clock.setPadding(Ui.dp(context, 18), Ui.dp(context, 4),
                Ui.dp(context, 18), Ui.dp(context, 4));
        clock.setBackground(backgrounds ? Ui.surface(context, surface, Ui.dp(context, 12)) : null);
        clockTime = Ui.text(context, 32, Ui.TEXT, Ui.LIGHT);
        String clockMode = Config.get(context).get(Config.CLOCK);
        boolean use24Hour = "24h".equals(clockMode)
                || "auto".equals(clockMode) && DateFormat.is24HourFormat(context);
        clockTime.setText(DateFormat.format(use24Hour ? "HH:mm" : "h:mm a", new Date()));
        clockDate = Ui.text(context, 13, Ui.MUTED, Ui.MEDIUM);
        clockDate.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"),
                new Date()).toString().toUpperCase(Locale.ROOT));
        clockDate.setPadding(Ui.dp(context, 2), Ui.dp(context, 2), 0, 0);
        clock.addView(clockTime);
        clock.addView(clockDate);
        addWidgetView("clock", clock);

        FrameLayout memory = new FrameLayout(context);
        memory.setBackground(backgrounds ? Ui.surface(context, surface, Ui.dp(context, 12)) : null);
        Meter meter = new Meter(context);
        meter.setWords("Memory", "%1$d MB free of %2$d MB", "Running low");
        meter.setAccent(Config.get(context).accent());
        meter.update((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE));
        memory.addView(meter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        addWidgetView("memory", memory);

        addWidgetView("recent", widgetText(context, "Recently opened\nRecent app names",
                backgrounds, surface));

        ConnectivityManager connectivity = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = connectivity == null ? null : connectivity.getActiveNetworkInfo();
        String networkState = network == null || !network.isConnected() ? "Disconnected"
                : "Connected · " + network.getTypeName();
        addWidgetView("network", widgetText(context, "Network\n" + networkState,
                backgrounds, surface));

        String storage = "Unavailable";
        try {
            StatFs stats = new StatFs(Environment.getDataDirectory().getPath());
            storage = Formatter.formatFileSize(context, stats.getAvailableBytes())
                    + " free of " + Formatter.formatFileSize(context, stats.getTotalBytes());
        } catch (RuntimeException ignored) {
            // Keep the preview usable if storage statistics are unavailable.
        }
        addWidgetView("storage", widgetText(context, "Storage\n" + storage,
                backgrounds, surface));

        customText = widgetText(context, "Custom text", backgrounds, surface);
        addWidgetView("text", customText);

        TextView diagnostics = widgetText(context, WidgetDiagnostics.snapshot(context),
                backgrounds, surface);
        diagnostics.setMaxLines(14);
        diagnostics.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        diagnostics.setPadding(Ui.dp(context, 10), Ui.dp(context, 5),
                Ui.dp(context, 10), Ui.dp(context, 5));
        addWidgetView("diagnostics", diagnostics);

        String deviceInfo = "Device information\n" + Build.MODEL
                + "\nAndroid " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT;
        TextView device = widgetText(context, deviceInfo, backgrounds, surface);
        device.setMaxLines(5);
        device.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        addWidgetView("device", device);
    }

    void setLayout(WidgetLayout value, String text) {
        super.setLayout(value);
        Config config = Config.get(getContext());
        for (WidgetVisibility.Definition definition : WidgetVisibility.ALL) {
            setWidgetEnabled(definition.id, WidgetVisibility.isEnabled(config, definition.id));
        }
        customText.setText(text == null || text.trim().isEmpty() ? "Custom text" : text.trim());
    }

    void setWidgetFontSize(String id, int size) {
        if ("clock".equals(id)) {
            clockTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
            clockDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(10, Math.round(size * 0.4f)));
            return;
        }
        for (int i = 0; i < getChildCount(); i++) {
            if (id.equals(widgetId(getChildAt(i))) && getChildAt(i) instanceof TextView) {
                ((TextView) getChildAt(i)).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
                return;
            }
        }
    }

    private static TextView widgetText(Context context, String value,
                                       boolean backgrounds, int surface) {
        TextView view = Ui.text(context, 14, Ui.TEXT, Ui.MEDIUM);
        view.setText(value);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(Ui.dp(context, 16), Ui.dp(context, 8),
                Ui.dp(context, 16), Ui.dp(context, 8));
        view.setMaxLines(3);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setBackground(backgrounds ? Ui.surface(context, surface, Ui.dp(context, 12)) : null);
        return view;
    }
}
