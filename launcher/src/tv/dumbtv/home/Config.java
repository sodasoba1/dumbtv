package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * All settings live in one plain {@code key=value} file in the app's external files dir.
 * On this Fire OS 6 target, {@code adb pull}/{@code adb push} is the whole export/import
 * story and a hand edit is picked up on the next return to the home screen.
 */
final class Config {
    static final String FILE = "home.conf";
    /** Options › Export settings writes this copy beside home.conf, for adb pull. */
    static final String EXPORT = "home-export.conf";

    static final class Choice {
        final String key, title;
        final String[] values, names;
        /** Values retained for reading existing home.conf files; optionValues is the shorter UI list. */
        final String[] optionValues;
        final String fallback;

        Choice(String key, String title, String fallback, String[] values, String[] names) {
            this(key, title, fallback, values, names, values);
        }

        Choice(String key, String title, String fallback, String[] values, String[] names,
                String[] optionValues) {
            this.key = key;
            this.title = title;
            this.fallback = fallback;
            this.values = values;
            this.names = names;
            this.optionValues = optionValues;
        }

        String name(String value) {
            int i = Arrays.asList(values).indexOf(value);
            return i < 0 ? value : names[i];
        }
    }

    static final Choice ACCENT = new Choice("accent", "Accent color", "blue",
            new String[] {"white", "blue", "cyan", "green", "amber", "red", "pink", "violet",
                    "teal", "mint", "lime", "yellow", "orange", "coral", "crimson", "rose",
                    "magenta", "purple", "indigo", "lavender", "sky", "ice", "gold", "copper", "custom"},
            new String[] {"White", "Blue", "Cyan", "Green", "Amber", "Red", "Pink", "Violet",
                    "Teal", "Mint", "Lime", "Yellow", "Orange", "Coral", "Crimson", "Rose",
                    "Magenta", "Purple", "Indigo", "Lavender", "Sky", "Ice", "Gold", "Copper", "Custom colour"},
            new String[] {"white", "blue", "cyan", "green", "amber", "red", "pink", "violet", "custom"});
    static final Choice TILES = new Choice("tiles", "Tile size", "medium",
            new String[] {"small", "medium", "large"},
            new String[] {"Small", "Medium", "Large"});
    static final Choice LAYOUT = new Choice("layout", "Layout", "rows",
            new String[] {"rows", "grid"},
            new String[] {"Rows", "Grid"});
    static final Choice ORDER = new Choice("order", "App order", "custom",
            new String[] {"custom", "recent"},
            new String[] {"Your order", "Recently used"});
    static final Choice ALL_APPS = new Choice("all_apps", "All apps section", "off",
            new String[] {"off", "on"},
            new String[] {"Hidden", "Shown"});
    static final Choice LABELS = new Choice("labels", "App names", "focus",
            new String[] {"focus", "always", "never"},
            new String[] {"On focus", "Always", "Never"});
    static final Choice CLOCK = new Choice("clock", "Clock", "auto",
            new String[] {"auto", "24h", "12h"},
            new String[] {"System", "24-hour", "12-hour"});
    /** Free memory under the date, for TVs that are short of it. Shows only; frees nothing. */
    static final Choice MEMORY = new Choice("memory", "Memory meter", "off",
            new String[] {"off", "on"},
            new String[] {"Hidden", "Shown"});
    static final Choice INPUTS = new Choice("inputs", "Inputs row", "on",
            new String[] {"on", "off"},
            new String[] {"Shown", "Hidden"});
    static final Choice SWITCH = new Choice("switch", "Open inputs with", "auto",
            new String[] {"auto", "tv", "home"},
            new String[] {"Automatic", "The TV's app", "dumbtv home"});
    static final Choice BACKGROUND = new Choice("background", "Background", "black",
            new String[] {"black", "graphite", "midnight", "charcoal", "slate", "navy", "ocean",
                    "forest", "burgundy", "plum", "espresso", "sand", "image", "adaptive", "adaptive-tint", "custom"},
            new String[] {"Black", "Graphite", "Midnight", "Charcoal", "Slate", "Navy", "Ocean",
                    "Forest", "Burgundy", "Plum", "Espresso", "Sand", "Image file", "Adaptive solid",
                    "Adaptive wallpaper tint", "Custom colour"},
            new String[] {"black", "graphite", "midnight", "slate", "image", "adaptive", "adaptive-tint", "custom"});
    static final Choice DIM = new Choice("dim", "Image dim", "40",
            new String[] {"0", "20", "40", "60", "80"},
            new String[] {"0%", "20%", "40%", "60%", "80%"});
    static final Choice GRADIENT = new Choice("gradient", "Gradient overlay", "off",
            new String[] {"off", "bottom", "top", "left", "right",
                    "top-left", "top-right", "bottom-left", "bottom-right"},
            new String[] {"Off", "Dark at bottom", "Dark at top", "Dark at left", "Dark at right",
                    "Dark at top left", "Dark at top right", "Dark at bottom left", "Dark at bottom right"});
    static final Choice GRADIENT_COLOR = new Choice("gradient_color", "Gradient colour", "black",
            new String[] {"black", "white", "blue", "cyan", "green", "amber", "red", "pink", "violet",
                    "teal", "mint", "lime", "yellow", "orange", "coral", "crimson", "rose", "magenta",
                    "purple", "indigo", "lavender", "sky", "ice", "gold", "copper", "custom"},
            new String[] {"Black", "White", "Blue", "Cyan", "Green", "Amber", "Red", "Pink", "Violet",
                    "Teal", "Mint", "Lime", "Yellow", "Orange", "Coral", "Crimson", "Rose", "Magenta",
                    "Purple", "Indigo", "Lavender", "Sky blue", "Ice", "Gold", "Copper", "Custom colour"},
            new String[] {"black", "white", "blue", "red", "custom"});
    static final Choice GRADIENT_STRENGTH = new Choice("gradient_strength", "Gradient strength", "40",
            new String[] {"20", "40", "60", "80"},
            new String[] {"20%", "40%", "60%", "80%"});
    static final Choice FOCUS_SCALE = new Choice("focus_scale", "Focus scale", "normal",
            new String[] {"off", "subtle", "normal", "strong"},
            new String[] {"Off", "Subtle", "Normal", "Strong"});
    static final Choice FOCUS_INDICATOR = new Choice("focus_indicator", "Focus indicator", "accent-ring",
            new String[] {"accent-ring", "white-ring", "accent-glow", "white-glow"},
            new String[] {"Accent ring", "White ring", "Accent glow", "White glow"});
    static final Choice FOCUS_ANIMATION = new Choice("focus_animation", "Focus animation", "smooth",
            new String[] {"immediate", "smooth"},
            new String[] {"Immediate", "Smooth"});
    static final Choice TILE_RADIUS = new Choice("tile_radius", "Tile corners", "soft",
            new String[] {"square", "soft", "rounded"},
            new String[] {"Square", "Soft", "Rounded"});
    static final Choice TILE_SPACING = new Choice("tile_spacing", "Tile spacing", "normal",
            new String[] {"compact", "normal", "spacious"},
            new String[] {"Compact", "Normal", "Spacious"});
    static final Choice TILE_BORDER = new Choice("tile_border", "Tile border", "off",
            new String[] {"off", "subtle", "accent"},
            new String[] {"Off", "Subtle", "Accent"});
    static final Choice TILE_BORDER_WIDTH = new Choice("tile_border_width", "Border thickness", "1",
            new String[] {"1", "2", "3"},
            new String[] {"Thin", "Medium", "Thick"});
    static final Choice TILE_SHADOW = new Choice("tile_shadow", "Tile shadow / glow", "off",
            new String[] {"off", "soft", "glow"},
            new String[] {"Off", "Soft shadow", "Focus glow"});
    static final Choice AFTERGLOW_STRENGTH = new Choice("afterglow_strength", "Afterglow strength", "40",
            new String[] {"20", "40", "60", "80"},
            new String[] {"20%", "40%", "60%", "80%"});
    static final Choice AFTERGLOW_ICON = new Choice("afterglow_icon", "Afterglow icon", "grayscale",
            new String[] {"grayscale", "original"},
            new String[] {"Grayscale", "Original"});

    static final Choice ROTATE = new Choice("rotate", "Wallpaper rotation", "off",
            new String[] {"off", "visit", "hourly", "daily", "daytime"},
            new String[] {"Off", "Every return home", "Every hour", "Every day", "By time of day"});

    /**
     * For a TV children use: hides Options and Settings and turns off the tile menu, so the home
     * screen only opens apps and inputs. Holding Menu for {@link Home#UNLOCK_MS} opens Options.
     */
    static final Choice LOCK = new Choice("lock", "Child lock", "off",
            new String[] {"off", "on"},
            new String[] {"Off", "On"});

    static final Choice TILE_STYLE = new Choice("tile_style", "App tile style", "original",
            new String[] {"original", "afterglow", "brand-focus"},
            new String[] {"Original", "Afterglow", "Brand Focus"});
    /** Options and Settings in the corner: text chips, or icon buttons whose name shows on focus. */
    static final Choice CHIPS = new Choice("chips", "Chip style", "text",
            new String[] {"text", "icons"}, new String[] {"Text", "Icons"});
    /** The banner of hand-picked titles from {@link Library}; hidden anyway while it has none. */
    static final Choice LIBRARY = new Choice("library", "Library banner", "on",
            new String[] {"on", "off"}, new String[] {"Shown", "Hidden"});
    static final Choice[] CHOICES = {ACCENT, TILES, LAYOUT, ORDER, ALL_APPS, LABELS, CLOCK, MEMORY, INPUTS, SWITCH, BACKGROUND, DIM, GRADIENT, GRADIENT_COLOR, GRADIENT_STRENGTH, ROTATE, LOCK, TILE_STYLE, FOCUS_SCALE, FOCUS_INDICATOR, FOCUS_ANIMATION, TILE_RADIUS, TILE_SPACING, TILE_BORDER, TILE_BORDER_WIDTH, TILE_SHADOW, AFTERGLOW_STRENGTH, AFTERGLOW_ICON, CHIPS, LIBRARY};

    /** Folder of wallpapers, next to home.conf, that Background › Image file rotates through. */
    static final String WALLPAPERS = "wallpapers";
    /** Folder and list of {@link Library}, next to home.conf like the wallpapers. */
    static final String LIBRARY_DIR = "library", LIBRARY_FILE = "library.conf";
    private static final String OFFSET = "wallpaper.offset";

    private static final int[] ACCENTS = {
        0xFFFFFFFF, 0xFF3D8BFF, 0xFF2BC4E8, 0xFF34C759, 0xFFFFB340, 0xFFFF5147, 0xFFFF5C9D, 0xFFA07CFF,
        0xFF26A69A, 0xFF58D6B0, 0xFF9CCC35, 0xFFDCE64A, 0xFFAE5A22, 0xFFFF765E, 0xFFC62848, 0xFFFE7AA5,
        0xFFFD36C7, 0xFF9C4DCC, 0xFF5C6BC0, 0xFFC5A3FF, 0xFF64B5F6, 0xFF80DEEA, 0xFFFFD166, 0xFFB87333,
    };
    private static final int[] BACKGROUNDS = {0xFF000000, 0xFF141518, 0xFF0B1220, 0xFF202124, 0xFF263238,
            0xFF101B2D, 0xFF102A43, 0xFF14291F, 0xFF321B24, 0xFF281B35, 0xFF29221D, 0xFF292722,
            0xFF000000, 0xFF141518, 0xFF141518};

    private static Config instance;

    final File dir;
    private final File file;
    private final Map<String, String> values = new LinkedHashMap<>();
    private long signature = Long.MIN_VALUE;
    private int version;
    private long visits;

    static Config get(Context c) {
        if (instance == null) instance = new Config(c.getApplicationContext());
        return instance;
    }

    private Config(Context c) {
        File external = c.getExternalFilesDir(null);
        dir = external != null ? external : c.getFilesDir();
        file = new File(dir, FILE);
        refresh();
    }

    /** Changes every time a setting changes, from the UI or from the file on disk. */
    int version() {
        return version;
    }

    void refresh() {
        long sig = signature();
        if (sig == signature) return;
        signature = sig;
        values.clear();
        if (file.isFile()) read();
        version++;
    }

    /** Both files count: a settings file pushed into the media folder has to be noticed too. */
    private long signature() {
        return file.lastModified() * 31 + file.length();
    }

    private void read() {
        values.putAll(parse(file));
    }

    private static Map<String, String> parse(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq <= 0) continue;
                out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            Log.w("dumbtv", "could not read " + f, e);
        }
        return out;
    }

    private void save() {
        signature = writeSettings(file, values) ? signature() : Long.MIN_VALUE;
    }

    /** Writes a separately named copy beside home.conf for adb pull and later restore. */
    File export() {
        File copy = new File(dir, EXPORT);
        return writeSettings(copy, values) ? copy : null;
    }

    private static boolean writeSettings(File target, Map<String, String> settings) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("# dumbtv home. Edit on the TV (Options) or here; unknown keys are kept.\n");
            for (Map.Entry<String, String> e : settings.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        } catch (IOException e) {
            Log.w("dumbtv", "could not write " + tmp, e);
            return false;
        }
        if (tmp.renameTo(target)) return true;
        Log.w("dumbtv", "could not replace " + target);
        return false;
    }

    String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    String get(Choice c) {
        String v = values.get(c.key);
        return v != null && Arrays.asList(c.values).contains(v) ? v : c.fallback;
    }

    void set(String key, String value) {
        if (value == null || value.isEmpty()) values.remove(key);
        else values.put(key, value);
        version++;
        save();
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : get(key, "").split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    void setList(String key, List<String> items) {
        // String.join is Android 8+; TextUtils.join keeps Fire OS 6 (Android 7.1) working.
        set(key, TextUtils.join(",", items));
    }

    /** Every key=value with this prefix in one string, to notice when any of them changes. */
    String joined(String prefix) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().startsWith(prefix)) out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return out.toString();
    }

    void removePrefix(String... prefixes) {
        for (Iterator<String> it = values.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            for (String p : prefixes) {
                if (k.startsWith(p)) {
                    it.remove();
                    break;
                }
            }
        }
        version++;
        save();
    }

    int accent() {
        String v = values.get(ACCENT.key);
        if (v != null && v.startsWith("#")) return parseColor(v, ACCENTS[1]);
        if ("custom".equals(get(ACCENT))) return parseColor(get("accent_custom", ""), ACCENTS[1]);
        return ACCENTS[Arrays.asList(ACCENT.values).indexOf(get(ACCENT))];
    }

    int background() {
        if ("custom".equals(get(BACKGROUND))) return parseColor(get("background_custom", ""), BACKGROUNDS[0]);
        return BACKGROUNDS[Arrays.asList(BACKGROUND.values).indexOf(get(BACKGROUND))];
    }

    int gradientColor() {
        if ("custom".equals(get(GRADIENT_COLOR))) return parseColor(get("gradient_color_custom", ""), 0xFF000000);
        return paletteColor(get(GRADIENT_COLOR));
    }

    static int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value) | 0xFF000000;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static int paletteColor(String value) {
        if ("black".equals(value)) return 0xFF000000;
        int index = Arrays.asList(ACCENT.values).indexOf(value);
        return index < 0 ? 0xFF000000 : ACCENTS[index];
    }

    /** Counts returns to the home screen, for the "visit" rotation. */
    void visited() {
        visits++;
    }

    /** Steps the rotation forward by one, so the next wallpaper shows now. */
    void nextWallpaper() {
        set(OFFSET, String.valueOf(offset() + 1));
    }

    private int offset() {
        try {
            return Integer.parseInt(get(OFFSET, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 0 morning (6-13 h), 1 afternoon (13-20 h), 2 night. */
    static int period() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 13 ? 0 : h >= 13 && h < 20 ? 1 : 2;
    }

    private static final String[][] PERIOD_WORDS = {
        {"morning", "day"},
        {"afternoon", "evening"},
        {"night", "midnight"},
    };

    /**
     * Files whose name carries a word for {@code period}; empty when others are tagged but not
     * this one, null when no file is tagged at all (then the period just indexes the pool).
     */
    private static List<File> forPeriod(List<File> pool, int period) {
        List<File> fit = new ArrayList<>();
        boolean tagged = false;
        for (File f : pool) {
            // Whole words only, so "sunday.jpg" or "holiday.jpg" is not a morning picture.
            List<String> words = Arrays.asList(f.getName().toLowerCase(Locale.ROOT).split("[^a-z]+"));
            for (int p = 0; p < PERIOD_WORDS.length; p++) {
                for (String w : PERIOD_WORDS[p]) {
                    if (words.contains(w)) {
                        tagged = true;
                        if (p == period && !fit.contains(f)) fit.add(f);
                    }
                }
            }
        }
        return tagged ? fit : null;
    }

    List<File> wallpapers() {
        List<File> out = new ArrayList<>();
        addPictures(new File(dir, WALLPAPERS), out);
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? byName : a.getPath().compareTo(b.getPath());
            }
        });
        return out;
    }

    /** The library folder that holds library.conf, beside home.conf. */
    File libraryDir() {
        File own = new File(dir, LIBRARY_DIR);
        return new File(own, LIBRARY_FILE).isFile() ? own : null;
    }

    private static void addPictures(File folder, List<File> out) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".webp"))) {
                out.add(f);
            }
        }
    }

    File wallpaper() {
        List<File> pool = wallpapers();
        if (!pool.isEmpty()) {
            long now = System.currentTimeMillis();
            long step;
            String mode = get(ROTATE);
            if ("daytime".equals(mode)) {
                int period = period();
                List<File> fit = forPeriod(pool, period);
                if (fit == null) {
                    // Keep Options › Next wallpaper working here too.
                    int n = pool.size();
                    return pool.get(((period + offset()) % n + n) % n);
                }
                if (!fit.isEmpty()) pool = fit;
                mode = "visit";
            }
            switch (mode) {
                case "visit": step = visits; break;
                case "hourly": step = now / 3_600_000L; break;
                case "daily": step = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L; break;
                default: step = 0;
            }
            int n = pool.size();
            return pool.get((int) (((step + offset()) % n + n) % n));
        }
        for (String name : new String[] {"wallpaper.jpg", "wallpaper.jpeg", "wallpaper.png", "wallpaper.webp"}) {
            File f = new File(dir, name);
            if (f.isFile()) return f;
        }
        return null;
    }
}
