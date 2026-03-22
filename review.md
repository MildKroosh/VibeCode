# Code Review — AccController

## Executive Summary

```
PROJECT: AccController (Acceleration Controller Mobile App)
HEALTH: Needs Work
FINDINGS: 14 total — P0: 2 | P1: 4 | P2: 5 | P3: 3

TOP 3 ISSUES:
1. [F-01] Hardcoded network credentials (IP & port) in production code — security risk
2. [F-02] Unhandled exceptions silently swallowed in send loop — data loss risk
3. [F-03] Race condition between steering angle updates and network sends — inconsistent state
```

---

## Project Overview

| Field | Value |
|---|---|
| **Path** | /Users/parth/Documents/Krushant/VibeCoded/VibeCode |
| **Tech Stack** | Kotlin, Android SDK 26-34, Jetpack AndroidX, Coroutines |
| **Framework(s)** | Android (AppCompat), Kotlin Coroutines |
| **File Count** | 4 source files (MainActivity, ControllerView), 2 test templates |
| **Lines of Code** | ~474 (main source code) |
| **Architecture** | Single-Activity + Custom View (no separation of concerns) |
| **Project Stage** | MVP / Prototype |
| **Docs Found** | None (no README, ARCHITECTURE, or docs) |
| **Linting/CI Enforced** | None detected |

---

## Scoring

| Dimension | Score | Weight | Notes |
|---|---|---|---|
| Architecture & Structure | 4/10 | 1.0x | Monolithic activity with mixed concerns; no separation between UI and business logic |
| Code Quality | 5/10 | **1.5x** | Decent Kotlin usage but hardcoded values, poor error handling, inconsistent patterns |
| Security | 3/10 | **1.5x** | Critical issues: hardcoded credentials, unhandled exceptions, no input validation |
| Performance | 6/10 | 1.0x | Reasonable sensor processing; some inefficiencies in drawing/redraws |
| Error Handling | 2/10 | 1.0x | Exceptions silently caught and ignored; no logging; no retry logic |
| Dependencies & Config | 7/10 | 0.5x | Good: Android Plugin 9.0.1, Kotlin 2.0.21; minimal surface area |
| Testing | 1/10 | 1.0x | Only boilerplate example tests; zero actual test coverage |
| Framework Best Practices | 4/10 | 1.0x | Lifecycle not fully leveraged; memory leak risk; no proper resource management |
| **Weighted Overall** | **3.8/10** | | **Critical issues must be addressed** |

---

## What Is Done Well

- **Sensor Integration**: Gyroscope sampling and auto-centering logic with appropriate deadzone and sensitivity controls (`MainActivity:106-126`).
- **Touch Multi-pointer Handling**: ControllerView correctly tracks multiple touch pointers for independent brake/throttle control (`ControllerView:250-299`).
- **Visual Feedback**: Rich, responsive UI with gradient overlays, real-time percentage displays, and connection status indicators.
- **Kotlin Idioms**: Good use of `.coerceIn()`, `?.let`, coroutine patterns, and data classes (`PedalTracker`, enum `PedalZone`).
- **Coroutine Usage**: Proper use of `CoroutineScope` and `Dispatchers.IO` for network operations off the main thread.

---

## All Findings

| ID | Priority | Category | Finding | Location | Fix |
|---|---|---|---|---|---|
| F-01 | P0 Critical | Security | Hardcoded server IP and port in production code | `MainActivity:38` | Extract to `BuildConfig` or secure config |
| F-02 | P0 Critical | Error Handling | Empty catch block swallows exceptions; no logging or retry | `MainActivity:160` | Add structured logging + exponential backoff retry |
| F-03 | P1 Important | Concurrency | Race condition: steering angle written on main thread, read on IO thread without sync | `MainActivity:23-112, 159` | Use `AtomicReference<Float>` |
| F-04 | P1 Important | Architecture | Business logic mixed with Android lifecycle and UI | `MainActivity:17-164` | Extract network, sensor, and control logic to separate classes |
| F-05 | P1 Important | Memory Leaks | `coroutineScope` created without lifecycle binding; may leak if activity destroyed mid-operation | `MainActivity:131, 153` | Use `viewModelScope` or bind `Job` cancellation to `onDestroy` |
| F-06 | P1 Important | Security | No validation of outgoing network protocol; no version or checksum | `MainActivity:159` | Add protocol version + checksum + error handling |
| F-07 | P2 Moderate | Code Quality | Hardcoded color codes scattered and duplicated throughout `ControllerView` | `ControllerView:36-64, 89-98` | Extract to companion object constants or theme resources |
| F-08 | P2 Moderate | Code Quality | Magic numbers (sensitivity array, deadzone, timestep) not documented | `MainActivity:25-26, 112` | Extract to named constants with explanatory comments |
| F-09 | P2 Moderate | Testing | Only boilerplate tests; zero coverage of sensor handling, network protocol, or touch input | `test/`, `androidTest/` | Add unit tests for sensitivity calculation and touch input |
| F-10 | P2 Moderate | Architecture | Socket resources not properly initialized before use | `MainActivity:35-36, 139` | Use proper initialization pattern with nullable safe calls |
| F-11 | P2 Moderate | Error Handling | No handling of network disconnects or partial writes; `PrintWriter` not flushed | `MainActivity:139-159` | Add `flush()`, check write success, implement reconnect |
| F-12 | P2 Moderate | Code Quality | Sensitivity changes trigger `invalidate()` without debouncing | `MainActivity:67-80` | Debounce or check if value actually changed before redraw |
| F-13 | P3 Nice-to-have | Documentation | No README, architecture doc, or build instructions | Project root | Add README with setup, network protocol spec, architecture overview |
| F-14 | P3 Nice-to-have | Performance | `Paint` objects created on every `onDraw()` call | `ControllerView:114, 150, 184, 187, 211, 219` | Move to class-level or companion object |

---

## Architecture Assessment

### Current Structure

```
/Users/parth/Documents/Krushant/VibeCoded/VibeCode
├── app/
│   ├── src/main/java/com/acccontroller/
│   │   ├── MainActivity.kt       (164 lines)
│   │   └── ControllerView.kt     (310 lines)
│   └── src/main/AndroidManifest.xml
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

### Issues

1. **Monolithic Structure**: All logic in two files; tight coupling between UI rendering and business logic.
2. **No Repository/ViewModel Pattern**: Network, sensor, and control logic are intertwined in `MainActivity`. Testing is nearly impossible.
3. **Framework Lock-in**: Core logic untestable without Android runtime.

### Recommended Structure

```
app/src/main/java/com/acccontroller/
├── MainActivity.kt                 (UI only)
├── ui/
│   └── ControllerView.kt          (rendering only)
├── domain/
│   ├── SensorController.kt        (gyro logic)
│   ├── ControllerState.kt         (state management)
│   └── NetworkProtocol.kt         (protocol definition + validation)
├── data/
│   ├── NetworkRepository.kt       (socket management)
│   └── SensorRepository.kt        (sensor callbacks)
└── utils/
    ├── Constants.kt               (all hardcoded values)
    └── Logging.kt                 (structured logging)
```

---

## Code Examples

### F-03: Fix Race Condition on Steering Angle

**Before** (`MainActivity.kt`):
```kotlin
private var steeringAngle = 0f  // Not thread-safe

// Main thread write:
steeringAngle += rotZ * 0.008f * sensitivity

// IO thread read — RACE:
val steer = String.format("%.4f", steeringAngle)
```

**After**:
```kotlin
private val steeringAngle = AtomicReference(0f)

// Main thread write (atomic):
steeringAngle.updateAndGet { current ->
    val updated = current + rotZ * 0.008f * sensitivity
    if (abs(rotZ) < 0.04f) updated * 0.93f else updated
}.coerceIn(-1f, 1f)

// IO thread read (safe):
val steer = String.format("%.4f", steeringAngle.get())
```

---

### F-01: Extract Hardcoded Credentials

**Before** (`MainActivity.kt`):
```kotlin
private val serverPort = 9999
private val serverHost = "192.168.1.32"
```

**After** (`build.gradle.kts`):
```groovy
buildTypes {
    debug {
        buildConfigField("String", "SERVER_HOST", "\"192.168.1.32\"")
        buildConfigField("int", "SERVER_PORT", "9999")
    }
    release {
        buildConfigField("String", "SERVER_HOST", "\"your-prod-server.com\"")
        buildConfigField("int", "SERVER_PORT", "443")
    }
}
```

**Then in `MainActivity.kt`**:
```kotlin
import com.acccontroller.BuildConfig

// Use BuildConfig.SERVER_HOST and BuildConfig.SERVER_PORT
```

---

### F-02: Structured Error Handling

**Before**:
```kotlin
} catch (e: Exception) { }  // Silent fail
```

**After**:
```kotlin
} catch (e: IOException) {
    Log.e("NetworkSend", "Write failed: ${e.message}", e)
    socket?.close()  // Trigger reconnect
    mainHandler.post { controllerView.setConnected(false) }
} catch (e: Exception) {
    Log.e("NetworkSend", "Unexpected error: ${e.javaClass.simpleName} — ${e.message}", e)
}
```

---

### F-06: Add Protocol Validation

**Before**:
```kotlin
writer?.println("S:$steer,T:$throttle,B:$brake")
```

**After**:
```kotlin
val payload = "V1|S:$steer,T:$throttle,B:$brake"
val checksum = payload.hashCode()
writer?.println("$payload|CRC:$checksum")
writer?.flush()
```

---

### F-08: Extract Magic Numbers

**Before**:
```kotlin
steeringAngle += rotZ * 0.008f * sensitivity
if (abs(rotZ) < 0.04f) steeringAngle *= 0.93f
```

**After**:
```kotlin
private companion object {
    const val SENSOR_TIMESTEP_S = 0.008f       // ~8ms sensor delay
    const val GYRO_CENTER_THRESHOLD = 0.04f    // rad/s; below this, start auto-centering
    const val GYRO_CENTER_DECAY = 0.93f        // exponential decay; 50% damping at ~11 frames
    const val STEERING_DEADZONE = 0.02f
    val SENSITIVITY_LEVELS = floatArrayOf(1.0f, 1.8f, 2.5f, 3.5f, 5.0f)

    const val COLOR_BG = "#0A0C10"
    const val COLOR_GRID = "#0D1520"
    const val COLOR_CONNECTED = "#00CC44"
    const val COLOR_DISCONNECTED = "#CC2200"
}

steeringAngle += rotZ * SENSOR_TIMESTEP_S * sensitivity
if (abs(rotZ) < GYRO_CENTER_THRESHOLD) steeringAngle *= GYRO_CENTER_DECAY
```

---

## Bug Predictions

| # | Predicted Bug | Likelihood | Files at Risk |
|---|---|---|---|
| 1 | Steering jitter under load (race condition) | **High** | `MainActivity:23-112, 159` |
| 2 | App silently stops sending after network error (no reconnect) | **High** | `MainActivity:130-150` |
| 3 | "CONNECTING..." never clears if server unreachable | **Medium** | `MainActivity:145` |
| 4 | Memory leak in `ControllerView` if activity destroyed mid-draw | **Medium** | `ControllerView:66-67` |
| 5 | Server receives garbled message on poor network (no framing) | **Medium** | `MainActivity:159` |
| 6 | Sensitivity level jumps by 2-3 on rapid taps (no debounce) | **Low** | `MainActivity:67-80` |
| 7 | App crashes if gyroscope not available | **Low** | `MainActivity:89-90` |

---

## Quick Wins

Easy fixes, under 5 minutes each:

| # | Finding | Action |
|---|---|---|
| 1 | F-02 | Add `Log.e()` in the empty catch block of `sendJob` |
| 2 | F-07 | Extract `Color.parseColor("#0A0C10")` to `COLOR_BG` constant |
| 3 | F-08 | Add comment on `0.008f` (sensor timestep in seconds) and `0.93f` (auto-center decay) |
| 4 | F-14 | Move `Paint` object in `drawGrid()` to class-level variable |
| 5 | F-12 | Log sensitivity change: `Log.d("Sensitivity", "Changed to $sensitivityLevel")` |

---

## Action Plan

### Immediate (P0 — do now)

1. **[F-01]** Extract `serverHost` and `serverPort` to `BuildConfig`.
2. **[F-02]** Add exception logging in `sendJob` catch block; implement reconnect with backoff.
3. **[F-03]** Replace `steeringAngle` with `AtomicReference<Float>`.

### Short-term (P1 — this sprint)

1. **[F-06]** Add protocol version + checksum to network messages.
2. **[F-04]** Refactor `MainActivity`: extract `SensorRepository` and `NetworkRepository`.
3. **[F-09]** Write unit tests for sensitivity calculation and touch input.
4. **[F-05]** Bind `CoroutineScope` to Activity lifecycle via `viewModelScope`.
5. **[F-11]** Add `flush()` after `println()`; implement disconnect detection.

### Backlog (P2/P3)

1. **[F-07]** Extract all hardcoded colors to resources or theme.
2. **[F-13]** Write README with network protocol spec and architecture overview.
3. **[F-14]** Move `Paint` objects to class level.
4. **[F-10]** Implement proper socket initialization pattern.
5. Add CI with Detekt (Kotlin) and Android Lint; enforce minimum test coverage.
