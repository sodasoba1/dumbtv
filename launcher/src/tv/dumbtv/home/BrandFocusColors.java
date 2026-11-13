package tv.dumbtv.home;

/** Dark, high-contrast brand colours for Brand Focus tiles. */
final class BrandFocusColors {
    private BrandFocusColors() {}

    /** Darken the chosen accent so white app names remain readable, even for white accent. */
    static int fallback(int color) {
        int r = Math.round(((color >> 16) & 255) * 0.42f);
        int g = Math.round(((color >> 8) & 255) * 0.42f);
        int b = Math.round((color & 255) * 0.42f);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int forPackage(String id, String label) {
        if ("Smart Kick TV".equalsIgnoreCase(label.trim())) return 0xFF236B24;
        switch (id) {
            case "org.smarttube.stable": return 0xFF8E2632;
            case "com.amcplus.amcfiretv": return 0xFF087A78;
            case "com.crunchyroll.crunchyroid": return 0xFFB84B00;
            case "com.disney.disneyplus": return 0xFF123C91;
            case "com.hbo.hbonow": return 0xFF49306F;
            case "com.hulu.plus": return 0xFF087A45;
            case "com.netflix.ninja": return 0xFFC8102E;
            case "com.pandora.android.gtv": return 0xFF54358F;
            case "com.cbs.ott": return 0xFF0755A5;
            case "com.peacock.peacockfiretv": return 0xFF63305E;
            case "com.plexapp.android": return 0xFF896000;
            case "com.amazon.firebat": return 0xFF006B8F;
            case "com.starz.starzplay.firetv": return 0xFF343434;
            default: return 0;
        }
    }
}