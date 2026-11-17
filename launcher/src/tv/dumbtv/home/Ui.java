package tv.dumbtv.home;

import android.content.Context;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    static final int TEXT = 0xFFF2F2F3;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int SURFACE = 0xFF1C1D21;
    static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    static int tileSurface(Context c, boolean app) {
        switch (Config.get(c).get(Config.THEME)) {
            case "glass": return 0xA61A2633;
            case "midnight": return 0xFF111722;
            case "slate": return 0xFF252B33;
            default: return app ? SURFACE : 0xFF17181B;
        }
    }

    static int optionSurface(Context c) {
        switch (Config.get(c).get(Config.THEME)) {
            case "glass": return 0xB31B2733;
            case "midnight": return 0xFF151C28;
            case "slate": return 0xFF303842;
            default: return 0xFF15161A;
        }
    }

    static int optionFocus(Context c) {
        int accent = Config.get(c).accent();
        return "glass".equals(Config.get(c).get(Config.THEME))
                ? (accent & 0x00FFFFFF) | 0x55000000
                : (accent & 0x00FFFFFF) | 0x33000000;
    }

    static boolean isGlass(Context c) {
        return "glass".equals(Config.get(c).get(Config.THEME));
    }

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Black on light accents, white on dark ones. */
    static int ink(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Theme-aware card surface; Glass gets a fine highlight edge instead of a flat fill. */
    static GradientDrawable surface(Context c, int color, float radius) {
        GradientDrawable d = round(color, radius);
        if (isGlass(c)) d.setStroke(Math.max(1, dp(c, 1)), 0x55FFFFFF);
        return d;
    }

    /** Give system dialogs the same restrained translucent surface as Glass cards. */
    static void styleDialog(AlertDialog dialog) {
        Context c = dialog.getContext();
        if (!isGlass(c) || dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(surface(c, optionSurface(c), dp(c, 18)));
        android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        android.widget.Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        android.widget.Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (positive != null) positive.setTextColor(Config.get(c).accent());
        if (negative != null) negative.setTextColor(TEXT);
        if (neutral != null) neutral.setTextColor(TEXT);
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused},
                isGlass(v.getContext()) ? surface(v.getContext(), accent, radius) : round(accent, radius));
        bg.addState(new int[0],
                isGlass(v.getContext()) ? surface(v.getContext(), idle, radius) : round(idle, radius));
        v.setBackground(bg);
        v.setTextColor(focused(ink(accent), 0xD9FFFFFF));
    }

    /**
     * A chip icon to go with {@link #focusStyle}: {@code shape} in the chip's text colour and
     * {@code mark} in the accent, both turning the ink colour on the accent fill when focused.
     */
    static Drawable chipIcon(Drawable shape, Drawable mark, int accent, int size) {
        shape.mutate().setTintList(focused(ink(accent), 0xD9FFFFFF));
        mark.mutate().setTintList(focused(ink(accent), accent));
        LayerDrawable d = new LayerDrawable(new Drawable[] {shape, mark});
        d.setBounds(0, 0, size, size);
        return d;
    }

    private static ColorStateList focused(int on, int off) {
        return new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {on, off});
    }
}
