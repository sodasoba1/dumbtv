package tv.dumbtv.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.Map;

/** One artwork pipeline for the home screen and All Apps. Runs on their serial IO executors. */
final class AppArtwork {
    private AppArtwork() {}

    static void load(Context context, PackageManager pm, Item item, boolean cornerIcon,
                     Map<String, IconPack> packs, String packName, String chosen) {
        Drawable replacement = null;
        if (!IconPack.OWN.equals(chosen)) {
            IconPack pack;
            if (packs.containsKey(packName)) {
                pack = packs.get(packName);
            } else {
                pack = IconPack.load(pm, packName);
                packs.put(packName, pack);
                if (pack != null) HomeSentinel.note("icon pack " + packName + ": " + pack.size() + " apps");
            }
            if (pack != null) {
                // A manually picked drawable is valid only for the pack it came from.
                String picked = IconMatch.picked(chosen, packName);
                Drawable selected = picked == null ? null : pack.picture(picked);
                replacement = selected != null ? selected : pack.drawable(item);
            }
        }

        Catalog.art(pm, item, cornerIcon, replacement);
        Drawable custom = Catalog.customBanner(context, item);
        if (custom != null) {
            item.banner = custom;
            item.icon = null;
        }
    }
}
