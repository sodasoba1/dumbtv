package tv.dumbtv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
        page.setBackgroundColor(cfg.background());
        page.setPadding(0, Ui.dp(this, 30), 0, Ui.dp(this, 24));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 56), Ui.dp(this, 8), Ui.dp(this, 56), Ui.dp(this, 20));

        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.LIGHT);
        title.setText(R.string.all_apps);
        top.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView close = Ui.text(this, 15, Ui.TEXT, Ui.MEDIUM);
        close.setText(R.string.all_apps);
        close.setContentDescription(getString(R.string.close_all_apps));
        close.setGravity(Gravity.CENTER);
        close.setFocusable(true);
        close.setClickable(true);
        close.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        Ui.focusStyle(close, cfg.accent(), 0x14FFFFFF, Ui.dp(this, 18));
        close.setOnClickListener(v -> finish());
        top.addView(close);
        page.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid = new Grid(this);
        grid.setPadding(Ui.dp(this, 56), Ui.dp(this, 8), Ui.dp(this, 56), Ui.dp(this, 28));
        scroll.addView(grid, new ScrollView.LayoutParams(MATCH, WRAP));
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
            case "small": return new int[] {Ui.dp(this, 144), Ui.dp(this, 81)};
            case "large": return new int[] {Ui.dp(this, 224), Ui.dp(this, 126)};
            default: return new int[] {Ui.dp(this, 180), Ui.dp(this, 101)};
        }
    }

    private void launch(Item item) {
        try {
            startActivity(new Intent(item.intent));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show();
        }
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
