# HomeSentinel: Fire OS 6 HOME monitoring and recovery

## Purpose and naming

HomeSentinel is DumbTV's Fire OS 6 service for detecting when Amazon's protected
HOME activity has returned to the foreground and restoring the existing DumbTV
home task. It does not replace or disable Amazon's HOME handler.

The service was originally named **Keeper**. The name changed because its role
has grown well beyond a simple foreground watcher: it now combines event-history
tracking, a second ActivityManager signal, screen-state handling, explicit
pause/resume rules, task restoration, verification and bounded retry logic.
The rename is descriptive; it does not change the service's runtime behaviour,
package name, permissions, or launcher identity.

## How it differs from the original Keeper

The first version was comparatively simple:

- Poll UsageStats foreground events on a HandlerThread.
- Look back over a short, fixed recent time window.
- When Amazon Home was observed, bring DumbTV Home forward.

That approach worked during ordinary foreground changes but had a timing flaw
after deep standby. Android can suspend normal process execution, so a 250 ms
Handler callback is not guaranteed to run every 250 ms while the TV sleeps.
If the next poll queried only the previous four seconds, the Amazon HOME event
could already have fallen outside that window. The earlier screen-off snapshot
could also be stale or missing, preventing the screen-on path from recognising
that Amazon Home had returned.

HomeSentinel retains the original purpose but has evolved in several deliberate
ways:

1. **Cursor-based UsageStats history.** It queries from the timestamp of the
   last event actually consumed, rather than relying on a short rolling window.
   This lets the next poll process events that accumulated while the process
   was suspended.
2. **Screen-off and screen-on event refresh.** It refreshes recent event history
   before recording screen-off and again after screen-on. Recovery does not
   depend on a successful earlier snapshot of Amazon Home.
3. **ActivityManager logcat detection.** It watches a narrow stream of
   ActivityManager records for the exact Amazon Home activity becoming
   displayed or resumed. This provides a second, more immediate signal when
   UsageStats delivery is delayed.
4. **Positive-event and foreground classification.** It acts only after
   positively observing Amazon's exact HOME activity. Amazon settings screens,
   System UI, DumbTV itself and other apps are treated differently so that
   takeover does not fight normal navigation.
5. **Verified, bounded task recovery.** It first tries to move the existing
   DumbTV task to the foreground, verifies the result, and uses a direct
   activity-launch fallback with a small retry limit if necessary.
6. **Reader supervision and duplicate suppression.** The logcat reader restarts
   after failure with bounded backoff; duplicate displayed/resumed records are
   debounced.

These changes address observed missed-event and stale-snapshot failure modes.
They do not prove that every Fire OS firmware state or arbitrarily long standby
interval is flawless.

## What HomeSentinel monitors

### 1. UsageStats foreground events

The service polls `UsageStatsManager.queryEvents()` every 250 ms on its
dedicated worker thread. It consumes only `MOVE_TO_FOREGROUND` events and
tracks the latest positively observed package and activity class.

UsageStats is an event history, not a direct query of the currently visible
window. The `UsageEventCursor` deduplicates events and preserves the last
consumed timestamp. Empty query results do not cause the service to guess which
app is foreground.

The package and class are used to distinguish:

- Amazon's exact HOME activity:
  `com.amazon.tv.launcher/.ui.HomeActivity_vNext`
- Other screens in Amazon's launcher package, including its settings UI.
- DumbTV Home.
- Android System UI and the `android` system package.
- Other foreground applications.

### 2. ActivityManager logcat

When `READ_LOGS` is granted, a dedicated reader starts
`/system/bin/logcat -T 1 -v brief -s ActivityManager:I`. It begins at the live
edge so old buffered activity transitions cannot be mistaken for a new HOME
event.

It matches only records containing both Amazon's launcher package and
`HomeActivity_vNext`, and then requires a displayed/resumed indication:
`Displayed`, `Resumed` or `topResumedActivity`. A `START` request alone is
not proof that the activity became visible and is ignored.

The logcat path:

- Checks that the display is interactive before starting recovery.
- Debounces duplicate records for three seconds.
- Does not persist raw ActivityManager lines.
- Restarts a failed reader with bounded backoff.
- Uses a watchdog to restart a reader thread that has died; an idle stream is
  not itself treated as a failure.

UsageStats remains active as an independent path. Logcat supplements it; it
does not replace it.

### 3. Screen state

A dynamic receiver observes `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON`.

- On screen-off, HomeSentinel refreshes recent UsageStats history and records
  the screen-off timestamp.
- On screen-on, it refreshes history from around that timestamp (or a recent
  fallback window if no snapshot was recorded).
- If the latest positive event identifies Amazon's exact HOME activity,
  recovery begins. Otherwise any pending recovery is cancelled.

The logcat path separately checks `PowerManager.isInteractive()`, so a log
record while the display is off cannot trigger an activity launch and
unintentionally wake the TV.

## How recovery works

1. **Start and lifecycle.** `BootReceiver` handles locked boot, normal boot and
   package replacement. It waits until the user is unlocked before starting
   HomeSentinel. The service runs in the foreground and uses a dedicated
   `HandlerThread` to serialise monitoring and recovery state.
2. **Eligibility.** Recovery starts only when the latest positively observed
   foreground activity is Amazon's exact HOME activity and the service is not
   paused.
3. **Existing-task restoration.** It enumerates this app's `AppTask` entries
   and calls `moveToFront()` only on a task whose top or base activity belongs
   to `tv.dumbtv.home`. It never reorders an unrelated app task.
4. **Fallback launch.** If task reordering does not succeed, or on later
   attempts, it requests the DumbTV `Home` activity directly with
   `NEW_TASK | CLEAR_TOP | SINGLE_TOP`.
5. **Verification and retry.** After 800 ms it refreshes foreground events. If
   DumbTV is not foreground and Amazon HOME is still the observed target, it
   retries after 650 ms, up to three attempts. It stops immediately when the
   target changes, the service is paused, DumbTV is foreground, or attempts
   are exhausted.
6. **First start.** If the service starts and there is no existing DumbTV task,
   it requests the Home activity so the launcher can be created.

### Manual pause and resume

Opening Amazon's own Home/settings interface pauses takeover. The pause remains
while Amazon launcher/settings, notification centre, device control settings,
DumbTV, System UI or an unknown foreground state is observed. It is cleared
when a genuine third-party app becomes foregrounded, allowing normal HOME
recovery to resume later.

This prevents HomeSentinel from immediately reclaiming the display while the
user is deliberately navigating Amazon settings.

## Permissions and platform integration

The launcher is deliberately restricted to the AFTMM Fire TV Stick 4K
(1st generation), Fire OS 6.7.1.1 / Android 7.1.2 / API 25.

- **UsageStats access** lets the service read foreground event history.
- **READ_LOGS** lets it read the filtered ActivityManager signal. The installer
  grants this through ADB and verifies `granted=true`; installation fails if
  the grant cannot be made or verified. The Java service can detect a missing
  grant, but a correctly configured installation is expected to have it.
- **Fire OS AUTO_START** is requested and verified by the installer where
  supported so the service can restart after boot.
- **Doze whitelist** setup reduces the risk of background restrictions
  interfering with the service.
- **RECEIVE_BOOT_COMPLETED** starts the service after boot or app replacement.
- **REORDER_TASKS** is declared for existing-task restoration.

The service does not require root, an accessibility service, or an overlay.
Amazon's protected HOME activity remains installed and remains the system HOME
handler.

## AlarmManager: what it does and does not do

There is a small `AlarmManager.setExact(AlarmManager.RTC, ...)` fallback in
`raiseHome()`. It is reached only if the direct `startActivity()` request
throws an exception.

This is **not** the standby detection or wake-recovery mechanism. It uses
`RTC`, not `RTC_WAKEUP`, so it does not wake a sleeping device. The actual
standby recovery paths are the screen-on event refresh and UsageStats history;
ActivityManager logcat is the fast signal while the display is interactive.
The alarm fallback is retained as a narrow API 25 launch fallback, not relied
upon to wake the Fire TV.

## Diagnostics

HomeSentinel writes concise status messages to Android logcat under the
existing `dumbtvtest` tag. If the optional marker file exists at
`/sdcard/Android/data/tv.dumbtv.home/files/debug`, it also writes a bounded
diagnostic log to `homesentinel.log` in that directory. Raw ActivityManager
records are not copied into this file.

The diagnostics screen reports whether the service is running and whether
required access paths are available; it does not change service state.

## Limitations and validation

- This implementation is intentionally API 25 / AFTMM-specific. Do not assume
  the same logcat format, permissions or background behaviour on Fire OS 7/8
  or other Android TV devices.
- UsageStats event delivery and Fire OS process scheduling remain platform
  dependencies. The event cursor reduces missed history; it cannot manufacture
  events the OS does not provide.
- The logcat reader can fail or be denied access; the installer therefore
  treats the ADB READ_LOGS grant as required for the intended configuration.
- The service does not reboot the TV, force the display on, replace Amazon's
  HOME handler, or guarantee recovery from every possible firmware failure.
- Physical standby/wake testing is still required, especially after long
  overnight standby intervals and firmware updates.

## Change history

- [PR #15 – Harden Keeper wake recovery on Fire OS 6](https://github.com/sodasoba1/dumbtv/pull/15)
- [PR #16 – Add ActivityManager logcat wake detection](https://github.com/sodasoba1/dumbtv/pull/16)
- [HomeSentinel source](../launcher/src/tv/dumbtv/home/HomeSentinel.java)
