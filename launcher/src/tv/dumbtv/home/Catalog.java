package tv.dumbtv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Where tiles come from, and which of them the user wants on the home screen. */
final class Catalog {
    static final String APPS = "apps", ALL_APPS = "allapps", INPUTS = "inputs";

    private Catalog() {}

    static boolean isApp(String kind) {
        return APPS.equals(kind) || ALL_APPS.equals(kind);
    }

    /** Every launchable app, TV apps first; drawables are left for {@link #art}. */
    static List<Item> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Item> found = new LinkedHashMap<>();
        collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER, true, c.getPackageName());
        collect(pm, found, Intent.CATEGORY_LAUNCHER, false, c.getPackageName());
        List<Item> out = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return out;
    }

    private static void collect(PackageManager pm, Map<String, Item> found, String category,
            boolean leanback, String self) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> matches;
        try {
            matches = pm.queryIntentActivities(query, 0);
        } catch (RuntimeException e) {
            Log.w("DumbTV-Catalog", "could not query " + category + " applications: " + e);
            return;
        }
        if (matches == null) return;
        for (ResolveInfo ri : matches) {
            if (ri == null || ri.activityInfo == null) continue;
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName == null || ai.name == null
                    || ai.packageName.equals(self) || found.containsKey(ai.packageName)) continue;
            try {
                Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setClassName(ai.packageName, ai.name);
                CharSequence label = ri.loadLabel(pm);
                Item it = new Item(ai.packageName,
                        label == null ? ai.packageName : label, launch);
                it.info = ai;
                it.leanback = leanback;
                found.put(ai.packageName, it);
            } catch (RuntimeException e) {
                Log.w("DumbTV-Catalog", "skipping app " + ai.packageName
                        + " because its launcher metadata failed: " + e);
            }
        }
    }

    /**
     * Loads a banner, plus an icon for Afterglow or when there is no banner. A {@code replacement}
     * (an icon picked for the app, or the icon pack's match) is used instead of the app's own
     * art: a wide one fills the tile like a banner, any other is drawn as the icon above the
     * name. Slow: off the UI thread.
     */
    static void art(PackageManager pm, Item it, boolean cornerIcon, Drawable replacement) {
        if (it.info == null) return;
        boolean wide = replacement != null && isWide(replacement);
        try {
            it.banner = wide ? replacement : replacement == null ? it.info.loadBanner(pm) : null;
        } catch (RuntimeException e) {
            it.banner = null;
            Log.w("DumbTV-Catalog", "banner resource failed for " + it.id + ": " + e);
        }
        if (replacement != null && !wide) {
            it.icon = replacement;
        } else if (it.banner == null || cornerIcon) {
            try {
                it.icon = it.info.loadIcon(pm);
            } catch (RuntimeException e) {
                it.icon = null;
                Log.w("DumbTV-Catalog", "icon resource failed for " + it.id + ": " + e);
            }
        } else {
            it.icon = null;
        }
    }

    /** Loads an optional user-selected bundled banner for one app. Slow: off the UI thread. */
    static Drawable customBanner(Context context, Item item) {
        String filename = Config.get(context).get("appstyle." + item.id + ".banner", null);
        if (filename == null || !filename.matches("[A-Za-z0-9_.-]+\\.png")) return null;
        try (java.io.InputStream bounds = context.getAssets().open("custom-banners/" + filename)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(bounds, null, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (options.outWidth > 0 && options.outHeight > 0) {
                long width = Math.max(1, options.outWidth / options.inSampleSize);
                long height = Math.max(1, options.outHeight / options.inSampleSize);
                if (width * height <= 4_000_000L && Math.max(width, height) <= 4096) break;
                if (options.inSampleSize >= (1 << 20)) break;
                options.inSampleSize *= 2;
            }
            Bitmap bitmap;
            try (java.io.InputStream image = context.getAssets().open("custom-banners/" + filename)) {
                bitmap = BitmapFactory.decodeStream(image, null, options);
            }
            if (bitmap == null) return null;
            Drawable artwork = new BitmapDrawable(context.getResources(), bitmap).mutate();
            String tint = Config.get(context).get("appstyle." + item.id + ".bannerColor", "original");
            if ("accent".equals(tint)) artwork.setTint(Config.get(context).accent());
            else if (tint.matches("#[0-9A-Fa-f]{6}")) artwork.setTint(Config.parseColor(tint, Config.get(context).accent()));
            return artwork;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** TV banners are 16:9; anything clearly wider than tall is drawn across the whole tile. */
    static boolean isWide(Drawable d) {
        return d.getIntrinsicWidth() * 3 > d.getIntrinsicHeight() * 4;
    }

    /**
     * HDMI, AV and other passthrough inputs; HDMI first in port order. The intent is the TV's own
     * app for the input; {@link Input#intent} decides whether a tile opens that or the built-in
     * screen.
     */
    static List<Item> inputs(Context c, TvInputManager tv) {
        List<Item> out = new ArrayList<>();
        if (tv == null) return out;
        List<Item> other = new ArrayList<>();
        for (TvInputInfo in : tv.getTvInputList()) {
            if (!in.isPassthroughInput() || in.isHidden(c)) continue;
            CharSequence label = in.loadCustomLabel(c);
            if (TextUtils.isEmpty(label)) label = in.loadLabel(c);
            Intent view = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(in.getId()));
            Item it = new Item(in.getId(), label, view);
            it.state = tv.getInputState(in.getId());
            (in.getType() == TvInputInfo.TYPE_HDMI ? out : other).add(it);
        }
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        Collections.sort(other, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        out.addAll(other);
        return out;
    }

    static CharSequence label(Config cfg, Item it) {
        return cfg.get("name." + it.id, it.label.toString());
    }

    static boolean shown(Config cfg, String kind, Item it) {
        if (cfg.list(kind + ".hide").contains(it.id)) return false;
        return !APPS.equals(kind) || it.leanback || cfg.list("apps.show").contains(it.id);
    }

    static void setShown(Config cfg, String kind, Item it, boolean on) {
        List<String> hide = cfg.list(kind + ".hide");
        hide.remove(it.id);
        if (!on && (it.leanback || !APPS.equals(kind))) hide.add(it.id);
        cfg.setList(kind + ".hide", hide);
        if (APPS.equals(kind) && !it.leanback) {
            List<String> show = cfg.list("apps.show");
            show.remove(it.id);
            if (on) show.add(it.id);
            cfg.setList("apps.show", show);
        }
    }

    /** The shown items, in the user's order; anything never moved keeps catalog order. */
    static List<Item> visible(Config cfg, String kind, List<Item> all) {
        return visible(cfg, kind, all, null);
    }

    /** As above, then newest first by {@code recent} (package to last-used time) when it is set. */
    static List<Item> visible(Config cfg, String kind, List<Item> all, Map<String, Long> recent) {
        // Pinning, hiding and recent use only affect the pinned section. Child lock keeps apps
        // hidden there out of reach on this screen. Sort the full list by its displayed names.
        if (ALL_APPS.equals(kind)) {
            // The home-screen catalogue row has been removed; All Apps is a separate Activity.
            return new ArrayList<>();
        }
        List<String> order = cfg.list(kind + ".order");
        List<Item> out = new ArrayList<>();
        for (Item it : all) {
            if (shown(cfg, kind, it)) out.add(it);
        }
        Collections.sort(out, (a, b) -> Integer.compare(rank(order, a.id), rank(order, b.id)));
        // The sort is stable, so apps with no record keep the user's order after the rest.
        if (recent != null) Collections.sort(out, (a, b) -> Recent.compare(recent, a.id, b.id));
        return out;
    }

    private static int rank(List<String> order, String id) {
        int i = order.indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /**
     * Extracts the logical favourites cycle from the duplicated scrolling strip. The carousel
     * renders three copies for seamless wraparound; only the middle copy is user-editable order.
     */
    static List<String> logicalCycle(List<String> rendered, int start, int count) {
        if (rendered == null || start < 0 || count < 0 || start > rendered.size() - count) {
            throw new IllegalArgumentException("invalid carousel cycle");
        }
        return new ArrayList<>(rendered.subList(start, start + count));
    }

    /** Saves {@code ids} as the new order, keeping the old positions of hidden items after them. */
    static void saveOrder(Config cfg, String kind, List<String> ids) {
        List<String> order = new ArrayList<>(ids);
        for (String id : cfg.list(kind + ".order")) {
            if (!order.contains(id)) order.add(id);
        }
        cfg.setList(kind + ".order", order);
    }
}
