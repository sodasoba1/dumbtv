package tv.dumbtv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

/** Starts the home HomeSentinel after a reboot or app update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // LOCKED_BOOT_COMPLETED can arrive before the user is unlocked on an encrypted TV. The
        // HomeSentinel is not direct-boot-aware, so leave that boot to BOOT_COMPLETED.
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (users != null && !users.isUserUnlocked()) return;
        // Undo leaves the app installed but deliberately disables automatic takeover.
        // Do not start a persistent foreground service on every boot in that state.
        if (!HomeSentinel.takeoverEnabled(context)) return;
        HomeSentinel.start(context);
    }
}
