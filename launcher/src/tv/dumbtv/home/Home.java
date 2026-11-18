package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Home extends Activity {
    /** How long Menu is held to open Options while the child lock is on. */
    static final long UNLOCK_MS = 3000;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService colorIo = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed, unlockOpened;
    private String lastFocusedKind;
    private final Map<String, String> lastIds = new HashMap<>();
    private FrameLayout root;
    private LinearLayout column, favoritesPanel, clockWidget;
    private ScrollView homeScroll;
    private ImageView wall;
    private View shade;
    private View tint;
    private View gradient;
    private LinearLayout head, toolbar;
    private TextView clock, date;
    private Meter meter;
    private View memoryWidget;
    private TextView networkWidget, textWidget, diagnosticsWidget, deviceWidget;
    private View storageWidget;
    private Meter storageMeter;
    private WidgetGridView widgetGrid;
    private WidgetLayout widgetLayout;
    private Row apps, allApps, inputs;
    private Hero hero;
    private int libraryGeneration;
    private Map<String, Long> recent = new HashMap<>();
    private Tile moving;
    private TextView allAppsButton;
    private String wallKey;
    private String backgroundMode;
    private ValueAnimator colorAnimation;
    private int displayedColor, targetColor;
    private boolean tintWallpaper;
    private int widgetHeight;
    private boolean toolbarCollapsed;
    /** Options › Icon pack and the picked icons as the tiles' art was last loaded for. UI thread. */
    private String artState = "";
    /** Icon packs read so far by package, null for one that would not load; io thread only. */
    private final Map<String, IconPack> packs = new HashMap<>();
    /** The installed icon packs, package -> name, read on io; UI thread. */
    private Map<String, String> iconPacks = new HashMap<>();

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            String changed = data == null ? null : data.getSchemeSpecificPart();
            if (changed != null) {
                // Forget what was read from that package, a pack that would not load included:
                // a pack installed again while another was chosen must load when chosen.
                io.execute(() -> packs.remove(changed));
                loadIconPacks();
                if (artState.contains(changed)) {
                    for (Item it : apps.all) it.artRequested = false;
                }
            }
            loadApps(changed);
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final class Row {
        final String kind;
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        List<Item> all = new ArrayList<>();
        int initialFocusIndex;
        int renderGeneration;

        Row(String kind, String name, LinearLayout parent) {
            this.kind = kind;
            int edge = dp(24), bleed = dp(14);
            title = Ui.text(Home.this, 12, Ui.FAINT, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.ROOT));
            title.setLetterSpacing(0.14f);
            title.setPadding(edge, dp(20), edge, dp(2));
            strip = new LinearLayout(Home.this);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(bleed, dp(12), bleed, dp(8));
            scroll = new HorizontalScrollView(Home.this) {
                /** Keeps the focused tile centered, except near either end of the row. */
                @Override
                protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                    if (getChildCount() == 0) return 0;
                    int range = Math.max(0, getChildAt(0).getWidth() - (getWidth() - getPaddingLeft() - getPaddingRight()));
                    int target = Math.max(0, Math.min(range, r.centerX() - getWidth() / 2));
                    return target - getScrollX();
                }
            };
            scroll.setFocusable(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setFillViewport(Catalog.APPS.equals(kind));
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            parent.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        /** The single horizontal strip that holds this section's tiles. */
        ViewGroup tiles() {
            return strip;
        }

        void setVisible(boolean on) {
            if (Catalog.APPS.equals(kind) && favoritesPanel != null) {
                favoritesPanel.setVisibility(on ? View.VISIBLE : View.GONE);
                title.setVisibility(View.GONE);
                scroll.setVisibility(on ? View.VISIBLE : View.GONE);
                return;
            }
            title.setVisibility(on ? View.VISIBLE : View.GONE);
            scroll.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Future<List<Item>> catalog = io.submit(() -> Catalog.apps(this));
        cfg = Config.get(this);
        boolean needsRecent = recentFor(Catalog.APPS) != null;
        Future<Map<String, Long>> times = needsRecent ? io.submit(() -> Recent.times(this)) : null;
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Keeper.start(this);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog, times);
        loadInputs();
        loadIconPacks();
    }

    private void loadIconPacks() {
        io.execute(() -> {
            Map<String, String> found = IconPack.installed(getPackageManager());
            ui.post(() -> iconPacks = found);
        });
    }

    /**
     * Waits for the app list and loads the art of the tiles that fit on screen, so the first
     * frame is complete. After a cold start that frame is what people see first: loading in the
     * background drew the Inputs row alone, focused HDMI 1 for ~0.3 s, then popped the apps and
     * their banners in.
     */
    private void firstApps(Future<List<Item>> catalog, Future<Map<String, Long>> times) {
        artState = artState();
        try {
            apps.all = catalog.get();
            if (times != null) recent = times.get();
        } catch (ExecutionException e) {
            loadApps(null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadApps(null);
            return;
        }
        int fit = getResources().getDisplayMetrics().widthPixels / (cardSize()[0] + dp(18)) + 1;
        String tileStyle = cfg.get(Config.TILE_STYLE);
        boolean cornerIcon = "afterglow".equals(tileStyle);
        List<Item> first = new ArrayList<>();
        List<Runnable> jobs = new ArrayList<>();
        // A chosen icon pack is read by parsing its whole appfilter; the first frame does not wait
        // for that, and its tiles load their art as they are drawn, like every later tile.
        if (!IconPack.OFF.equals(cfg.get(IconPack.KEY, IconPack.OFF))) return;
        for (Item it : Catalog.visible(cfg, Catalog.APPS, apps.all, recentFor(Catalog.APPS))) {
            if (fit-- <= 0) break;
            first.add(it);
            jobs.add(artJob(it, cornerIcon));
        }
        try {
            io.submit(() -> {
                for (Runnable job : jobs) job.run();
            }).get();
        } catch (ExecutionException e) {
            return; // the tiles load their art as they are drawn
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (Item it : first) it.artRequested = true;
    }

    /** What the tiles' art depends on besides the apps themselves. */
    private String artState() {
        return cfg.get(Config.TILE_STYLE) + "|" + cfg.get(IconPack.KEY, IconPack.OFF)
                + "|" + cfg.joined(IconPack.CHOSEN) + "|" + cfg.joined("appstyle.");
    }

    /** Loads one tile's art: the settings are read here, on the UI thread; run it on io. */
    private Runnable artJob(Item it, boolean cornerIcon) {
        String pack = cfg.get(IconPack.KEY, IconPack.OFF);
        String chosen = cfg.get(IconPack.CHOSEN + it.id, null);
        return () -> {
            Catalog.art(getPackageManager(), it, cornerIcon, replacementArt(it, chosen, pack));
            Drawable custom = Catalog.customBanner(this, it);
            if (custom != null) {
                it.banner = custom;
                it.icon = null;
            }
        };
    }

    /**
     * The picture that replaces an app's own art: the icon picked for it from the chosen pack,
     * then the chosen pack's match. Null keeps the app's own. io thread only.
     */
    private Drawable replacementArt(Item it, String chosen, String pack) {
        if (IconPack.OWN.equals(chosen)) return null;
        IconPack p = iconPack(pack);
        if (p == null) return null;
        // A picked icon belongs to the pack it came from and only counts while that pack is the
        // chosen one: switching packs needs no tile fixed by hand, switching back restores it.
        String picked = IconMatch.picked(chosen, pack);
        Drawable d = picked == null ? null : p.picture(picked);
        return d != null ? d : p.drawable(it);
    }

    /** An icon pack by package, read once. io thread only. */
    private IconPack iconPack(String pkg) {
        if (!packs.containsKey(pkg)) {
            IconPack p = IconPack.load(getPackageManager(), pkg);
            packs.put(pkg, p);
            if (p != null) Keeper.note("icon pack " + pkg + ": " + p.size() + " apps");
        }
        return packs.get(pkg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        cfg.refresh();
        cfg.visited();
        if (cfg.version() != applied) apply();
        else background();
        refreshRecent();
        loadLibrary();
        hero.start();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(time, f);
        tick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        hero.stop();
        stopColorAnimation();
        targetColor = 0;
        unregisterReceiver(time);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        colorIo.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on this TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving == null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN
                    && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                    && getCurrentFocus() == allAppsButton) {
                openAllApps();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && locked()) {
            // Repeats keep arriving while the button is held; the first one past the hold opens it.
            if (e.getRepeatCount() == 0) {
                unlockOpened = false;
            } else if (!unlockOpened && e.getEventTime() - e.getDownTime() >= UNLOCK_MS) {
                unlockOpened = true;
                startActivity(new Intent(this, Options.class));
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /**
     * The scroll position for a focused rect {@code r}: the earliest of the top or a section
     * title that still shows the row holding {@code r} whole.
     */
    private int rowTarget(ScrollView page, Rect r) {
        View content = page.getChildAt(0);
        List<int[]> rows = rowBlocks(content);
        int[] at = null;
        for (int[] b : rows) {
            if (r.centerY() >= b[0]) at = b;
        }
        if (at == null) return 0;
        int range = Math.max(0, content.getHeight() - page.getHeight());
        return Math.max(0, Math.min(range, stopFor(at, rows, page.getHeight())));
    }

    /**
     * Title top and strip bottom of each visible horizontal row, in {@code content}'s coordinates.
     */
    private List<int[]> rowBlocks(View content) {
        List<int[]> rows = new ArrayList<>();
        for (Row row : rows()) {
            if (row == apps) continue;
            if (row.scroll.getVisibility() == View.VISIBLE) {
                rows.add(new int[] {topIn(row.title, content), topIn(row.scroll, content) + row.scroll.getHeight()});
            }
        }
        return rows;
    }

    /** The earliest of the top or a row title that shows the whole of {@code at}. */
    private static int stopFor(int[] at, List<int[]> rows, int height) {
        if (height >= at[1]) return 0;
        for (int[] b : rows) {
            if (b[0] <= at[0] && b[0] + height >= at[1]) return b[0];
        }
        return at[0];
    }

    /**
     * When the page is taller than the screen, pads its bottom just enough that every row's stop
     * is reachable. Otherwise the last stop is clamped and the clock above it shows half cut.
     */
    private void fitBottom(ScrollView page) {
        View content = page.getChildAt(0);
        if (content == null || page.getHeight() == 0) return;
        int base = dp(32), height = page.getHeight();
        int natural = content.getHeight() - content.getPaddingBottom() + base;
        int want = base;
        if (natural > height) {
            int last = 0;
            List<int[]> rows = rowBlocks(content);
            for (int[] b : rows) last = Math.max(last, stopFor(b, rows, height));
            want = base + Math.max(0, last + height - natural);
        }
        if (content.getPaddingBottom() != want) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), want);
        }
    }

    /** {@code v}'s top edge in {@code content}'s coordinates. */
    private static int topIn(View v, View content) {
        int y = 0;
        while (v != null && v != content) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    private View build() {
        root = new FrameLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                if (focused instanceof Tile && Catalog.APPS.equals(((Tile) focused).kind)) {
                    if (direction == View.FOCUS_DOWN) return focused;
                    if (direction == View.FOCUS_UP) {
                        if (heroShown()) return hero;
                        View above = inputs.title.getVisibility() == View.VISIBLE
                                ? nearestBottom(inputs.tiles(), focused) : null;
                        if (above == null && allApps.title.getVisibility() == View.VISIBLE) {
                            above = nearestBottom(allApps.tiles(), focused);
                        }
                        if (above != null) return above;
                        View chip = nearestToolbarChip(focused);
                        if (chip != null) setToolbarVisible(true);
                        return chip != null ? chip : focused;
                    }
                }
                if (chips.contains(focused) && direction == View.FOCUS_DOWN) {
                    if (heroShown()) return hero;
                    View below = allApps.title.getVisibility() == View.VISIBLE
                            ? nearest(allApps.tiles(), focused) : null;
                    if (below == null && inputs.title.getVisibility() == View.VISIBLE) {
                        below = nearest(inputs.tiles(), focused);
                    }
                    if (below == null && favoritesPanel.getVisibility() == View.VISIBLE
                            && apps.tiles().getChildCount() > 0) {
                        below = nearestFavourite(focused);
                    }
                    if (below != null) return below;
                }
                return super.focusSearch(focused, direction);
            }
        };
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        tint = new View(this);
        root.addView(tint, new FrameLayout.LayoutParams(MATCH, MATCH));
        // Kept above the wallpaper, dim and adaptive tint, but below every home-screen element.
        gradient = new View(this);
        root.addView(gradient, new FrameLayout.LayoutParams(MATCH, MATCH));

        ScrollView scroll = new ScrollView(this) {
            /** Scrolls in whole rows: the focused row shows complete, and a row starts at the top edge. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return rowTarget(this, r) - getScrollY();
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                fitBottom(this);
            }

            /**
             * Up and down only move the focus; scrolling follows it. Left to ScrollView, a press
             * with nothing focusable that way scrolls half a screen and leaves rows cut.
             */
            @Override
            public boolean executeKeyEvent(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_PAGE_UP:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                    case KeyEvent.KEYCODE_SPACE:
                        return false;
                    default:
                        return super.executeKeyEvent(e);
                }
            }
        };
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        homeScroll = scroll;
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                // The library banner sits between the chips and the rows; Down from it
                // goes where Down from the chips goes without it.
                if (focused == hero && direction == View.FOCUS_UP) {
                    for (TextView c : chips) {
                        if (c.getVisibility() == View.VISIBLE) {
                            setToolbarVisible(true);
                            return c;
                        }
                    }
                }
                if (focused == hero && direction != View.FOCUS_DOWN) return focused;
                if (direction == View.FOCUS_DOWN && chips.contains(focused) && heroShown()) {
                    return hero;
                }
                if (direction == View.FOCUS_DOWN && (chips.contains(focused) || focused == hero)) {
                    View remembered = rememberedTile(lastFocusedKind);
                    if (remembered != null) return remembered;
                    for (Row row : rows()) {
                        View below = nearest(row.tiles(), focused);
                        if (below != null) return below;
                    }
                }
                if (focused instanceof Tile
                        && focused.getParent() instanceof ViewGroup) {
                    Tile tile = (Tile) focused;
                    ViewGroup parent = (ViewGroup) tile.getParent();
                    int idx = parent.indexOfChild(tile);
                    int count = parent.getChildCount();

                    // Left and right stop at the edge of a row instead of jumping away
                    // from it: no tile of another row takes the focus.
                    if (direction == View.FOCUS_LEFT) {
                        if (idx == 0) return focused;
                    }
                    if (direction == View.FOCUS_RIGHT) {
                        if (idx == count - 1) return focused;
                    }

                    // Up from the first line of the apps row goes to the library banner.
                    if (direction == View.FOCUS_UP && Catalog.APPS.equals(tile.kind)
                            && heroShown()) {
                        return hero;
                    }

                    // Crossing a section restores that section's own last tile, even if the
                    // same app is also pinned in the favourites strip.
                    if (direction == View.FOCUS_DOWN) {
                        View below = adjacentTile(tile.kind, 1, focused);
                        if (below == null && favoritesPanel.getVisibility() == View.VISIBLE
                                && (Catalog.INPUTS.equals(tile.kind) || Catalog.ALL_APPS.equals(tile.kind))) {
                            below = nearestFavourite(focused);
                        }
                        return below != null ? below : focused;
                    }
                    if (direction == View.FOCUS_UP) {
                        View above = adjacentTile(tile.kind, -1, focused);
                        if (above != null) return above;
                        View chip = nearestToolbarChip(focused);
                        if (chip != null && !locked()) {
                            setToolbarVisible(true);
                            return chip;
                        }
                        if (locked()) return focused;
                    }
                }
                View next = super.focusSearch(focused, direction);
                if (next == null && direction == View.FOCUS_UP && !chips.isEmpty()) {
                    int[] at = new int[2];
                    focused.getLocationOnScreen(at);
                    int x = at[0] + focused.getWidth() / 2;
                    TextView best = null;
                    int bestGap = Integer.MAX_VALUE;
                    for (TextView c : chips) {
                        c.getLocationOnScreen(at);
                        int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
                        if (gap < bestGap) {
                            bestGap = gap;
                            best = c;
                        }
                    }
                    return best;
                }
                return next;
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, dp(20), 0, dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setClipChildren(false);
        head.setClipToPadding(false);
        head.setGravity(Gravity.TOP | Gravity.LEFT);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setClipChildren(false);
        toolbar.setClipToPadding(false);
        toolbar.addView(allAppsChip());
        toolbar.addView(chip("Widgets", R.drawable.chip_widgets, R.drawable.chip_widgets_mark,
                v -> startActivity(new Intent(this, WidgetEditor.class))));
        toolbar.addView(chip(getString(R.string.options), R.drawable.chip_options, R.drawable.chip_options_mark,
                v -> startActivity(new Intent(this, Options.class))));
        toolbar.addView(chip(getString(R.string.settings), R.drawable.chip_settings, R.drawable.chip_settings_mark,
                v -> openSettings()));
        head.addView(toolbar, new LinearLayout.LayoutParams(MATCH, WRAP));

        head.setPadding(dp(24), 0, dp(24), 0);
        root.addView(head, new FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP));

        if (!"1".equals(cfg.get("widgets.initialized", ""))) {
            widgetLayout = WidgetLayout.defaults();
            cfg.set(WidgetLayout.CONFIG_KEY, widgetLayout.encode());
            cfg.set("widgets.initialized", "1");
        } else {
            widgetLayout = WidgetLayout.decode(cfg.get(WidgetLayout.CONFIG_KEY, ""));
        }
        widgetGrid = new WidgetGridView(this);
        widgetGrid.setLayout(widgetLayout);
        for (WidgetVisibility.Definition definition : WidgetVisibility.ALL) {
            widgetGrid.setWidgetEnabled(definition.id, WidgetVisibility.isEnabled(cfg, definition.id));
        }

        clockWidget = new LinearLayout(this);
        clockWidget.setOrientation(LinearLayout.VERTICAL);
        clockWidget.setGravity(Gravity.CENTER_VERTICAL);
        clockWidget.setPadding(dp(18), dp(4), dp(18), dp(4));
        clockWidget.setBackground(null);
        clock = Ui.text(this, 32, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        date.setPadding(dp(2), dp(2), 0, 0);
        clockWidget.addView(clock);
        clockWidget.addView(date);
        widgetGrid.addWidgetView("clock", clockWidget);

        memoryWidget = new FrameLayout(this);
        memoryWidget.setBackground(null);
        memoryWidget.setVisibility(View.GONE);
        meter = new Meter(this);
        meter.setWords(getString(R.string.meter_title), getString(R.string.meter_free),
                getString(R.string.meter_low));
        meter.setAccent(cfg.accent());
        FrameLayout.LayoutParams meterLp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER);
        ((FrameLayout) memoryWidget).addView(meter, meterLp);
        widgetGrid.addWidgetView("memory", memoryWidget);

        networkWidget = widgetText("Network\nChecking connection…");
        FrameLayout storageFrame = new FrameLayout(this);
        storageFrame.setBackground(null);
        storageMeter = new Meter(this);
        storageMeter.setWords("Storage", "", "Running low");
        storageMeter.setAccent(cfg.accent());
        storageWidget = storageFrame;
        storageFrame.addView(storageMeter, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        textWidget = widgetText(cfg.get("widgets.text", "Custom text"));
        diagnosticsWidget = widgetText("DumbTV diagnostics\nCollecting process information…");
        diagnosticsWidget.setMaxLines(4);
        diagnosticsWidget.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        diagnosticsWidget.setPadding(dp(6), dp(2), dp(6), dp(2));
        deviceWidget = widgetText(WidgetDiagnostics.deviceInformation());
        deviceWidget.setMaxLines(3);
        deviceWidget.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        widgetGrid.addWidgetView("network", networkWidget);
        widgetGrid.addWidgetView("storage", storageWidget);
        widgetGrid.addWidgetView("text", textWidget);
        widgetGrid.addWidgetView("diagnostics", diagnosticsWidget);
        widgetGrid.addWidgetView("device", deviceWidget);

        widgetHeight = WidgetGridView.preferredHeight(this);
        FrameLayout.LayoutParams widgetLp = new FrameLayout.LayoutParams(MATCH, widgetHeight, Gravity.TOP);
        widgetLp.leftMargin = dp(24);
        widgetLp.rightMargin = dp(24);
        widgetLp.topMargin = dp(64);
        root.addView(widgetGrid, widgetLp);

        hero = new Hero(this, ui, io);
        hero.setOnClickListener(v -> hero.openCurrent());
        hero.setOnFocusChangeListener((v, has) -> {
            if (has) focusBackground(null);
        });
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(MATCH, dp(220));
        heroLp.leftMargin = dp(24);
        heroLp.rightMargin = dp(24);
        heroLp.topMargin = dp(2);
        column.addView(hero, heroLp);
        this.column = column;

        favoritesPanel = new LinearLayout(this);
        favoritesPanel.setOrientation(LinearLayout.VERTICAL);
        favoritesPanel.setClipChildren(false);
        favoritesPanel.setClipToPadding(false);
        favoritesPanel.setBackground(Ui.isGlass(this)
                ? Ui.surface(this, Ui.optionSurface(this), dp(16)) : null);
        favoritesPanel.setPadding(0, dp(2), 0, dp(2));
        favoritesPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams favoritesLp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        favoritesLp.leftMargin = dp(12);
        favoritesLp.rightMargin = dp(12);
        favoritesLp.bottomMargin = dp(8);
        root.addView(favoritesPanel, favoritesLp);

        apps = new Row(Catalog.APPS, getString(R.string.row_apps), favoritesPanel);
        apps.title.setVisibility(View.GONE);
        allApps = new Row(Catalog.ALL_APPS, getString(R.string.row_all_apps), column);
        allApps.setVisible(false);
        inputs = new Row(Catalog.INPUTS, getString(R.string.row_inputs), column);

        FrameLayout.LayoutParams scrollLp = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        scrollLp.topMargin = dp(64) + widgetHeight + dp(12);
        scrollLp.bottomMargin = favoriteBottomReserve();
        scroll.setLayoutParams(scrollLp);
        return root;
    }

    /**
     * The index of the tile below {@code tile}, or -1 when it is in the last line. Only the
     * last line of a grid can be shorter than the others: a tile with no tile directly under
     * it takes the last tile of the line below, the nearest one in that line.
     */
    private int favoriteBottomReserve() {
        return apps == null || favoritesPanel.getVisibility() != View.VISIBLE
                ? dp(16) : favoriteCardSize()[1] + dp(68);
    }

    private int[] favoriteCardSize() {
        int[] base = cardSize();
        String spacing = cfg.get(Config.TILE_SPACING);
        int gap = dp("compact".equals(spacing) ? 10 : "spacious".equals(spacing) ? 28 : 18);
        int available = getResources().getDisplayMetrics().widthPixels - dp(24);
        int widthForSix = Math.max(dp(112), (available - gap * 5) / 6);
        int width = Math.min(base[0], widthForSix);
        return new int[] {width, Math.round(width * 9f / 16f)};
    }

    private void toolbarFocusChanged(boolean focused) {
        if (focused) {
            setToolbarVisible(true);
        } else if ("on".equals(cfg.get(Config.TOOLBAR_AUTO_HIDE))) {
            ui.post(() -> {
                if (toolbar != null && !toolbar.hasFocus()
                        && "on".equals(cfg.get(Config.TOOLBAR_AUTO_HIDE))) {
                    setToolbarVisible(false);
                }
            });
        }
    }

    private void setToolbarVisible(boolean visible) {
        if (head == null || toolbar == null) return;
        if (!visible && toolbar.hasFocus()) return;
        if (toolbarCollapsed == !visible) return;
        for (TextView chip : chips) {
            boolean enabled = visible && chip.getVisibility() == View.VISIBLE;
            chip.setFocusable(enabled);
            chip.setClickable(enabled);
        }
        // The toolbar owns a permanent top slot. Hiding it changes only its visual state:
        // the widget grid, scrolling content and bottom favourites never reflow or overlap.
        head.setClipChildren(false);
        head.setClipToPadding(false);
        head.animate().cancel();
        head.setAlpha(visible ? 0f : 1f);
        head.setTranslationY(visible ? -dp(60) : 0f);
        toolbarCollapsed = !visible;
        head.animate().alpha(visible ? 1f : 0f)
                .translationY(visible ? 0f : -dp(60)).setDuration(160).start();
    }

    private View nearestToolbarChip(View from) {
        if (chips.isEmpty()) return null;
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        TextView best = null;
        int bestGap = Integer.MAX_VALUE;
        for (TextView chip : chips) {
            if (chip.getVisibility() != View.VISIBLE) continue;
            chip.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + chip.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = chip;
            }
        }
        return best;
    }

    /**
     * The child in the favourites strip nearest {@code from}; used when moving up from favourites.
     */
    private View nearestFavourite(View from) {
        ViewGroup strip = apps.tiles();
        int count = strip.getChildCount();
        if (count == 0) return null;
        int start = count > 6 && count % 3 == 0 ? count / 3 : 0;
        int end = count > 6 && count % 3 == 0 ? start + count / 3 : count;
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = start; i < end; i++) {
            View child = strip.getChildAt(i);
            child.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + child.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = child;
            }
        }
        return best;
    }

    private static View nearestBottom(ViewGroup strip, View from) {
        int n = strip.getChildCount();
        if (n == 0) return null;
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        int[] ys = new int[n];
        int bottomY = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            strip.getChildAt(i).getLocationOnScreen(at);
            ys[i] = at[1];
            bottomY = Math.max(bottomY, at[1]);
        }
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (ys[i] != bottomY) continue;
            View child = strip.getChildAt(i);
            child.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + child.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = child;
            }
        }
        return best;
    }

    private static View nearest(ViewGroup strip, View from) {
        int n = strip.getChildCount();
        if (n == 0) return null;
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        int[] ys = new int[n];
        int topY = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            strip.getChildAt(i).getLocationOnScreen(at);
            ys[i] = at[1];
            topY = Math.min(topY, at[1]);
        }
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (ys[i] != topY) continue;
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private boolean locked() {
        return "on".equals(cfg.get(Config.LOCK));
    }

    /**
     * A chip in the corner. Options › Chip style picks its look: the text chip (the default), or
     * a round icon button whose name shows next to the icon only while it has the focus, so the
     * corner stays quiet and the remote still says where OK goes. {@code shape} and
     * {@code mark} are the two layers {@link Ui#chipIcon} tints; {@link #apply} draws them.
     */
    private TextView chip(String label, int shape, int mark, View.OnClickListener click) {
        TextView v = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        v.setTag(new int[] {shape, mark});
        v.setContentDescription(label);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            chipLook(v, has);
            view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(110).start();
            toolbarFocusChanged(has);
            if (has) focusBackground(null);
        });
        chipLook(v, false);
        v.setMinWidth(dp(112));
        v.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(48));
        lp.leftMargin = dp(8);
        lp.topMargin = dp(4);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private void openAllApps() {
        startActivity(new Intent(this, AllApps.class));
        overridePendingTransition(R.anim.slide_up, R.anim.stay);
    }

    /** Navigation chip that opens the full installed-app catalogue. */
    private TextView allAppsChip() {
        TextView v = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        allAppsButton = v;
        v.setText(R.string.all_apps);
        v.setContentDescription(getString(R.string.all_apps));
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setMinWidth(dp(112));
        v.setMinHeight(dp(48));
        v.setOnClickListener(view -> openAllApps());
        v.setOnFocusChangeListener((view, has) -> {
            chipLook(v, has);
            view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(110).start();
            toolbarFocusChanged(has);
            if (has) focusBackground(null);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(48));
        lp.leftMargin = dp(8);
        lp.topMargin = dp(4);
        v.setLayoutParams(lp);
        v.setTag(new int[] {R.drawable.chip_apps, R.drawable.chip_apps_mark});
        chips.add(v);
        return v;
    }

    private boolean iconChips() {
        return "icons".equals(cfg.get(Config.CHIPS));
    }

    /** Text and padding for the chip style: the name always for text, on focus only for icons. */
    private void chipLook(TextView c, boolean focused) {
        if (c.getTag() == null || !iconChips()) {
            c.setText(c.getContentDescription());
            c.setCompoundDrawablePadding(0);
            c.setPadding(dp(18), dp(9), dp(18), dp(9));
            return;
        }
        c.setText(focused ? c.getContentDescription() : "");
        c.setCompoundDrawablePadding(focused ? dp(8) : 0);
        c.setPadding(dp(10), dp(10), dp(focused ? 16 : 10), dp(10));
    }

    private TextView widgetText(String value) {
        TextView view = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        view.setText(value);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        view.setMaxLines(3);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setBackground(null);
        view.setFocusable(false);
        return view;
    }

    private void applyWidgetFontSizes() {
        if (widgetGrid == null || widgetLayout == null) return;
        for (WidgetLayout.Widget widget : widgetLayout.items()) {
            int fallback = "clock".equals(widget.type) ? 32
                    : "diagnostics".equals(widget.type) ? 11
                    : "device".equals(widget.type) ? 12
                    : "memory".equals(widget.type) || "storage".equals(widget.type) ? 13 : 14;
            int size;
            try {
                size = Integer.parseInt(cfg.get("widgets.font." + widget.id,
                        String.valueOf(fallback)));
            } catch (NumberFormatException ignored) {
                size = fallback;
            }
            if ("clock".equals(widget.id)) {
                clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
                date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                        Math.max(10, Math.round(size * 0.4f)));
                continue;
            }
            for (int i = 0; i < widgetGrid.getChildCount(); i++) {
                View child = widgetGrid.getChildAt(i);
                if (!widget.id.equals(widgetGrid.widgetId(child))) continue;
                if (child instanceof TextView) {
                    ((TextView) child).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
                } else if (child instanceof FrameLayout) {
                    for (int j = 0; j < ((FrameLayout) child).getChildCount(); j++) {
                        View inner = ((FrameLayout) child).getChildAt(j);
                        if (inner instanceof Meter) ((Meter) inner).setFontSize(size);
                    }
                }
                break;
            }
        }
    }

    private void updateSystemWidgets() {
        if (networkWidget == null || storageWidget == null || widgetLayout == null) return;
        if (widgetLayout.find("network") != null && WidgetVisibility.isEnabled(cfg, "network")) {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
            String state = info == null || !info.isConnected() ? "Disconnected"
                    : "Connected · " + info.getTypeName();
            networkWidget.setText("Network\n" + state);
        }
        if (widgetLayout.find("storage") != null && WidgetVisibility.isEnabled(cfg, "storage")) {
            storageMeter.updateStorage(this);
        }
        if (widgetLayout.find("diagnostics") != null && WidgetVisibility.isEnabled(cfg, "diagnostics")) {
            diagnosticsWidget.setText(WidgetDiagnostics.snapshot(this));
        }
        if (widgetLayout.find("device") != null && WidgetVisibility.isEnabled(cfg, "device")) {
            deviceWidget.setText(deviceInformation());
        }
    }

    private String deviceInformation() {
        return WidgetDiagnostics.deviceInformation();
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        boolean icons = iconChips();
        int align = "left".equals(cfg.get(Config.TOOLBAR_ALIGN)) ? Gravity.LEFT
                : "center".equals(cfg.get(Config.TOOLBAR_ALIGN)) ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT;
        head.setGravity(Gravity.TOP);
        toolbar.setGravity(Gravity.CENTER_VERTICAL | align);
        toolbar.setBackground(null);
        widgetLayout = WidgetLayout.decode(cfg.get(WidgetLayout.CONFIG_KEY, ""));
        widgetGrid.setLayout(widgetLayout);
        int widgetSurface = Ui.tileSurface(this, true);
        boolean widgetBackgrounds = "on".equals(cfg.get(Config.WIDGET_BACKGROUNDS));
        clockWidget.setBackground(widgetBackgrounds ? Ui.surface(this, widgetSurface, dp(12)) : null);
        memoryWidget.setBackground(widgetBackgrounds ? Ui.surface(this, widgetSurface, dp(12)) : null);
        networkWidget.setBackground(widgetBackgrounds ? Ui.surface(this, widgetSurface, dp(12)) : null);
        storageWidget.setBackground(widgetBackgrounds ? Ui.surface(this, widgetSurface, dp(12)) : null);
        textWidget.setBackground(widgetBackgrounds ? Ui.surface(this, widgetSurface, dp(12)) : null);
        diagnosticsWidget.setBackground(widgetBackgrounds ? Ui.surface(this, widgetSurface, dp(12)) : null);
        deviceWidget.setBackground(widgetBackgrounds ? Ui.surface(this, widgetSurface, dp(12)) : null);
        favoritesPanel.setBackground(Ui.isGlass(this)
                ? Ui.surface(this, Ui.optionSurface(this), dp(16)) : null);
        FrameLayout.LayoutParams scrollParams = (FrameLayout.LayoutParams) homeScroll.getLayoutParams();
        scrollParams.bottomMargin = favoriteBottomReserve();
        homeScroll.setLayoutParams(scrollParams);
        textWidget.setText(cfg.get("widgets.text", "Custom text"));
        applyWidgetFontSizes();
        updateSystemWidgets();
        for (TextView c : chips) {
            Ui.focusStyle(c, accent, Ui.isGlass(this) ? 0x55303D4B : 0x14FFFFFF, dp(icons ? 21 : 18));
            int[] art = (int[]) c.getTag();
            c.setCompoundDrawablesRelative(icons && art != null ? Ui.chipIcon(getDrawable(art[0]), getDrawable(art[1]),
                    accent, dp(22)) : null, null, null, null);
            if (art == null) c.setText(c.getContentDescription());
            chipLook(c, c.isFocused());
            c.setVisibility(locked() ? View.GONE : View.VISIBLE);
        }
        for (WidgetVisibility.Definition definition : WidgetVisibility.ALL) {
            widgetGrid.setWidgetEnabled(definition.id, WidgetVisibility.isEnabled(cfg, definition.id));
        }
        meter.setAccent(accent);
        storageMeter.setAccent(accent);
        hero.setAccent(accent);
        apps.title.setText(getString(R.string.row_pinned_apps).toUpperCase(Locale.ROOT));
        String state = artState();
        if (!state.equals(artState)) {
            artState = state;
            for (Item it : apps.all) it.artRequested = false;
        }
        render(apps);
        render(allApps);
        render(inputs);
        background();
        tick();
        if ("on".equals(cfg.get(Config.TOOLBAR_AUTO_HIDE)) && !toolbar.hasFocus()) {
            setToolbarVisible(false);
        } else {
            setToolbarVisible(true);
        }
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : apps.all) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                            fresh.backgroundColor = old.backgroundColor;
                        }
                    }
                }
                apps.all = list;
                render(apps);
                render(allApps);
                    });
        });
    }

    private boolean heroShown() {
        return hero.getVisibility() == View.VISIBLE && hero.hasEntries();
    }

    /** Reads the library folder off the UI thread; the banner hides while it has no titles. */
    private void loadLibrary() {
        int generation = ++libraryGeneration;
        boolean on = "on".equals(cfg.get(Config.LIBRARY));
        io.execute(() -> {
            List<Library.Entry> list = on ? Library.load(this, cfg) : new ArrayList<>();
            ui.post(() -> {
                if (generation != libraryGeneration || isDestroyed()) return;
                boolean was = heroShown();
                hero.setEntries(list);
                if (was && !heroShown()) focusFirst();
                // A shorter top keeps the clock, banner and apps row on one screen.
                column.setPadding(0, heroShown() ? dp(16) : dp(24), 0, column.getPaddingBottom());
                adjustAppRowPosition();
            });
        });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    /** The last-used times to sort {@code kind} by, or null when it keeps the user's order. */
    private Map<String, Long> recentFor(String kind) {
        return Catalog.APPS.equals(kind) && "recent".equals(cfg.get(Config.ORDER)) ? recent : null;
    }

    /** With App order › Recently used, re-sorts the apps row on the way back home. */
    private void refreshRecent() {
        boolean sortPinned = recentFor(Catalog.APPS) != null;
        if (!sortPinned) return;
        io.execute(() -> {
            Map<String, Long> times = Recent.times(this);
            ui.post(() -> {
                if (isDestroyed()) return;
                List<Item> before = sortPinned
                        ? Catalog.visible(cfg, Catalog.APPS, apps.all, recent) : new ArrayList<>();
                recent = times;
                if (sortPinned && !before.equals(Catalog.visible(cfg, Catalog.APPS, apps.all, recent))) {
                    render(apps);
                }
                    });
        });
    }

    private void render(Row row) {
        if (row == allApps) {
            row.setVisible(false);
            return;
        }
        int renderGeneration = ++row.renderGeneration;
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        ViewGroup target = row.strip;
        row.strip.removeAllViews();

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind,
                row == allApps ? apps.all : row.all, recentFor(row.kind))
                : new ArrayList<>();
        int[] size = row == apps ? favoriteCardSize() : cardSize();
        int w = size[0], h = Catalog.isApp(row.kind) ? size[1] : Math.round(size[1] * 0.56f);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        boolean loopingFavourites = row == apps && shown.size() > 6;
        int copies = loopingFavourites ? 3 : 1;
        row.initialFocusIndex = loopingFavourites ? shown.size() : 0;
        String spacing = cfg.get(Config.TILE_SPACING);
        int tileGap = dp("compact".equals(spacing) ? 10 : "spacious".equals(spacing) ? 28 : 18);
        if (row == apps) {
            int alignment = "left".equals(cfg.get(Config.FAVOURITES_ALIGN)) ? Gravity.LEFT
                    : "right".equals(cfg.get(Config.FAVOURITES_ALIGN)) ? Gravity.RIGHT
                    : Gravity.CENTER_HORIZONTAL;
            row.strip.setGravity(Gravity.CENTER_VERTICAL | (loopingFavourites ? Gravity.LEFT : alignment));
            int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
            int contentWidth = shown.size() * w + Math.max(0, shown.size() - 1) * tileGap;
            row.strip.setLayoutParams(new FrameLayout.LayoutParams(
                    loopingFavourites || contentWidth > availableWidth ? WRAP : MATCH, WRAP));
        }
        int index = 0, restore = -1;
        for (int copy = 0; copy < copies; copy++) {
            for (int itemIndex = 0; itemIndex < shown.size(); itemIndex++) {
                final int copyIndex = copy;
                final int itemPosition = itemIndex;
                Item it = shown.get(itemIndex);
                Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent);
                t.setOnClickListener(v -> open((Tile) v));
                t.setOnFocusChangeListener((v, has) -> {
                    if (has) {
                        Tile tile = (Tile) v;
                        lastFocusedKind = tile.kind;
                        lastIds.put(tile.kind, tile.item.id);
                        focusBackground(tile.item);
                        if (loopingFavourites && copyIndex != 1) {
                            ui.post(() -> {
                                if (renderGeneration != row.renderGeneration
                                        || getCurrentFocus() != tile || tile.getParent() != target) return;
                                View equivalent = target.getChildAt(shown.size() + itemPosition);
                                if (equivalent != null) {
                                    // Use the actual measured tile positions, not an estimated
                                    // cycle width, so spacing and card changes cannot introduce a jump.
                                    int offset = equivalent.getLeft() - tile.getLeft();
                                    row.scroll.scrollTo(row.scroll.getScrollX() + offset, 0);
                                    equivalent.requestFocus();
                                }
                            });
                        }
                    }
                });
                t.setOnLongClickListener(v -> {
                    if (!locked()) menu((Tile) v);
                    return true;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.rightMargin = row == apps && copy == copies - 1
                        && itemIndex == shown.size() - 1 ? 0 : tileGap;
                target.addView(t, lp);
                if (it.id.equals(keep) && (!loopingFavourites || copy == 1)) restore = index;
                index++;
                String tileStyle = cfg.get(Config.TILE_STYLE);
                boolean needsStyleIcon = "afterglow".equals(tileStyle)
                        && cfg.get(AppStyle.key(it, "banner"), null) == null && it.icon == null;
                if (Catalog.isApp(row.kind) && (!it.artRequested || needsStyleIcon)) loadArt(it);
            }
        }
        if (loopingFavourites) {
            final int initialScrollIndex = restore >= 0 ? restore : row.initialFocusIndex;
            row.scroll.post(() -> {
                if (renderGeneration != row.renderGeneration) return;
                View initialTile = row.tiles().getChildAt(initialScrollIndex);
                if (initialTile != null) row.scroll.scrollTo(initialTile.getLeft(), 0);
            });
        }
        row.setVisible(row != allApps && !shown.isEmpty());
        if (row == apps) row.title.setVisibility(View.GONE);
        adjustAppRowPosition();
        if (shown.isEmpty()) {
            // Nothing is left to come back to: forget the tile that used to be here.
            lastIds.remove(row.kind);
        }
        if (restore >= 0) {
            target.getChildAt(restore).requestFocus();
        } else if (keep != null && target.getChildCount() > 0) {
            target.getChildAt(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    /** Keep the scrolling content clear of the fixed favourites panel. */
    private void adjustAppRowPosition() {
        if (homeScroll == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) homeScroll.getLayoutParams();
        int reserve = favoriteBottomReserve();
        if (lp.bottomMargin != reserve) {
            lp.bottomMargin = reserve;
            homeScroll.setLayoutParams(lp);
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        String tileStyle = cfg.get(Config.TILE_STYLE);
        Runnable job = artJob(it, "afterglow".equals(tileStyle));
        io.execute(() -> {
            job.run();
            ui.post(() -> {
                for (Row row : new Row[] {apps, allApps}) {
                    ViewGroup tiles = row.tiles();
                    for (int i = 0; i < tiles.getChildCount(); i++) tiles.getChildAt(i).invalidate();
                }
                if (focusedItem() == it) focusBackground(it);
            });
        });
    }

    private Item focusedItem() {
        View focused = getCurrentFocus();
        return focused instanceof Tile ? ((Tile) focused).item : null;
    }

    private void focusBackground(Item it) {
        String mode = cfg.get(Config.BACKGROUND);
        if (!resumed || !"adaptive".equals(mode) && !"adaptive-tint".equals(mode)) return;
        if ("adaptive-tint".equals(mode) && !tintWallpaper) return;
        if (it != null && it.info != null && it.backgroundColor == 0 && !it.colorRequested
                && (it.banner != null || it.icon != null)) {
            it.colorRequested = true;
            colorIo.execute(() -> {
                int color = FocusColor.from(it.banner != null ? it.banner : it.icon);
                ui.post(() -> {
                    it.backgroundColor = color;
                    if (focusedItem() == it) focusBackground(it);
                });
            });
        }
        int color = it == null || it.backgroundColor == 0 ? FocusColor.FALLBACK : it.backgroundColor;
        if ("adaptive-tint".equals(mode)) color = FocusColor.tint(color);
        if (color == targetColor) return;
        targetColor = color;
        stopColorAnimation();
        if (Settings.Global.getFloat(getContentResolver(), "animator_duration_scale", 1f) == 0f) {
            setAdaptiveColor(color);
            return;
        }
        colorAnimation = ValueAnimator.ofArgb(displayedColor, color);
        colorAnimation.setDuration(250);
        colorAnimation.addUpdateListener(a -> setAdaptiveColor((Integer) a.getAnimatedValue()));
        colorAnimation.start();
    }

    private void setAdaptiveColor(int color) {
        displayedColor = color;
        if ("adaptive-tint".equals(backgroundMode)) tint.setBackgroundColor(color);
        else root.setBackgroundColor(color);
    }

    private void stopColorAnimation() {
        if (colorAnimation != null) {
            colorAnimation.cancel();
            colorAnimation = null;
        }
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "extra-small":
                return new int[] {dp(120), dp(68)};
            case "small":
                return new int[] {dp(144), dp(81)};
            case "large":
                return new int[] {dp(224), dp(126)};
            case "extra-large":
                return new int[] {dp(260), dp(146)};
            default:
                return new int[] {dp(180), dp(101)};
        }
    }

    /**
     * The tile of {@code kind} that was focused last, or null when there is none left.
     */
    private View rememberedTile(String kind) {
        if (kind == null) return null;
        String id = lastIds.get(kind);
        if (id == null) return null;
        View v = findTile(kind, id);
        if (v == null) {
            // The tile is gone: the row was rebuilt without it.
            lastIds.remove(kind);
        }
        return v;
    }

    /** The tile with this id in the row of {@code kind}, or null when there is none. */
    private View findTile(String kind, String id) {
        if (kind == null || id == null) return null;
        Row row = row(kind);
        if (row == null || row.title.getVisibility() != View.VISIBLE) return null;
        ViewGroup tiles = row.tiles();
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View v = tiles.getChildAt(i);
            if (v instanceof Tile
                    && ((Tile) v).kind.equals(kind)
                    && ((Tile) v).item.id.equals(id)) {
                return v;
            }
        }
        return null;
    }

    private Row[] rows() {
        return new Row[] {apps, allApps, inputs};
    }

    private Row row(String kind) {
        for (Row row : rows()) if (row.kind.equals(kind)) return row;
        return null;
    }

    /** The next nonempty section in this direction, skipping hidden sections. */
    private View adjacentTile(String kind, int step, View from) {
        Row[] sections = rows();
        for (int i = 0; i < sections.length; i++) {
            if (!sections[i].kind.equals(kind)) continue;
            for (int j = i + step; j >= 0 && j < sections.length; j += step) {
                Row next = sections[j];
                if (next == apps && step < 0) continue;
                if (next.title.getVisibility() != View.VISIBLE) continue;
                View remembered = rememberedTile(next.kind);
                if (remembered != null) return remembered;
                View nearest = nearest(next.tiles(), from);
                if (nearest != null) return nearest;
            }
            break;
        }
        return null;
    }

    private void focusFirst() {
        View first = null;
        for (Row row : rows()) {
            if (row == apps && row.initialFocusIndex > 0) {
                first = row.tiles().getChildAt(row.initialFocusIndex);
                if (first != null) row.scroll.scrollTo(first.getLeft(), 0);
            } else {
                row.scroll.scrollTo(0, 0);
                if (first == null) first = row.tiles().getChildAt(0);
            }
        }
        landed = apps.tiles().getChildCount() > 0 || allApps.tiles().getChildCount() > 0;
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.36f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now));
        if (widgetGrid.isWidgetEnabled("memory") && widgetLayout.find("memory") != null) {
            meter.update((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE));
        }
        updateSystemWidgets();
        // hourly and time-of-day rotations change while the screen stays open; a no-op otherwise
        background();
    }

    private void background() {
        String mode = cfg.get(Config.BACKGROUND);
        boolean image = "image".equals(mode) || "adaptive-tint".equals(mode);
        File file = image ? cfg.wallpaper() : null;
        tintWallpaper = "adaptive-tint".equals(mode) && file != null;
        if ("adaptive".equals(mode)) {
            if (!mode.equals(backgroundMode)) {
                stopColorAnimation();
                targetColor = 0;
                root.setBackgroundColor(FocusColor.FALLBACK);
                displayedColor = FocusColor.FALLBACK;
                tint.setBackgroundColor(Color.TRANSPARENT);
            }
            backgroundMode = mode;
            focusBackground(focusedItem());
        } else if ("adaptive-tint".equals(mode)) {
            root.setBackgroundColor(file == null ? cfg.background() : Color.BLACK);
            if (!mode.equals(backgroundMode) || file == null) {
                stopColorAnimation();
                targetColor = 0;
                tint.setBackgroundColor(Color.TRANSPARENT);
                displayedColor = Color.TRANSPARENT;
            }
            backgroundMode = mode;
            if (tintWallpaper) focusBackground(focusedItem());
        } else {
            stopColorAnimation();
            targetColor = 0;
            backgroundMode = mode;
            tint.setBackgroundColor(Color.TRANSPARENT);
            root.setBackgroundColor(cfg.background());
        }
        int dim = Integer.parseInt(cfg.get(Config.DIM));
        shade.setBackgroundColor(file == null ? Color.TRANSPARENT : Color.argb(dim * 255 / 100, 0, 0, 0));
        applyGradient();
        String key = file == null ? null : file.getPath() + ":" + file.lastModified();
        if (key == null ? wallKey == null : key.equals(wallKey)) return;
        wallKey = key;
        if (file == null) {
            wall.setImageDrawable(null);
            return;
        }
        io.execute(() -> {
            Bitmap b = decode(file, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            ui.post(() -> {
                if (key.equals(wallKey)) wall.setImageBitmap(b);
            });
        });
    }

    /** Applies a configurable black gradient over the background, never over foreground UI. */
    private void applyGradient() {
        String direction = cfg.get(Config.GRADIENT);
        if ("off".equals(direction)) {
            gradient.setBackground(null);
            return;
        }
        int strength = Integer.parseInt(cfg.get(Config.GRADIENT_STRENGTH));
        int color = cfg.gradientColor();
        int tinted = Color.argb(strength * 255 / 100,
                Color.red(color), Color.green(color), Color.blue(color));
        GradientDrawable.Orientation orientation;
        switch (direction) {
            case "top":
                orientation = GradientDrawable.Orientation.BOTTOM_TOP;
                break;
            case "left":
                orientation = GradientDrawable.Orientation.RIGHT_LEFT;
                break;
            case "right":
                orientation = GradientDrawable.Orientation.LEFT_RIGHT;
                break;
            case "top-left":
                orientation = GradientDrawable.Orientation.BR_TL;
                break;
            case "top-right":
                orientation = GradientDrawable.Orientation.BL_TR;
                break;
            case "bottom-left":
                orientation = GradientDrawable.Orientation.TR_BL;
                break;
            case "bottom-right":
                orientation = GradientDrawable.Orientation.TL_BR;
                break;
            default:
                orientation = GradientDrawable.Orientation.TOP_BOTTOM;
                break;
        }
        gradient.setBackground(new GradientDrawable(orientation,
                new int[] {Color.TRANSPARENT, tinted}));
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Fire OS 6's standalone settings page draws its first row above the screen. */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        if (FireSettings.applies(getPackageManager(), tvSettings)) {
            settingsList();
            return;
        }
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void settingsList() {
        List<Item> sections = FireSettings.sections(this);
        CharSequence[] labels = new CharSequence[sections.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = sections.get(i).label;
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(R.string.settings)
                .setItems(labels, (d, which) -> {
                    Item s = sections.get(which);
                    Keeper.pause();
                    launch(new Intent(s.intent), null);
                })
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    private Intent appIntent(Item it) {
        return new Intent(it.intent);
    }

    private void open(Tile t) {
        if (Catalog.isApp(t.kind)) {
            launch(appIntent(t.item), t.item.id);
            return;
        }
        Intent own = Input.intent(this, cfg, t.item, Catalog.label(cfg, t.item));
        // The built-in screen goes on top of this one in the same task, so BACK returns here and
        // the HOME takeover's singleTask relaunch clears it. NEW_TASK | RESET_TASK_IF_NEEDED on an
        // activity of the task that is already in front only brings that task forward and starts
        // nothing (ActivityStarter.complyActivityFlags), so it is started plainly.
        if (own != null) startActivity(own);
        else launch(new Intent(t.item.intent), null);
    }

    private void launch(Intent i, String pkg) {
        if (Keeper.AMAZON_PKG.equals(pkg)) Keeper.pause();
        try {
            // No transition: on a warm start of an app with a trampoline activity, such as
            // Prime Video, the animation showed home again between the two (#169).
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
            if (pkg != null) Recent.opened(this, pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        if (locked()) return;
        if (Catalog.ALL_APPS.equals(t.kind)) {
            boolean pinned = Catalog.shown(cfg, Catalog.APPS, t.item);
            AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                    .setTitle(Catalog.label(cfg, t.item))
                    .setItems(new String[] {getString(pinned ? R.string.tile_unpin : R.string.tile_pin),
                            getString(R.string.tile_rename), getString(R.string.tile_customize), getString(R.string.tile_app_info)}, (d, which) -> {
                        if (which == 0) {
                            Catalog.setShown(cfg, Catalog.APPS, t.item, !pinned);
                            render(apps);
                            render(allApps);
                        } else if (which == 1) {
                            rename(t);
                        } else if (which == 2) {
                            customizeTile(t);
                        } else {
                            appInfo(t);
                        }
                    })
                    .create();
            dialog.show();
            Ui.styleDialog(dialog);
            return;
        }
        boolean app = Catalog.APPS.equals(t.kind);
        boolean pinning = app && Catalog.shown(cfg, Catalog.APPS, t.item);
        // Icon picks from the chosen pack, so it is only offered while that pack is installed.
        boolean icon = app && iconPacks.containsKey(cfg.get(IconPack.KEY, IconPack.OFF));
        List<Integer> menu = new ArrayList<>();
        menu.add(R.string.tile_move);
        menu.add(R.string.tile_rename);
        if (app) menu.add(R.string.tile_customize);
        if (icon) menu.add(R.string.tile_icon);
        menu.add(pinning ? R.string.tile_unpin : R.string.tile_hide);
        if (app) menu.add(R.string.tile_app_info);
        String[] actions = new String[menu.size()];
        for (int i = 0; i < actions.length; i++) actions[i] = getString(menu.get(i));
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (menu.get(which)) {
                        case R.string.tile_move:
                            if (t.getParent() == null) break;
                            if (app && recentFor(t.kind) != null) {
                                Toast.makeText(this, R.string.move_needs_order, Toast.LENGTH_LONG).show();
                                break;
                            }
                            moving = t;
                            t.setMoving(true);
                            break;
                        case R.string.tile_rename:
                            rename(t);
                            break;
                        case R.string.tile_customize:
                            customizeTile(t);
                            break;
                        case R.string.tile_icon:
                            startActivity(new Intent(this, IconPicker.class)
                                    .putExtra(IconPicker.APP, t.item.id)
                                    .putExtra(IconPicker.LABEL, Catalog.label(cfg, t.item).toString()));
                            break;
                        case R.string.tile_hide:
                        case R.string.tile_unpin:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            render(app ? apps : inputs);
                            Toast.makeText(this, pinning ? R.string.unpinned_toast : R.string.hidden_toast,
                                    Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            appInfo(t);
                    }
                })
                .create();
        dialog.show();
        Ui.styleDialog(dialog);
    }

    /** Per-app appearance overrides; unset properties continue to inherit global settings. */
    private void customizeTile(Tile tile) {
        AppStyleEditor.show(this, cfg, tile.item, () -> {
            tile.item.artRequested = false;
            apply();
        });
    }

    private void appInfo(Tile t) {
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", t.item.id, null)), null);
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(R.string.rename_title)
                .setView(box)
                .setPositiveButton(R.string.save, (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton(R.string.original, (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        Ui.styleDialog(d);
    }

    private void shift(int by) {
        ViewGroup strip = (ViewGroup) moving.getParent();
        int from = strip.indexOfChild(moving), to = from + by;
        if (to < 0 || to >= strip.getChildCount()) return;
        ViewGroup.LayoutParams lp = moving.getLayoutParams();
        strip.removeViewAt(from);
        strip.addView(moving, to, lp);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        ViewGroup strip = (ViewGroup) t.getParent();
        if (strip == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < strip.getChildCount(); i++) ids.add(((Tile) strip.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
