package tv.dumbtv.home;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/** Fire OS 6 settings persistence, export and failed-write checks; no TV required. */
public final class ConfigTest {
    private ConfigTest() {}

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static Config open(final File dir) throws Exception {
        Context context = new Context() {
            @Override public File getExternalFilesDir(String type) { return dir; }
            @Override public File getFilesDir() { return dir; }
        };
        Constructor<Config> ctor = Config.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        return ctor.newInstance(context);
    }

    private static final class Fixture implements AutoCloseable {
        final File root = Files.createTempDirectory("dumbtv-config-").toFile();
        final File dir = new File(root, "files");
        final File settings = new File(dir, Config.FILE);

        Fixture() throws Exception {
            check(dir.mkdir(), "create settings folder");
            write(settings, "accent=blue\nunknown=keep me\n");
        }

        Config open() throws Exception { return ConfigTest.open(dir); }

        @Override public void close() { remove(root); }

        private static void remove(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) remove(child);
            check(file.delete(), "remove fixture " + file.getName());
        }
    }

    private static void settingsReadWriteAndExport() throws Exception {
        try (Fixture f = new Fixture()) {
            Config cfg = f.open();
            check("blue".equals(cfg.get(Config.ACCENT)), "read persisted accent");
            check("keep me".equals(cfg.get("unknown", "")), "unknown keys retained");
            cfg.set("accent", "green");
            Config reopened = f.open();
            check("green".equals(reopened.get(Config.ACCENT)), "setting survives restart");
            check("keep me".equals(reopened.get("unknown", "")), "unknown setting survives write");
            File exported = reopened.export();
            check(exported != null && exported.isFile(), "export copy written");
            check(Config.EXPORT.equals(exported.getName()), "export has separate filename");
            String copy = new String(Files.readAllBytes(exported.toPath()), StandardCharsets.UTF_8);
            check(copy.contains("accent=green"), "export contains current values");
            check(copy.contains("unknown=keep me"), "export retains unknown values");
            check("green".equals(reopened.get(Config.ACCENT)), "export does not alter live settings");
        }
    }

    private static void customColoursIndependent() throws Exception {
        try (Fixture f = new Fixture()) {
            Config cfg = f.open();
            cfg.set("accent", "custom");
            cfg.set("accent_custom", "#123456");
            cfg.set("background", "custom");
            cfg.set("background_custom", "#234567");
            cfg.set("gradient_color", "custom");
            cfg.set("gradient_color_custom", "#345678");
            check(cfg.accent() == 0xFF123456, "custom accent is independent");
            check(cfg.background() == 0xFF234567, "custom solid background is independent");
            check(cfg.gradientColor() == 0xFF345678, "custom gradient colour is independent");
        }
    }

    private static void legacyValuesRemainReadable() throws Exception {
        try (Fixture f = new Fixture()) {
            Config cfg = f.open();
            cfg.set("accent", "copper");
            cfg.set("background", "navy");
            cfg.set("gradient_color", "copper");
            check("copper".equals(cfg.get(Config.ACCENT)), "legacy accent value remains valid");
            check(cfg.accent() == 0xFFB87333, "legacy accent colour still resolves");
            check(cfg.background() == 0xFF101B2D, "legacy background colour still resolves");
            check(cfg.gradientColor() == 0xFFB87333, "legacy gradient colour still resolves");
            check(!java.util.Arrays.asList(Config.ACCENT.optionValues).contains("copper"),
                    "legacy accent is hidden from the shortened picker");
        }
    }

    private static void themeAndTileSizePresets() throws Exception {
        try (Fixture f = new Fixture()) {
            Config cfg = f.open();
            check("classic".equals(cfg.get(Config.THEME)), "classic theme is the backward-compatible default");
            cfg.set(Config.THEME.key, "glass");
            check("glass".equals(f.open().get(Config.THEME)), "Glass theme persists");
            cfg.set(Config.THEME.key, "not-a-theme");
            check("classic".equals(f.open().get(Config.THEME)), "invalid theme falls back safely");
            cfg.set(Config.TILES.key, "extra-large");
            check("extra-large".equals(f.open().get(Config.TILES)), "extra-large tile preset persists");
        }
    }

    private static void favouritesAlignmentDefaultsAndPersists() throws Exception {
        try (Fixture f = new Fixture()) {
            Config cfg = f.open();
            check(java.util.Arrays.asList(Config.CHOICES).contains(Config.FAVOURITES_ALIGN),
                    "favourites alignment is registered with the settings choices");
            check("center".equals(cfg.get(Config.FAVOURITES_ALIGN)),
                    "favourites alignment defaults to centre");
            cfg.set(Config.FAVOURITES_ALIGN.key, "left");
            check("left".equals(f.open().get(Config.FAVOURITES_ALIGN)),
                    "left favourites alignment persists");
            cfg.set(Config.FAVOURITES_ALIGN.key, "right");
            check("right".equals(f.open().get(Config.FAVOURITES_ALIGN)),
                    "right favourites alignment persists");
            cfg.set(Config.FAVOURITES_ALIGN.key, "invalid");
            check("center".equals(f.open().get(Config.FAVOURITES_ALIGN)),
                    "invalid favourites alignment falls back to centre");
        }
    }

    private static void toolbarAutoHideDefaultsAndPersists() throws Exception {
        try (Fixture f = new Fixture()) {
            Config cfg = f.open();
            check(java.util.Arrays.asList(Config.CHOICES).contains(Config.TOOLBAR_AUTO_HIDE),
                    "top bar auto-hide is registered with Options choices");
            check("off".equals(cfg.get(Config.TOOLBAR_AUTO_HIDE)),
                    "top bar auto-hide defaults to off");
            check(java.util.Arrays.asList(Config.CHOICES).contains(Config.WIDGET_BACKGROUNDS),
                    "widget backgrounds are registered with Options choices");
            check("off".equals(cfg.get(Config.WIDGET_BACKGROUNDS)),
                    "widget backgrounds default to off");
            cfg.set(Config.TOOLBAR_AUTO_HIDE.key, "on");
            check("on".equals(f.open().get(Config.TOOLBAR_AUTO_HIDE)),
                    "top bar auto-hide On persists");
            cfg.set(Config.TOOLBAR_AUTO_HIDE.key, "off");
            check("off".equals(f.open().get(Config.TOOLBAR_AUTO_HIDE)),
                    "top bar auto-hide Off persists");
        }
    }

    private static void customTextRoundTrips() throws Exception {
        try (Fixture f = new Fixture()) {
            Config cfg = f.open();
            cfg.setText("widgets.singleline", "Readable text");
            check("Readable text".equals(cfg.getText("widgets.singleline", "")),
                    "single-line custom text remains readable");
            String expected = "First line\nSecond=line\\path\n雪";
            cfg.setText("widgets.text", expected);
            String stored = new String(Files.readAllBytes(f.settings.toPath()), StandardCharsets.UTF_8);
            check(stored.contains("widgets.text=!dumbtv-text-v1!"),
                    "multiline widget text is stored on one settings line");
            Config reopened = f.open();
            check(expected.equals(reopened.getText("widgets.text", "Custom text")),
                    "multiline text, equals, backslash and Unicode survive restart");
            check("Readable text".equals(reopened.getText("widgets.singleline", "")),
                    "single-line text survives restart without encoding");
            check("legacy text".equals(Config.decodeText("legacy text")),
                    "legacy plain-text values remain readable");
            reopened.setText("widgets.text", "");
            check("Custom text".equals(reopened.getText("widgets.text", "Custom text")),
                    "empty custom text keeps the normal fallback");
        }
    }

    private static void concurrentSettingsAccess() throws Exception {
        try (Fixture f = new Fixture()) {
            final Config cfg = f.open();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < 30; i++) cfg.set("race.value", String.valueOf(i));
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            Runnable reader = () -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        cfg.get("race.value", "");
                        cfg.get(Config.ACCENT);
                        cfg.joined("race.");
                        cfg.list("apps.order");
                        cfg.accent();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            };
            Thread firstReader = new Thread(reader);
            Thread secondReader = new Thread(reader);
            writer.start();
            firstReader.start();
            secondReader.start();
            writer.join();
            firstReader.join();
            secondReader.join();
            check(failure.get() == null, "concurrent settings reads and writes remain safe: " + failure.get());
            check("29".equals(cfg.get("race.value", "")), "concurrent writer completes its final setting");
        }
    }

    private static void failedWriteCanRecover() throws Exception {
        try (Fixture f = new Fixture()) {
            File blocked = new File(f.dir, Config.FILE + ".tmp");
            check(blocked.mkdir(), "block settings write");
            Config cfg = f.open();
            cfg.set("accent", "green");
            cfg.refresh();
            check("blue".equals(cfg.get(Config.ACCENT)), "failed write does not replace persisted value");
            check(blocked.delete(), "unblock settings write");
            cfg.set("accent", "red");
            check("red".equals(f.open().get(Config.ACCENT)), "write succeeds after storage recovers");
        }
    }

    public static void main(String[] args) throws Exception {
        settingsReadWriteAndExport();
        customColoursIndependent();
        legacyValuesRemainReadable();
        themeAndTileSizePresets();
        favouritesAlignmentDefaultsAndPersists();
        toolbarAutoHideDefaultsAndPersists();
        customTextRoundTrips();
        concurrentSettingsAccess();
        failedWriteCanRecover();
        System.out.println("ConfigTest passed (settings persistence, export, custom colours, legacy values, top bar auto-hide, failed-write recovery)");
    }
}
