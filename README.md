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

Shizuku UserService (`:dump`, daemon). No app foreground service, no extra notification. Header dump on open (at most every 2 minutes) and about once an hour. Frames: `/data/local/tmp/com.saplin.edrc.frames.jsonl` (shell uid).

Live % / charging is public `BatteryManager`. Wireless pad time is already excluded from the on-battery clocks. Micro top-ups stay in the same cycle until Android resets after a full charge.

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
