package android.content;

import java.io.File;

/** Only the app-private directory lookups Config uses in its host tests. */
public abstract class Context {
    public Context getApplicationContext() { return this; }
    public abstract File getExternalFilesDir(String type);
    public abstract File getFilesDir();
}
