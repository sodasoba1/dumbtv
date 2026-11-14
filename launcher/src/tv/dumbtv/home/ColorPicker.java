package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** A shared, D-pad-friendly HSV picker for the three independent appearance colours. */
final class ColorPicker {
    interface Listener {
        void onSelected(int color);
    }

    private ColorPicker() {}

    static void show(Activity activity, int initial, Listener listener) {
        float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Ui.dp(activity, 28);
        content.setPadding(horizontal, Ui.dp(activity, 12), horizontal, Ui.dp(activity, 8));

        TextView preview = Ui.text(activity, 22, Ui.TEXT, Ui.MEDIUM);
        preview.setGravity(Gravity.CENTER);
        preview.setMinimumHeight(Ui.dp(activity, 76));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 76));
        previewParams.bottomMargin = Ui.dp(activity, 14);
        content.addView(preview, previewParams);

        SeekBar hue = addChannel(activity, content, activity.getString(R.string.color_hue), 359,
                Math.round(hsv[0]));
        SeekBar saturation = addChannel(activity, content, activity.getString(R.string.color_saturation), 100,
                Math.round(hsv[1] * 100));
        SeekBar brightness = addChannel(activity, content, activity.getString(R.string.color_brightness), 100,
                Math.round(hsv[2] * 100));

        Runnable update = () -> {
            hsv[0] = hue.getProgress();
            hsv[1] = saturation.getProgress() / 100f;
            hsv[2] = brightness.getProgress() / 100f;
            int color = Color.HSVToColor(hsv);
            preview.setText(String.format(Locale.US, "#%06X", color & 0x00FFFFFF));
            preview.setTextColor(Ui.ink(color));
            preview.setBackground(Ui.round(color, Ui.dp(activity, 8)));
        };
        SeekBar.OnSeekBarChangeListener changes = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                update.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
        hue.setOnSeekBarChangeListener(changes);
        saturation.setOnSeekBarChangeListener(changes);
        brightness.setOnSeekBarChangeListener(changes);
        update.run();

        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle(R.string.custom_color_title)
                .setView(content)
                .setPositiveButton(R.string.color_use, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                listener.onSelected(Color.HSVToColor(new float[] {
                        hue.getProgress(), saturation.getProgress() / 100f, brightness.getProgress() / 100f
                }));
                dialog.dismiss();
            });
            hue.requestFocus();
        });
        dialog.show();
    }

    private static SeekBar addChannel(Activity activity, LinearLayout parent, String title,
                                      int max, int progress) {
        TextView label = Ui.text(activity, 15, Ui.MUTED, Ui.MEDIUM);
        label.setText(title);
        label.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 2));
        parent.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(activity);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setFocusable(true);
        bar.setFocusableInTouchMode(true);
        parent.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 48)));
        return bar;
    }
}
