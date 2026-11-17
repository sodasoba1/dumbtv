package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/** Shared DPAD-friendly per-app appearance editor used by Home and All Apps. */
final class AppStyleEditor {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private AppStyleEditor() {}

    static void show(Activity activity, Config config, Item item, Runnable changed) {
        showMenu(activity, config, item, changed);
    }

    private static void showMenu(Activity activity, Config config, Item item, Runnable changed) {
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle(activity.getString(R.string.tile_customize) + " · " + Catalog.label(config, item))
                .setItems(AppStyle.properties(), (d, which) -> {
                    if (which == 6) chooseBackground(activity, config, item, changed);
                    else if (which == 7) chooseBanner(activity, config, item, changed);
                    else editProperty(activity, config, item, which, changed);
                })
                .setNeutralButton(R.string.original, (d, which) -> {
                    config.removePrefix("appstyle." + item.id + ".");
                    changed.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private static void chooseBackground(Activity activity, Config config, Item item, Runnable changed) {
        int fallback = AppStyle.backgroundColor(config, item, Ui.tileSurface(activity, true));
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("App background")
                .setItems(new String[] {"Choose custom colour", "Use theme default"}, (d, which) -> {
                    if (which == 0) {
                        ColorPicker.show(activity, fallback, color -> {
                            AppStyle.set(config, item, "background",
                                    String.format(java.util.Locale.US, "#%06X", color & 0x00FFFFFF));
                            changed.run();
                            showMenu(activity, config, item, changed);
                        });
                    } else {
                        config.set(AppStyle.key(item, "background"), null);
                        changed.run();
                        showMenu(activity, config, item, changed);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private static void chooseBanner(Activity activity, Config config, Item item, Runnable changed) {
        String[] assets;
        try {
            String[] files = activity.getAssets().list("custom-banners");
            java.util.List<String> choices = new java.util.ArrayList<>();
            choices.add("Use app's original banner");
            if (files != null) {
                for (String file : files) {
                    if (file != null && file.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                        choices.add(file);
                    }
                }
            }
            assets = choices.toArray(new String[0]);
        } catch (java.io.IOException e) {
            assets = new String[] {"Use app's original banner"};
        }
        final String[] bannerAssets = assets;
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Banner image")
                .setItems(bannerAssets, (d, which) -> {
                    config.set("appstyle." + item.id + ".banner",
                            which == 0 ? null : bannerAssets[which]);
                    changed.run();
                    showMenu(activity, config, item, changed);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private static void editProperty(Activity activity, Config config, Item item,
                                     int property, Runnable changed) {
        String[] values = AppStyle.values(property);
        String[] labels = AppStyle.labels(property);
        String current = AppStyle.current(config, item, property);
        int selected = java.util.Arrays.asList(values).indexOf(current);
        final String[] pending = {selected < 0 ? AppStyle.inherited(config, property) : values[selected]};

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 12), Ui.dp(activity, 20), 0);

        TextView preview = Ui.text(activity, 15, Ui.TEXT, Ui.MEDIUM);
        preview.setGravity(android.view.Gravity.CENTER);
        preview.setText("APP ICON\nExample app");
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(MATCH, Ui.dp(activity, 104));
        previewParams.bottomMargin = Ui.dp(activity, 16);
        content.addView(preview, previewParams);

        RadioGroup choices = new RadioGroup(activity);
        choices.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = new RadioButton(activity);
            option.setText(labels[i]);
            option.setTextColor(Ui.TEXT);
            option.setTextSize(15);
            option.setId(1000 + i);
            choices.addView(option, new RadioGroup.LayoutParams(MATCH, WRAP));
        }
        content.addView(choices);

        Runnable updatePreview = () -> {
            String corners = value(config, item, property, pending[0], 0);
            String border = value(config, item, property, pending[0], 2);
            String shadow = value(config, item, property, pending[0], 3);
            String focusScale = value(config, item, property, pending[0], 1);
            String appLabels = value(config, item, property, pending[0], 4);
            String appSize = value(config, item, property, pending[0], 5);

            float radius = Ui.dp(activity, "square".equals(corners) ? 0 : "rounded".equals(corners) ? 18 : 10);
            int surface = AppStyle.backgroundColor(config, item, Ui.tileSurface(activity, true));
            preview.setBackground(Ui.surface(activity, surface, radius));
            GradientDrawable shape = (GradientDrawable) preview.getBackground();
            if ("off".equals(border)) {
                shape.setStroke(Ui.dp(activity, Ui.isGlass(activity) ? 1 : 0),
                        Ui.isGlass(activity) ? 0x66FFFFFF : 0x00000000);
            } else {
                shape.setStroke(Ui.dp(activity, 2),
                        "accent".equals(border) ? config.accent() : 0x66FFFFFF);
            }
            preview.setTextColor(Ui.ink(surface));
            preview.setElevation(Ui.dp(activity, "off".equals(shadow) ? 0 : "soft".equals(shadow) ? 4 : 8));
            float focus = "off".equals(focusScale) ? 1f
                    : "subtle".equals(focusScale) ? 1.04f
                    : "strong".equals(focusScale) ? 1.12f : 1.08f;
            float size = "compact".equals(appSize) ? 0.90f : "large".equals(appSize) ? 1.08f : 1f;
            preview.setScaleX(size * focus);
            preview.setScaleY(size * focus);
            preview.setText("never".equals(appLabels) ? "APP ICON" : "APP ICON\nExample app");
        };

        choices.setOnCheckedChangeListener((group, checkedId) -> {
            int index = checkedId - 1000;
            if (index >= 0 && index < values.length) {
                pending[0] = values[index];
                updatePreview.run();
            }
        });
        choices.check(1000 + Math.max(0, selected));
        updatePreview.run();

        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle(AppStyle.properties()[property])
                .setView(content)
                .setPositiveButton(R.string.save, (d, which) -> {
                    AppStyle.set(config, item, AppStyle.configProperty(property), pending[0]);
                    changed.run();
                    showMenu(activity, config, item, changed);
                })
                .setNeutralButton(R.string.original, (d, which) -> {
                    config.set(AppStyle.key(item, AppStyle.configProperty(property)), null);
                    changed.run();
                    showMenu(activity, config, item, changed);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private static String value(Config config, Item item, int editedProperty,
                                String pending, int property) {
        if (editedProperty == property) return pending;
        return AppStyle.current(config, item, property);
    }
}
