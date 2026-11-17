# Keeper standby and HOME recovery

## Why this fix was needed

On the target Fire TV Stick 4K (1st generation, AFTMM), Amazon's protected
launcher can become the foreground HOME activity again after the Stick wakes
from standby. Keeper is responsible for returning the existing DumbTV task to
the foreground; it does not replace Amazon's protected HOME component.

The original Keeper recovery path worked during ordinary foreground changes,
but a long suspend exposed a timing assumption:

- Keeper polls Android UsageStats events on a HandlerThread.
- During deep sleep, Android suspends normal process execution, so the 250 ms
  polling callbacks do not run on schedule.
- The old poll queried only a short, fixed recent window (four seconds).
  When the next callback ran after wake, the Amazon HOME foreground event
  could already be older than that window. Keeper then had no positive event
  telling it that Amazon HOME had returned.
- The screen-off path also relied on a previously captured
  `screenWasAmazonHome` snapshot. If that snapshot was stale or had not been
  captured, the screen-on path could decline recovery even though Amazon HOME
  was now foregrounded.

The visible failure was specific: after a sufficiently long standby/deep-sleep
period, the Stick woke into Amazon's launcher but Keeper did not bring DumbTV
back to the foreground. The service could still be running; the problem was
that its foreground detection/recovery trigger had missed the transition.

## What changed

The fix was developed in two related stages:

1. **Wake-recovery hardening (PR #15, commit
   [23916b9](https://github.com/sodasoba1/dumbtv/commit/23916b9b754a88b49cabedcf46a2483fd85af23d)).**
   Keeper now queries UsageStats from the last event it actually consumed,
   rather than relying on a fixed four-second look-back. It refreshes event
   history around screen-off and screen-on, and no longer requires a successful
   screen-off Amazon HOME snapshot before considering wake recovery. It also
   catches UsageStats query failures, verifies recovery, retries boundedly,
   and uses a direct activity-launch fallback when task reordering does not
   succeed.

2. **ActivityManager wake detection (PR #16, commit
   [3410bb4](https://github.com/sodasoba1/dumbtv/commit/3410bb49d1f952697ea742bbb6f900bdc65306b3)).**
   UsageStats and screen broadcasts remain useful signals, but the Fire OS 6
   build also watches ActivityManager logcat for the exact Amazon
   `HomeActivity_vNext` becoming displayed or resumed. This provides a
   second, more immediate observation of the HOME transition rather than
   depending only on UsageStats polling after a long suspend.

The logcat reader is deliberately narrow: it filters ActivityManager records,
matches the exact Amazon HOME activity and a displayed/resumed transition,
ignores launch-request-only records, does not trigger while the display is off,
and debounces duplicate events. It restarts after stream failure with bounded
backoff. It does not save raw system log lines.

## Why the installer grants READ_LOGS

Android protects access to system log buffers. On this Fire OS 6 / Android 7.1.2
target (API 25), the installer uses ADB to grant
`android.permission.READ_LOGS` to `tv.dumbtv.home`. Keeper checks that grant
before starting its ActivityManager reader.

This is why READ_LOGS is not treated as an optional diagnostic permission in
this fork: it enables the additional ActivityManager foreground signal added
specifically to strengthen the deep-sleep HOME recovery fix. The installer
must fail if ADB refuses the grant or if the installed package does not report
`granted=true`. A running Keeper service or a successful UsageStats check
alone does not prove that this additional reader is active.

The grant does not replace UsageStats. Keeper continues to use UsageStats and
screen on/off broadcasts as separate recovery paths; ActivityManager logcat
supplements them.

## Scope and limitations

- The implementation targets AFTMM, Fire OS 6.7.1.1, Android 7.1.2 / API 25.
- Keeper only takes over after a positive observation of Amazon's exact HOME
  activity and only while the display is interactive.
- Amazon's own settings screens are intentionally not treated as a HOME
  takeover target; the user can navigate them without DumbTV immediately
  reclaiming the screen.
- The changes address the missed-event and stale-screen-snapshot failure modes.
  They do not prove that every possible Fire OS firmware state or arbitrarily
  long standby interval is flawless. Real standby/wake testing remains necessary.

## Relevant history

- [PR #15 – Harden Keeper wake recovery on Fire OS 6](https://github.com/sodasoba1/dumbtv/pull/15)
- [PR #16 – Add ActivityManager logcat wake detection](https://github.com/sodasoba1/dumbtv/pull/16)
- [Current Keeper implementation](../launcher/src/tv/dumbtv/home/Keeper.java)
