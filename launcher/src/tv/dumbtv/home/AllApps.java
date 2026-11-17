package tv.dumbtv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.app.AlertDialog;
import android.widget.EditText;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen catalogue of every installed app with a launcher entry. */
public final class AllApps extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private Grid grid;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        cfg.refresh();
        if ("on".equals(cfg.get(Config.LOCK))) {
            finish();
            return;
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pageSurface = "classic".equals(cfg.get(Config.THEME)) ? cfg.background() : Ui.optionSurface(this);
        page.setBackground(Ui.isGlass(this)
                ? Ui.surface(this, pageSurface, 0) : Ui.round(pageSurface, 0));
        page.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 24));

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid = new Grid(this);
        grid.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 28));
        scroll.addView(grid, new FrameLayout.LayoutParams(MATCH, WRAP));
        page.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(page);
        loadApps();
    }

    private void loadApps() {
        io.execute(() -> {
            List<Item> items = Catalog.apps(this);
            Collator collator = Collator.getInstance();
            Collections.sort(items, (a, b) -> collator.compare(
                    Catalog.label(cfg, a).toString(), Catalog.label(cfg, b).toString()));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                int[] size = cardSize();
                int width = size[0], height = size[1];
                for (Item item : items) {
                    Tile tile = new Tile(this, item, Catalog.ALL_APPS, Catalog.label(cfg, item),
                            width, height, cfg.get(Config.LABELS), cfg.accent());
                    tile.setOnClickListener(v -> launch(item));
                    tile.setOnLongClickListener(v -> {
                        showAppMenu(item);
                        return true;
                    });
                    tile.setOnKeyListener((v, code, event) -> {
                        if (event.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK) {
                            finish();
                            return true;
                        }
                        return false;
                    });
                    grid.addView(tile, new ViewGroup.LayoutParams(WRAP, WRAP));
                    io.execute(() -> {
                        Catalog.art(getPackageManager(), item, "afterglow".equals(cfg.get(Config.TILE_STYLE)), null);
                        Drawable custom = Catalog.customBanner(this, item);
                        if (custom != null) {
                            item.banner = custom;
                            item.icon = null;
                        }
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) tile.invalidate();
                        });
                    });
                }
                if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
            });
        });
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "extra-small": return new int[] {Ui.dp(this, 120), Ui.dp(this, 68)};
            case "small": return new int[] {Ui.dp(this, 144), Ui.dp(this, 81)};
            case "large": return new int[] {Ui.dp(this, 224), Ui.dp(this, 126)};
            case "extra-large": return new int[] {Ui.dp(this, 260), Ui.dp(this, 146)};
            default: return new int[] {Ui.dp(this, 180), Ui.dp(this, 101)};
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && getCurrentFocus() instanceof Tile) {
            showAppMenu(((Tile) getCurrentFocus()).item);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showAppMenu(Item item) {
        boolean pinned = Catalog.shown(cfg, Catalog.APPS, item);
        String[] actions = new String[] {
                getString(pinned ? R.string.tile_unpin : R.string.tile_pin),
                getString(R.string.tile_rename),
                getString(R.string.tile_customize),
                getString(R.string.tile_app_info)
        };
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, item))
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        Catalog.setShown(cfg, Catalog.APPS, item, !pinned);
                        Toast.makeText(this, pinned ? "Removed from home screen" : "Added to home screen",
                                Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        rename(item);
                    } else if (which == 2) {
                        AppStyleEditor.show(this, cfg, item, this::recreate);
                    } else {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", item.id, null)));
                    }
                })
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private void rename(Item item) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, item));
        field.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(R.string.rename_title)
                .setView(field)
                .setPositiveButton(R.string.save, (d, which) -> {
                    String name = field.getText().toString().trim();
                    cfg.set("name." + item.id, name.equals(item.label.toString()) ? null : name);
                    recreate();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private void launch(Item item) {
        try {
            startActivity(new Intent(item.intent));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.stay, R.anim.slide_down);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
