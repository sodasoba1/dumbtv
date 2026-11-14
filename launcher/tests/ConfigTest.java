package tv.dumbtv.home;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
        failedWriteCanRecover();
        System.out.println("ConfigTest passed (settings persistence, export, custom colours, legacy values, failed-write recovery)");
    }
}
