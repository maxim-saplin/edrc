# battery_stats

Instant **screen-on endurance**: how many hours of screen-on time a full charge would last, from data Android already has — not by starting a tracker and waiting a week.

Example: Monday had 2h screen-on while 20% drained unplugged → **10h SOT per full battery**. That is the ranking metric (phone models, daily bar).

This repo is an old Flutter PoC (app usage list + 24h level chart). It was never tested. Native code reflects the wrong class (`BatteryUsageStatsManager`; real one is `BatteryStatsManager`) and never hits the power ledger.

## What Android already stores

On a used Oppo (ColorOS 16 / API 36) we confirmed:

- **~10 daily buckets** of 1% discharge/charge steps, with screen-on/off flags, plus extrapolated “hours per 100%” (`dumpsys batterystats --daily`).
- **~24h** of level history in batterystats history.
- **~7–10 days** of screen-on time via `UsageStatsManager.queryEventStats` (`SCREEN_INTERACTIVE`).
- **Since last charge**: on-battery time, screen-on time, mAh (same blob Settings uses).

The endurance number needs **both** screen-on hours and **% dropped while unplugged**. Screen-on alone is wellbeing, not battery life.

## Options

### 1. Play Store, public APIs only

| Source | Instant? | Gives SOT / 100%? |
|---|---|---|
| `BatteryManager` (level, charge counter, current) | Yes | No — snapshot only |
| Usage access (`PACKAGE_USAGE_STATS`) | Yes, ~7–10 days SOT | No — no Δ% |
| `SystemHealthManager.takeMyUidSnapshot()` | Yes, **this charge cycle** | No — times only, not % dropped |

Honest UI: live remaining + last week’s screen-on bar + “this cycle: *n* h screen-on”. Do **not** invent a 10h rating.

`BATTERY_STATS` is `signature\|privileged\|development` on purpose: the ledger is per-app wakelocks, radios, history — not “battery %” (that is already public). Play will not grant it. Stock Battery is Settings/OEM using hidden `BatteryStatsManager`. Digital Wellbeing is the same Usage-stats API, not a back door to Δ%.

Sysfs / vendor paths (`/sys/class/power_supply/…`, Oppo `oplus_chg`, Settings/Oppo content providers): fail-closed probing is fine; on this phone they are SELinux- or signature-blocked. Even when readable they are **now**, not last week’s curve.

### 2. Mine the ledger (the original idea)

Parse `dumpsys batterystats --daily`: 1% steps tagged screen-on → mean minutes per 1% × 100. Hide days with too few steps (one step → nonsense).

Ways to get that dump:

- **Shizuku** (best UX): wireless debugging, no PC after pairing. This phone already has it. Natural as a Shizuku client (F-Droid / IzzyOnDroid / GitHub). Shizuku itself is not on official F-Droid; keep Shizuku **optional** so the app can still list with a fallback.
- **`adb shell pm grant … BATTERY_STATS`** (+ often `DUMP` / `hidden_api_policy`). GSam/BBS path. Oppo: disable permission monitoring. Hidden APIs are shaky on **Android 16**.
- **User shares a bugreport zip** — contains batterystats; heavy privacy; one-shot import.

Root is the same data with less ceremony.

### 3. Sampling (current PoC in this repo)

Foreground service logs `%`, plug, charging, screen-on, and µAh after install. SOT per 100% appears after enough unplugged (or plugged-but-not-charging) screen-on drain (≥3%). ColorOS 80% charge-hold with USB attached is still sampled; charging sessions reset the drain step so they are not stitched into SOT.

Setup requires notifications, unrestricted battery, and ColorOS **autostart**. Do not force-stop the app from Settings / recents — Android will not restart sampling until you open it again. Lock the task in recents if ColorOS offers that.

Shizuku mining can be added later as a second data source.

## Distribution (if we mine)

Shizuku-optional FOSS app: without Shizuku → option 1; with Shizuku → daily SOT-per-100% chart. F-Droid/Izzy + GitHub; Play optional. Audience is BBS/GSam users, not mass Play.

## Device notes (Oppo PKH110, 2026-08)

- No root; `adbd` cannot restart as root.
- Charge often sits ~65–80%, so daily 1% screen-on samples can be thin. The collector keeps logging during that hold (USB “plugged” is not treated as charging unless status is `CHARGING`).
- Recents swipe / force-stop kills the collector until the app is opened again.
- Estimated pack **5600 mAh**; live `%` / charge counter work via `BatteryManager`.
- `takeUidSnapshots(all)` still needs `BATTERY_STATS`; window is since last charge, not 7 days.
