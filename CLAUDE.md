# CLAUDE.md — BoxyBatt Development Guide

## Project Overview

BoxyBatt is a native Android battery monitoring app written in Kotlin. It provides real-time battery metrics, historical charts, per-app drain attribution, and hardware usage monitoring. The app is designed for cross-device compatibility (Samsung, Pixel, OnePlus, etc.) and works identically whether installed via ADB or the Google Play Store.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Production build (minified, shrunk resources)
./gradlew assembleProduction

# Grant optional enhanced permissions (only needed for testing)
adb shell pm grant com.boxymoron.boxybatt android.permission.BATTERY_STATS
```

## Key Design Decisions

### Multi-Source Data Collection
Battery data varies wildly across OEMs. The collector cascades through:
1. `BatteryManager` property API (official, most reliable)
2. OEM-specific intent extras (Samsung's `EXTRA_CURRENT`, etc.)
3. sysfs files (`/sys/class/power_supply/*/current_now`)

### Unit Normalization
Samsung returns current in µA where Android spec says mA. The `normalizeToMA()` function detects values > 50,000 and divides by 1000. This heuristic is critical — breaking it would show 150000 mA instead of 150 mA on Samsung devices.

### EWMA Damping
Current readings are noisy. A 10-sample exponentially weighted moving average smooths the display without introducing significant lag. The damping factor `alpha = 2 / (N + 1)` gives ~86% weight to the last 10 samples.

### Per-App Drain Estimation
No public API provides per-app drain for third-party apps. Our approach:
1. Measure total device drain from battery level drop × max capacity
2. Query per-app foreground + background service time from `UsageStatsManager`
3. Proportionally allocate drain using weighted time (FG × 1.0, BG × 0.35)

Note: `dumpsys batterystats` is NOT accessible from app process context (requires shell UID), even with `BATTERY_STATS` permission granted via ADB. Do not attempt to use it.

### Per-App CPU Time
Reads `/proc/[pid]/stat` for each process from `ActivityManager.runningAppProcesses()`. Fields 14-15 (utime, stime) give user+system CPU ticks. Convert with `CLK_TCK` (typically 100 Hz on Android).

Caveat: Only shows CPU for currently running processes. Dead processes don't retain data.

### Per-App RAM
Uses `ActivityManager.getProcessMemoryInfo()` which returns PSS (Proportional Set Size) in KB per process. PSS is the most meaningful memory metric as it accounts for shared libraries proportionally.

## Architecture Notes

### File Organization
- `model/` — Data classes (immutable where possible)
- `util/` — Stateless collectors and parsers
- `service/` — Foreground service for continuous monitoring
- `adapter/` — RecyclerView adapters
- `view/` — Custom views (chart)
- Root package — Activities

### Threading Model
- UI updates on `Dispatchers.Main` via `CoroutineScope`
- Data collection on `Dispatchers.IO`
- Service uses configurable polling interval (default 5000ms)
- App usage screen auto-refreshes every 30 seconds

### Database
`BatteryHistoryStore` uses SQLite (`SQLiteOpenHelper`) with a single `battery_log` table. Entries are pruned to keep the last 7 days. Schema:
```sql
CREATE TABLE battery_log (
    _id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER,
    level INTEGER,
    temperature REAL,
    voltage REAL,
    current_now INTEGER,
    is_charging INTEGER,
    charge_counter INTEGER
)
```

## Common Gotchas

1. **Samsung µA vs mA**: Always go through `normalizeToMA()`. Raw BatteryManager values on Samsung are 1000× too large.

2. **Background time**: `totalTimeForegroundServiceUsed` (API 29+) measures foreground service time, not general background activity. Pre-Q returns 0.

3. **`/proc/stat` denied**: Samsung denies access to `/proc/stat` for CPU usage. The `HardwareStatsCollector` falls back to parsing `top -bn1` output.

4. **Permissions after reinstall**: `BATTERY_STATS` permission is revoked on app reinstall. Must be re-granted via ADB.

5. **Charge counter units**: `BATTERY_PROPERTY_CHARGE_COUNTER` returns µAh (microamp-hours), not mAh. Divide by 1000.

6. **Negative current**: Discharging current is negative on most devices but positive on some. Always use `abs()` for display and `normalizeToMA()` handles sign.

## Testing Checklist

- [ ] Dashboard shows all metrics (level, temp, voltage, current, power, energy, capacity)
- [ ] Current draw shows non-zero values (damped)
- [ ] History chart renders with level + current overlay
- [ ] App usage list populates with FG time, BG time, drain, CPU, RAM
- [ ] Sort modes all work (Drain, CPU, RAM, Usage%, FG, BG, A-Z, Recent)
- [ ] Auto-refresh detects newly installed apps
- [ ] Hardware section shows screen, WiFi, BT, GPS, mobile, CPU, RAM
- [ ] Advanced Settings slider and ms input work
- [ ] Data source probe shows available/unavailable sources
- [ ] App works without BATTERY_STATS permission (graceful degradation)
