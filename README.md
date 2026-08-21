# edrc

How long a full charge would last, from Android’s own on-battery timers (`dumpsys batterystats --charged` via Shizuku).

Screen-on hours ÷ (screen-on mAh / pack). Shown if on-battery screen-on drain is ≥ 3% of the pack. Tap the big number for the same thing while idle (screen off).

Example: 3.2 h screen on, 1498 mAh of a 5600 mAh pack → **~12 h** per full charge.

## What it shows

- **Live %** and charging / unplugged.
- **Hours per full charge** — screen on (or idle). Need more drain until the 3% gate.
- **This cycle** — hours actually used on battery since Android’s last full-charge reset. Those clocks do not move while plugged in.
- **PREVIOUS** — earlier cycles, only if a dump saw `Start clock time` change.
- **RECENT** — interval estimates, only when a stretch drained ≥ 100 mAh.

No calendar days.

## How it collects

Two processes, one APK. No extra binary, no Shizuku cron, no app foreground service, no notification.

1. **`com.saplin.edrc`** — Flutter UI, normal app uid. Reads live `%` / charging from public `BatteryManager`. Cannot run `dumpsys`.
2. **`com.saplin.edrc:dump`** — `DumpService` (a Kotlin class in this APK). Shizuku starts it with `app_process` as uid **shell**, so it can run the system `dumpsys batterystats --charged`.

**Start.** First open with Shizuku granted, the UI calls `bindUserService`. Shizuku forks `:dump` and constructs `DumpService`. `.daemon(true)` keeps that process after the UI unbinds or is swipe-killed.

**While `:dump` is alive.** On start it takes one header dump, then an in-process `Handler` delay (~1 h) and repeats. Opening the app also asks it over AIDL (`collectFrame`); skipped if the last dump was < 2 min ago (10 s if you tap Refresh). Each dump appends a line to `/data/local/tmp/com.saplin.edrc.frames.jsonl` (shell uid). The UI only reads that process / file.

**Stop.** `:dump` dies if Shizuku is stopped, the phone reboots, or the OEM kills that process. Nothing is registered to start it again until you open edrc. Minimizing or killing the UI does not stop it.

Wireless pad time is already excluded from the on-battery clocks. Micro top-ups stay in the same cycle until Android resets after a full charge.

## Setup

Shizuku installed, running, permission granted.

## Release

Local (`android/key.properties` → `saplin.jks`, alias `edrc`):

```bash
flutter build apk --release
flutter build appbundle --release
```

GitHub: **Actions → Release**. Draft has the APK (sideload) and AAB (Play). Upload the AAB in Play Console for `com.saplin.edrc`.

## Out of scope

Foreground sampling, `pm grant` without Shizuku, Play `BATTERY_STATS`, OEM day extrapolations.
