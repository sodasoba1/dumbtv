package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
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
        boolean resetCustomisation;
        State(Config config, Item item) {
            name = Catalog.label(config, item).toString();
            banner = config.get(AppStyle.key(item, "banner"), null);
            background = config.get(AppStyle.key(item, "background"), null);
        }
    }

    private static void showMenu(Activity activity, Config config, Item item,
                                 State state, Runnable changed) {
        String[] actions = {"Rename app", "Change banner image", "Change background colour",
                "Reset customisation", "Save changes"};
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Customise · " + Catalog.label(config, item))
                .setItems(actions, (d, which) -> {
                    if (which == 0) rename(activity, config, item, state, changed);
                    else if (which == 1) chooseBanner(activity, config, item, state, changed);
                    else if (which == 2) chooseBackground(activity, config, item, state, changed);
                    else if (which == 3) {
                        state.name = item.label.toString();
                        state.banner = null;
                        state.background = null;
                        state.resetCustomisation = true;
                        showMenu(activity, config, item, state, changed);
                    } else save(activity, config, item, state, changed);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
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
        choices.add("Use app's original banner");
        try {
            String[] files = activity.getAssets().list("custom-banners");
            if (files != null) {
                java.util.Arrays.sort(files, String.CASE_INSENSITIVE_ORDER);
                for (String file : files) {
                    if (file != null && file.toLowerCase(Locale.ROOT).endsWith(".png")) {
                        choices.add(file);
                    }
                }
            }
        } catch (IOException ignored) {
            // The original banner remains available if the asset folder cannot be read.
        }
        String[] assets = choices.toArray(new String[0]);
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Banner image")
                .setItems(assets, (d, which) -> {
                    state.banner = which == 0 ? null : assets[which];
                    showMenu(activity, config, item, state, changed);
                })
                .setNegativeButton(R.string.cancel, (d, which) ->
                        showMenu(activity, config, item, state, changed))
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

    private static void save(Activity activity, Config config, Item item,
                             State state, Runnable changed) {
        AlertDialog dialog = new AlertDialog.Builder(activity, Ui.DIALOG)
                .setTitle("Save changes?")
                .setMessage("Apply the name, banner and background changes to " + item.label + "?")
                .setPositiveButton(R.string.save, (d, which) -> {
                    if (state.resetCustomisation) {
                        config.removePrefix("appstyle." + item.id + ".");
                    }
                    config.set("name." + item.id,
                            state.name.equals(item.label.toString()) ? null : state.name);
                    config.set(AppStyle.key(item, "banner"), state.banner);
                    config.set(AppStyle.key(item, "background"), state.background);
                    changed.run();
                })
                .setNegativeButton(R.string.cancel, (d, which) ->
                        showMenu(activity, config, item, state, changed))
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }
}
