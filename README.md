# BoxyBatt ⚡

A comprehensive Android battery monitor that provides real-time metrics, historical charts, per-app battery drain attribution, and hardware usage monitoring.

## Features

### 🔋 Real-Time Dashboard
- Battery level, health, temperature, voltage
- Live current draw (mA) with EWMA damping (10-sample weighted average)
- Power consumption (watts): `P = V × I`
- Estimated time remaining
- Max battery capacity detection

### 📊 History & Charts
- Multi-layer chart overlays: battery level, temperature, current draw, power
- Selectable time ranges with zoom
- Charging region highlighting
- Detailed log entries with all metrics per sample

### 📱 Per-App Battery Usage
- Foreground time (screen-on usage from `UsageStatsManager`)
- Background service time (`totalTimeForegroundServiceUsed`, API 29+)
- **Estimated drain (mAh)**: proportional allocation based on battery level drop × max capacity
- **CPU time**: per-process user+system ticks from `/proc/[pid]/stat`
- **RAM (PSS)**: per-process memory from `ActivityManager.getProcessMemoryInfo()`
- Sortable by: Drain, CPU Time, RAM, Usage %, FG Time, BG Time, A-Z, Recent
- Auto-refresh every 30s to detect newly installed apps

### 🖥️ Hardware Monitoring
- Screen brightness (auto/manual %)
- WiFi state + signal strength
- Bluetooth on/off + paired device count
- GPS/Location mode
- Mobile network type + signal level (0-4)
- CPU usage (with `top` fallback for Samsung/restricted devices)
- RAM usage (total/used/available GB)

### ⚙ Advanced Settings
- Configurable polling interval (1s – 30min) with millisecond precision
- Logarithmic slider for fine low-end control
- Data source probe showing all available battery data methods with live readings
- Multi-source cascade: BatteryManager API → OEM intent extras → sysfs paths

## Architecture

```
com.boxymoron.boxybatt/
├── model/
│   ├── BatteryInfo.kt           # Battery metrics data class
│   └── AppBatteryUsage.kt       # Per-app usage model (drain, CPU, RAM)
├── util/
│   ├── BatteryStatsCollector.kt # Multi-source battery data with normalization
│   ├── BatteryStatsParser.kt    # Per-app CPU time + RAM from /proc and ActivityManager
│   ├── BatteryHistoryStore.kt   # SQLite store for historical battery data
│   ├── AppUsageCollector.kt     # Per-app usage + drain estimation
│   └── HardwareStatsCollector.kt# Hardware component states + power impact
├── service/
│   └── BatteryMonitorService.kt # Foreground service for continuous monitoring
├── adapter/
│   └── AppBatteryAdapter.kt     # RecyclerView adapter for app list
├── view/
│   └── BatteryChartView.kt      # Custom multi-layer chart drawable
├── MainActivity.kt              # Dashboard
├── HistoryActivity.kt           # History charts + log entries
├── AppUsageActivity.kt          # Per-app battery usage list
└── AdvancedSettingsActivity.kt  # Settings + data source probe
```

## Data Collection Strategy

BoxyBatt uses a cascading fallback strategy for maximum cross-device compatibility:

| Data | Primary Source | Fallback 1 | Fallback 2 |
|------|---------------|------------|------------|
| Current (mA) | `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` | `EXTRA_CURRENT` from broadcast | sysfs: `/sys/class/power_supply/*/current_now` |
| Capacity (µAh) | `BATTERY_PROPERTY_CHARGE_COUNTER` | Estimated from level × design capacity | N/A |
| Energy (µWh) | `BATTERY_PROPERTY_ENERGY_COUNTER` | Computed: capacity × voltage | N/A |
| Max Capacity | `BATTERY_PROPERTY_CHARGE_COUNTER` at 100% | `charge_full` sysfs | 4000 mAh fallback |

### Unit Normalization
Samsung devices report current in µA instead of mA. The collector auto-detects this (values > 50,000) and converts.

### Current Damping
Uses a 10-sample EWMA (Exponentially Weighted Moving Average) where newer samples have higher weight, producing stable readings without lag.

## Building

```bash
# Debug build
./gradlew assembleDebug

# Install to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Production build
./gradlew assembleProduction
```

## Requirements

- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 34 (Android 14)
- **Language**: Kotlin
- **JVM Target**: 17

## Permissions

| Permission | Purpose | Required |
|-----------|---------|----------|
| `FOREGROUND_SERVICE` | Background monitoring service | Yes |
| `POST_NOTIFICATIONS` | Service notification | Yes (runtime) |
| `PACKAGE_USAGE_STATS` | Per-app usage data | Yes (settings redirect) |
| `ACCESS_WIFI_STATE` | WiFi hardware status | Yes (auto) |
| `ACCESS_NETWORK_STATE` | Network type detection | Yes (auto) |
| `BLUETOOTH` / `BLUETOOTH_CONNECT` | Bluetooth hardware status | Yes (auto) |
| `ACCESS_FINE_LOCATION` | GPS status, cell signal | Optional (runtime) |
| `BATTERY_STATS` | Enhanced power data | Optional (ADB grant) |

## License

Proprietary — © BoxyMoron
