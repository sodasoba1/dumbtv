package tv.dumbtv.home;

import android.content.Context;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.ListView;

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

    /** A rounded surface that always follows the global UI roundness setting. */
    static GradientDrawable round(Context context, int color) {
        return round(color, cornerRadius(context));
    }

    /** Theme-aware card surface; Glass gets a fine highlight edge instead of a flat fill. */
    static float cornerRadius(Context c) {
        switch (Config.get(c).get(Config.TILE_RADIUS)) {
            case "square": return 0f;
            case "rounded": return dp(c, 18);
            default: return dp(c, 10);
        }
    }

    static GradientDrawable surface(Context c, int color, float radius) {
        GradientDrawable d = round(color, cornerRadius(c));
        if (isGlass(c)) d.setStroke(Math.max(1, dp(c, 1)), 0x55FFFFFF);
        return d;
    }

    /** Give Android dialogs DumbTV surfaces, typography, accent and focus treatment. */
    static void styleDialog(AlertDialog dialog) {
        Context c = dialog.getContext();
        if (dialog.getWindow() == null) return;
        int accent = Config.get(c).accent();
        Drawable background = isGlass(c)
                ? surface(c, optionSurface(c), dp(c, 18))
                : round(c, optionSurface(c));
        dialog.getWindow().setBackgroundDrawable(background);

        int titleId = c.getResources().getIdentifier("alertTitle", "id", "android");
        android.view.View titleView = titleId == 0 ? null : dialog.findViewById(titleId);
        if (titleView instanceof TextView) {
            TextView title = (TextView) titleView;
            title.setTextColor(TEXT);
            title.setTypeface(MEDIUM);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            title.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 4));
        }
        android.view.View messageView = dialog.findViewById(android.R.id.message);
        if (messageView instanceof TextView) {
            TextView message = (TextView) messageView;
            message.setTextColor(MUTED);
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        }
        ListView list = dialog.getListView();
        if (list != null) {
            list.setBackgroundColor(Color.TRANSPARENT);
            list.setDivider(new ColorDrawable(0x22FFFFFF));
            list.setDividerHeight(Math.max(1, dp(c, 1)));
            for (int i = 0; i < list.getChildCount(); i++) {
                android.view.View row = list.getChildAt(i);
                row.setBackground(focusBackground(c, accent, 0x00000000));
                styleDialogText(row, c, accent);
            }
        }
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), c, accent, true);
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), c, accent, false);
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), c, accent, false);
    }

    private static void styleDialogText(android.view.View view, Context c, int accent) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(focused(ink(accent), TEXT));
            text.setTypeface(MEDIUM);
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleDialogText(group.getChildAt(i), c, accent);
            }
        }
    }

    private static void styleDialogButton(android.widget.Button button, Context c,
                                          int accent, boolean primary) {
        if (button == null) return;
        button.setAllCaps(false);
        button.setTypeface(MEDIUM);
        button.setTextColor(focused(ink(accent), primary ? accent : TEXT));
        button.setMinHeight(dp(c, 44));
        button.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        button.setBackground(focusBackground(c, accent,
                primary ? 0x22000000 : 0x00000000));
    }

    private static Drawable focusBackground(Context c, int accent, int idle) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused},
                isGlass(c) ? surface(c, accent, dp(c, 9)) : round(accent, dp(c, 9)));
        states.addState(new int[0],
                isGlass(c) ? surface(c, idle, dp(c, 9)) : round(idle, dp(c, 9)));
        return states;
    }

    /** Apply a monochrome PNG's alpha mask as the selected DumbTV accent colour. */
    static Drawable tintArtwork(Drawable artwork, int color) {
        if (artwork == null) return null;
        Drawable tinted = artwork.mutate();
        tinted.setTint(color);
        return tinted;
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        radius = cornerRadius(v.getContext());
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
