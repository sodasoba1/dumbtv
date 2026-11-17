package tv.dumbtv.home;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Preview built from the same widget views and placement engine used on the home screen. */
final class WidgetPreview extends WidgetGridView {
    private final TextView customText;

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
        TextView time = Ui.text(context, 32, Ui.TEXT, Ui.LIGHT);
        time.setText("10:42");
        TextView date = Ui.text(context, 13, Ui.MUTED, Ui.MEDIUM);
        date.setText("MONDAY  ·  17 NOV");
        date.setPadding(Ui.dp(context, 2), Ui.dp(context, 2), 0, 0);
        clock.addView(time);
        clock.addView(date);
        addWidgetView("clock", clock);

        FrameLayout memory = new FrameLayout(context);
        memory.setBackground(backgrounds ? Ui.surface(context, surface, Ui.dp(context, 12)) : null);
        Meter meter = new Meter(context);
        meter.setWords("Memory", "Free", "Running low");
        meter.setAccent(Config.get(context).accent());
        memory.addView(meter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        addWidgetView("memory", memory);

        addWidgetView("recent", widgetText(context, "Recently opened\nPrime Video  ·  YouTube",
                backgrounds, surface));
        addWidgetView("network", widgetText(context, "Network\nWi-Fi connected",
                backgrounds, surface));
        addWidgetView("storage", widgetText(context, "Storage\n28 GB available",
                backgrounds, surface));
        customText = widgetText(context, "Custom text", backgrounds, surface);
        addWidgetView("text", customText);

        TextView diagnostics = widgetText(context,
                "DumbTV diagnostics\nKeeper running  ·  Permissions OK", backgrounds, surface);
        diagnostics.setMaxLines(14);
        diagnostics.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        diagnostics.setPadding(Ui.dp(context, 10), Ui.dp(context, 5),
                Ui.dp(context, 10), Ui.dp(context, 5));
        addWidgetView("diagnostics", diagnostics);

        TextView device = widgetText(context,
                "Device information\nFire TV Stick 4K  ·  Fire OS 6", backgrounds, surface);
        device.setMaxLines(5);
        device.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        addWidgetView("device", device);
    }

    void setLayout(WidgetLayout value, String text) {
        super.setLayout(value);
        customText.setText(text == null || text.trim().isEmpty() ? "Custom text" : text.trim());
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
