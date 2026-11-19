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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen catalogue of every installed app with a launcher entry. */
public final class AllApps extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private Grid grid;
    private ScrollView scroll;
    private final Set<String> artRequested = new HashSet<>();
    /** Loaded once per icon-pack package; accessed only by the serial IO executor. */
    private final Map<String, IconPack> packs = new HashMap<>();

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

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid = new Grid(this);
        grid.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 28));
        scroll.addView(grid, new FrameLayout.LayoutParams(MATCH, WRAP));
        scroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> loadVisibleArt());
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
                }
                if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
                scroll.post(this::loadVisibleArt);
            });
        });
    }

    /** Decode artwork only for tiles near the viewport. */
    private void loadVisibleArt() {
        if (grid == null || scroll == null || isFinishing() || isDestroyed()) return;
        if (grid.getHeight() == 0 || scroll.getHeight() == 0) {
            grid.post(this::loadVisibleArt);
            return;
        }
        int top = scroll.getScrollY() - Ui.dp(this, 220);
        int bottom = scroll.getScrollY() + scroll.getHeight() + Ui.dp(this, 220);
        int columns = grid.columns();
        int stride = grid.rowStride();
        if (columns < 1 || stride < 1) return;
        int firstRow = Math.max(0, (top - grid.getPaddingTop()) / stride);
        int lastRow = Math.max(firstRow, (bottom - grid.getPaddingTop()) / stride);
        int first = firstRow * columns;
        int end = Math.min(grid.getChildCount(), (lastRow + 1) * columns);
        for (int i = first; i < end; i++) {
            View child = grid.getChildAt(i);
            if (!(child instanceof Tile)) continue;
            Tile tile = (Tile) child;
            Item item = tile.item;
            if (item.banner != null || item.icon != null || !artRequested.add(item.id)) continue;
            String pack = cfg.get(IconPack.KEY, IconPack.OFF);
            String chosen = cfg.get(IconPack.CHOSEN + item.id, null);
            boolean cornerIcon = "afterglow".equals(cfg.get(Config.TILE_STYLE));
            io.execute(() -> {
                AppArtwork.load(this, getPackageManager(), item, cornerIcon, packs, pack, chosen);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) tile.invalidate();
                });
            });
        }
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            View focused = getCurrentFocus();
            int index = grid == null ? -1 : grid.indexOfChild(focused);
            if (index >= 0 && index < grid.columns()) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
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
            Recent.opened(this, item.id);
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
