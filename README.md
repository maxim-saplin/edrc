# edrc

Battery endurance. Screen-on (and idle) hours a full charge would last, from Android’s own timers (`dumpsys batterystats --charged` via Shizuku).

SOT = screen-on hours ÷ (screen-on mAh / pack capacity). Shown only if screen-on discharge is ≥ 3% of the pack.

Example: 3.2 h on-screen while 1498 mAh drained on-screen of a 5600 mAh pack → **~12 h** per full charge.

## What it shows

- **This cycle** — `Screen on`, `Screen on discharge`, `Start clock time`.
- **Last cycle** — only if a dump saw `Start clock time` change. Otherwise **not caught**.
- **Frames** — hourly (and on open) snapshots of those same totals. Interval SOT only when Δ mAh ≥ 100.

No calendar days. ColorOS `--daily` 1% screen tags are not used.

## How it collects

Shizuku UserService (`:dump`, daemon). No app foreground service, no extra notification. One dumpsys of the since-last-charge header, at most every 2 minutes on open, otherwise about once an hour. Frames live in `/data/local/tmp/com.saplin.edrc.frames.jsonl` (shell uid).

Live `%` / charging is public `BatteryManager`.

`Screen on` / idle clocks are **on battery only** — they do not move while plugged in. Wireless pad time is already excluded. Micro top-ups stay in the same cycle until Android resets after a full charge.

## Setup

Shizuku installed, running, permission granted.

## Release

Local (needs `android/key.properties` pointing at `saplin.jks`, alias `edrc`):

```bash
flutter build apk --release
flutter build appbundle --release
```

GitHub: **Actions → Release**. Draft has the APK (sideload) and AAB (Play). Upload the AAB in Play Console for `com.saplin.edrc`.

## Out of scope

Foreground sampling, `pm grant` without Shizuku, Play `BATTERY_STATS`, OEM day extrapolations.
