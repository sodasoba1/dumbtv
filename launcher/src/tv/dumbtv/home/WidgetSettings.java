package tv.dumbtv.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.StateListDrawable;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** DPAD-friendly visibility switches; placement remains in WidgetEditor. */
public final class WidgetSettings extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private Config cfg;
    private final java.util.List<Runnable> refreshers = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        cfg.refresh();

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        int surface = "classic".equals(cfg.get(Config.THEME)) ? 0xFF0C0C0E : Ui.optionSurface(this);
        scroll.setBackground(Ui.isGlass(this) ? Ui.surface(this, surface, 0) : Ui.round(surface, 0));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(56), dp(32), dp(56), dp(32));
        scroll.addView(root);

        TextView title = Ui.text(this, 28, Ui.TEXT, Ui.LIGHT);
        title.setText("Widgets");
        title.setPadding(dp(16), 0, 0, dp(8));
        root.addView(title);

        TextView note = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        note.setText("Choose which placed widgets are visible. Turning one off keeps its position; use Edit widgets to add, move, resize or remove widgets.");
        note.setLineSpacing(0, 1.25f);
        note.setPadding(dp(16), 0, 0, dp(18));
        root.addView(note);

        for (WidgetVisibility.Definition definition : WidgetVisibility.ALL) {
            root.addView(widgetRow(definition));
        }
        setContentView(scroll);
        refresh();
    }

    private View widgetRow(WidgetVisibility.Definition definition) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(600), WRAP);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);

        TextView label = Ui.text(this, 16, Ui.TEXT, Ui.MEDIUM);
        label.setText(definition.label);
        row.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView value = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        Runnable show = () -> {
            boolean enabled = WidgetVisibility.isEnabled(cfg, definition.id);
            value.setText(enabled ? "On" : "Off");
            value.setTextColor(row.isFocused() ? Ui.TEXT : Ui.MUTED);
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[] {android.R.attr.state_focused},
                    Ui.surface(this, Ui.optionFocus(this), dp(10)));
            background.addState(new int[0],
                    Ui.surface(this, Ui.optionSurface(this), dp(10)));
            row.setBackground(background);
        };
        row.setOnClickListener(v -> {
            WidgetVisibility.setEnabled(cfg, definition.id,
                    !WidgetVisibility.isEnabled(cfg, definition.id));
            show.run();
        });
        row.setOnFocusChangeListener((v, focused) -> show.run());
        refreshers.add(show);
        return row;
    }

    private void refresh() {
        for (Runnable refresher : refreshers) refresher.run();
    }

    private int dp(float value) { return Ui.dp(this, value); }
}
