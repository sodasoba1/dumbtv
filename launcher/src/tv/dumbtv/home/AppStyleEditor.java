package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal per-app editor: name, banner artwork, background colour, reset and save. */
final class AppStyleEditor {
    private AppStyleEditor() {}

    static void show(Activity activity, Config config, Item item, Runnable changed) {
        State state = new State(config, item);
        showMenu(activity, config, item, state, changed);
    }

    private static final class State {
        String name;
        String banner;
        String background;
        String bannerColor;
        String size;
        boolean resetCustomisation;
        State(Config config, Item item) {
            name = Catalog.label(config, item).toString();
            banner = config.get(AppStyle.key(item, "banner"), null);
            background = config.get(AppStyle.key(item, "background"), null);
            bannerColor = config.get(AppStyle.key(item, "bannerColor"), "original");
            size = AppStyle.get(config, item, "size", "normal");
        }
    }

    private static void showMenu(Activity activity, Config config, Item item,
                                 State state, Runnable changed) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 12),
                Ui.dp(activity, 18), Ui.dp(activity, 12));
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int previewWidth = Ui.dp(activity, "compact".equals(state.size) ? 240
                : "large".equals(state.size) ? 360 : 300);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(previewWidth, Ui.dp(activity, 150));
        previewLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(tilePreview(activity, item, state), previewLp);
        final AlertDialog[] holder = new AlertDialog[1];
        addAction(activity, content, "Rename app", () -> rename(activity, config, item, state, changed), holder);
        addAction(activity, content, "Banner artwork", () -> chooseBanner(activity, config, item, state, changed), holder);
        addAction(activity, content, "Banner colour", () -> chooseBannerColor(activity, config, item, state, changed), holder);
        addAction(activity, content, "Background colour", () -> chooseBackground(activity, config, item, state, changed), holder);
        addAction(activity, content, "Tile size", () -> chooseSize(activity, config, item, state, changed), holder);
        addAction(activity, content, "Reset customisation", () -> {
            state.name = item.label.toString();
            state.banner = null;
            state.background = null;
            state.bannerColor = "original";
            state.size = "normal";
            state.resetCustomisation = true;
            showMenu(activity, config, item, state, changed);
        }, holder);
        addAction(activity, content, "Save changes", () -> save(activity, config, item, state, changed), holder);
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 430)));
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Customise · " + Catalog.label(config, item))
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private static void addAction(Activity activity, LinearLayout parent, String label,
                                  Runnable action, AlertDialog[] dialog) {
        TextView button = Ui.text(activity, 15, Ui.TEXT, Ui.MEDIUM);
        button.setText(label);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12),
                Ui.dp(activity, 14), Ui.dp(activity, 12));
        Ui.focusStyle(button, Config.get(activity).accent(),
                Ui.optionSurface(activity), Ui.dp(activity, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, 5);
        parent.addView(button, lp);
        button.setOnClickListener(v -> {
            if (dialog[0] != null) dialog[0].dismiss();
            action.run();
        });
    }

    private static View tilePreview(Activity activity, Item item, State state) {
        float scale = "compact".equals(state.size) ? 0.82f : "large".equals(state.size) ? 1.18f : 1f;
        FrameLayout card = new FrameLayout(activity);
        int fallback = Ui.tileSurface(activity, true);
        card.setBackground(Ui.round(state.background == null ? fallback
                : Config.parseColor(state.background, fallback), Ui.cornerRadius(activity)));
        card.setClipToOutline(true);
        Drawable artwork = previewArtwork(activity, item, state.banner, state.bannerColor);
        if (artwork == null) artwork = item.icon;
        if (artwork != null) {
            ImageView image = new ImageView(activity);
            image.setImageDrawable(artwork);
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (image.getScaleType() == ImageView.ScaleType.CENTER_INSIDE) {
                imageLp.setMargins(Ui.dp(activity, 28), Ui.dp(activity, 10),
                        Ui.dp(activity, 28), Ui.dp(activity, 38));
            }
            card.addView(image, imageLp);
        }
        TextView label = Ui.text(activity, Math.round(14 * scale), Ui.TEXT, Ui.MEDIUM);
        label.setText(state.name);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 5),
                Ui.dp(activity, 12), Ui.dp(activity, 5));
        label.setBackground(Ui.round(0x99000000, Ui.dp(activity, 7)));
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        labelLp.setMargins(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), Ui.dp(activity, 8));
        card.addView(label, labelLp);
        return card;
    }

    private static Drawable previewArtwork(Activity activity, Item item, String asset, String bannerColor) {
        if (asset == null) return item.banner;
        try (java.io.InputStream in = activity.getAssets().open("custom-banners/" + asset)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) return item.banner;
            Drawable artwork = new BitmapDrawable(activity.getResources(), bitmap);
            if ("accent".equals(bannerColor)) return Ui.tintArtwork(artwork, Config.get(activity).accent());
            if (bannerColor != null && bannerColor.matches("#[0-9A-Fa-f]{6}")) {
                return Ui.tintArtwork(artwork, Config.parseColor(bannerColor, Config.get(activity).accent()));
            }
            return artwork;
        } catch (IOException e) {
            return item.banner;
        }
    }

    private static void rename(Activity activity, Config config, Item item,
                               State state, Runnable changed) {
        EditText input = new EditText(activity);
        input.setSingleLine();
        input.setText(state.name);
        input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Rename app")
                .setView(input)
                .setPositiveButton(R.string.done, (d, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) state.name = value;
                    showMenu(activity, config, item, state, changed);
                })
                .setNegativeButton(R.string.cancel, (d, which) ->
                        showMenu(activity, config, item, state, changed))
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private static void chooseBanner(Activity activity, Config config, Item item,
                                     State state, Runnable changed) {
        List<String> choices = new ArrayList<>();
        choices.add(null);
        try {
            String[] files = activity.getAssets().list("custom-banners");
            if (files != null) {
                java.util.Arrays.sort(files, String.CASE_INSENSITIVE_ORDER);
                for (String file : files) {
                    if (file != null && file.toLowerCase(Locale.ROOT).endsWith(".png")) choices.add(file);
                }
            }
        } catch (IOException ignored) {
            // The original artwork remains available if the asset folder cannot be read.
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(screenHeight * 0.76f)));
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        grid.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 10),
                Ui.dp(activity, 10), Ui.dp(activity, 10));
        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog[] holder = new AlertDialog[1];
        for (String asset : choices) {
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setPadding(Ui.dp(activity, 7), Ui.dp(activity, 7),
                    Ui.dp(activity, 7), Ui.dp(activity, 7));
            android.graphics.drawable.StateListDrawable cellStates =
                    new android.graphics.drawable.StateListDrawable();
            cellStates.addState(new int[] {android.R.attr.state_focused},
                    Ui.round(Config.get(activity).accent(), Ui.dp(activity, 8)));
            cellStates.addState(new int[0],
                    Ui.surface(activity, Ui.optionSurface(activity), Ui.dp(activity, 8)));
            cell.setBackground(cellStates);
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            Drawable artwork = previewArtwork(activity, item, asset, state.bannerColor);
            if (artwork == null) artwork = item.icon;
            image.setImageDrawable(artwork);
            cell.addView(image, new LinearLayout.LayoutParams(Ui.dp(activity, 176), Ui.dp(activity, 94)));
            TextView caption = Ui.text(activity, 11, Ui.TEXT, Ui.MEDIUM);
            caption.setText(asset == null ? "Original artwork" : asset.replace('_', ' '));
            caption.setGravity(Gravity.CENTER);
            caption.setMaxLines(2);
            cell.addView(caption, new LinearLayout.LayoutParams(
                    Ui.dp(activity, 176), ViewGroup.LayoutParams.WRAP_CONTENT));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(activity, 194);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 4),
                    Ui.dp(activity, 4), Ui.dp(activity, 4));
            grid.addView(cell, lp);
            cell.setOnClickListener(v -> {
                state.banner = asset;
                if (holder[0] != null) holder[0].dismiss();
                showMenu(activity, config, item, state, changed);
            });
        }
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Choose banner artwork")
                .setView(scroll)
                .setNegativeButton(R.string.cancel, (d, which) ->
                        showMenu(activity, config, item, state, changed))
                .create();
        holder[0] = dialog;
        dialog.show();
        Ui.styleDialog(dialog);
        if (dialog.getWindow() != null) {
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            int height = activity.getResources().getDisplayMetrics().heightPixels;
            dialog.getWindow().setLayout(Math.round(width * 0.88f), Math.round(height * 0.86f));
        }
    }

    private static void chooseBannerColor(Activity activity, Config config, Item item,
                                          State state, Runnable changed) {
        String[] choices = {"Original image colours", "Use launcher accent", "Choose a colour"};
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Banner colour")
                .setItems(choices, (d, which) -> {
                    if (which == 0) {
                        state.bannerColor = "original";
                        showMenu(activity, config, item, state, changed);
                    } else if (which == 1) {
                        state.bannerColor = "accent";
                        showMenu(activity, config, item, state, changed);
                    } else {
                        int current = state.bannerColor != null && state.bannerColor.matches("#[0-9A-Fa-f]{6}")
                                ? Config.parseColor(state.bannerColor, config.accent()) : config.accent();
                        ColorPicker.show(activity, current, color -> {
                            state.bannerColor = String.format(Locale.US, "#%06X", color & 0x00FFFFFF);
                            showMenu(activity, config, item, state, changed);
                        });
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private static void chooseBackground(Activity activity, Config config, Item item,
                                         State state, Runnable changed) {
        int fallback = state.background == null ? Ui.tileSurface(activity, true)
                : Config.parseColor(state.background, Ui.tileSurface(activity, true));
        ColorPicker.show(activity, fallback, color -> {
            state.background = String.format(Locale.US, "#%06X", color & 0x00FFFFFF);
            showMenu(activity, config, item, state, changed);
        });
    }

    private static void chooseSize(Activity activity, Config config, Item item,
                                    State state, Runnable changed) {
        String[] values = {"compact", "normal", "large"};
        String[] labels = {"Compact", "Normal", "Large"};
        int selected = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(state.size)) selected = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Favourite tile size")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    state.size = values[which];
                    showMenu(activity, config, item, state, changed);
                })
                .setNegativeButton(R.string.cancel, (d, which) ->
                        showMenu(activity, config, item, state, changed))
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private static void save(Activity activity, Config config, Item item,
                             State state, Runnable changed) {
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Save changes?")
                .setMessage("Apply the name, banner, background and tile-size changes to " + item.label + "?")
                .setPositiveButton(R.string.save, (d, which) -> {
                    AppStyle.save(config, item, state.name, item.label.toString(),
                            state.banner, state.background, state.size, state.resetCustomisation);
                    config.set(AppStyle.key(item, "bannerColor"),
                            "original".equals(state.bannerColor) ? null : state.bannerColor);
                    changed.run();
                })
                .setNegativeButton(R.string.cancel, (d, which) ->
                        showMenu(activity, config, item, state, changed))
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }
}
