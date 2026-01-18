# Photo Backup Android App - Deployment Guide

## Quick Reference

### Build & Install (One Command)

**From Git Bash or WSL:**
```bash
cd /c/Users/armin/Documents/Projects/Claude/photo-backup-app/PhotoBackupApp
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
```

**Install to device:**
```bash
/c/Users/armin/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

### Monitor Logs
```bash
/c/Users/armin/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -s BackupScheduler:* BackupWorker:* WifiConnectivityReceiver:* PhotoBackupApplication:* WifiStateMonitor:* MainViewModel:* ServerDiscovery:* BackupForegroundService:* BackupRepository:* BootReceiver:*
```

---

## 1. Environment Setup

### Required Paths

| Item | Path |
|------|------|
| **JAVA_HOME** | `C:\Program Files\Android\Android Studio\jbr` |
| **ADB** | `C:\Users\armin\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| **Project Root** | `C:\Users\armin\Documents\Projects\Claude\photo-backup-app\PhotoBackupApp` |
| **Debug APK** | `app/build/outputs/apk/debug/app-debug.apk` |
| **App Package** | `com.photobackup` |
| **Main Activity** | `com.photobackup.ui.main.MainActivity` |

### Git Bash / WSL Path Conversion
Windows paths need to be converted for bash:
- `C:\` → `/c/`
- `\` → `/`

Example: `C:\Users\armin` → `/c/Users/armin`

---

## 2. Build Commands

### Git Bash / WSL (Recommended)
```bash
# Set JAVA_HOME (required for Gradle)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

# Navigate to project
cd /c/Users/armin/Documents/Projects/Claude/photo-backup-app/PhotoBackupApp

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean and rebuild
./gradlew clean assembleDebug
```

### Windows CMD (Alternative)
```cmd
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd C:\Users\armin\Documents\Projects\Claude\photo-backup-app\PhotoBackupApp
gradlew.bat assembleDebug
```

### Build Output
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`

---

## 3. ADB Commands

### Full ADB Path (for bash)
```bash
ADB="/c/Users/armin/AppData/Local/Android/Sdk/platform-tools/adb.exe"
```

### Device Management
```bash
# List connected devices
$ADB devices

# Check device authorization
$ADB devices -l

# Kill and restart ADB server (if device not recognized)
$ADB kill-server
$ADB start-server
```

### Installation
```bash
# Install APK (replace existing)
$ADB install -r app/build/outputs/apk/debug/app-debug.apk

# Verify installation
$ADB shell pm list packages | grep photobackup
```

### App Control
```bash
# Launch app
$ADB shell am start -n com.photobackup/.ui.main.MainActivity

# Force stop app
$ADB shell am force-stop com.photobackup

# Clear app data (keeps installed)
$ADB shell pm clear com.photobackup

# Uninstall app
$ADB uninstall com.photobackup
```

---

## 4. Logcat Commands

### App-Specific Log Tags

| Tag | Component | Purpose |
|-----|-----------|---------|
| `PhotoBackupApplication` | App | Application lifecycle, WiFi receiver registration |
| `WifiStateMonitor` | Network | WiFi connection state, SSID detection |
| `WifiConnectivityReceiver` | Receiver | WiFi change events, auto-backup trigger |
| `BootReceiver` | Receiver | Device boot events, schedule restoration |
| `BackupScheduler` | Worker | WorkManager scheduling, foreground service |
| `BackupWorker` | Worker | Backup execution, file processing |
| `BackupForegroundService` | Service | Background service lifecycle |
| `BackupRepository` | Data | File uploads, hash verification |
| `MainViewModel` | UI | UI state, backup orchestration |
| `ServerDiscovery` | Network | mDNS server discovery |

### Filtered Log Commands

```bash
ADB="/c/Users/armin/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# Clear logcat buffer first
$ADB logcat -c

# All app logs (recommended)
$ADB logcat -s BackupScheduler:* BackupWorker:* WifiConnectivityReceiver:* PhotoBackupApplication:* WifiStateMonitor:* MainViewModel:* ServerDiscovery:* BackupForegroundService:* BackupRepository:* BootReceiver:*

# WiFi and connectivity only
$ADB logcat -s WifiStateMonitor:* WifiConnectivityReceiver:*

# Backup worker only
$ADB logcat -s BackupWorker:* BackupRepository:*

# Background service and scheduling
$ADB logcat -s BackupScheduler:* BackupForegroundService:* BootReceiver:*

# Dump recent logs (last 200 lines)
$ADB logcat -d -t 200

# Dump and filter
$ADB logcat -d -s BackupWorker:* BackupRepository:* | tail -50
```

### Save Logs to File
```bash
# Save filtered logs
$ADB logcat -s BackupWorker:* BackupRepository:* > backup_logs.txt

# Continuous logging to file (Ctrl+C to stop)
$ADB logcat -s BackupScheduler:* BackupWorker:* WifiConnectivityReceiver:* > app_logs.txt
```

---

## 5. Complete Build & Deploy Workflow

### One-Time Setup
1. Enable **Developer Options** on Android device (tap Build Number 7 times)
2. Enable **USB Debugging** in Developer Options
3. Connect device via USB
4. Accept "Allow USB debugging?" prompt on device
5. Check "Always allow from this computer"

### Full Build & Deploy (Git Bash)
```bash
# Set environment
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
ADB="/c/Users/armin/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# Navigate to project
cd /c/Users/armin/Documents/Projects/Claude/photo-backup-app/PhotoBackupApp

# Check device connection
$ADB devices

# Build
./gradlew assembleDebug

# Install
$ADB install -r app/build/outputs/apk/debug/app-debug.apk

# Launch app
$ADB shell am start -n com.photobackup/.ui.main.MainActivity

# Monitor logs (in separate terminal or after app interaction)
$ADB logcat -c  # Clear first
$ADB logcat -s BackupScheduler:* BackupWorker:* WifiConnectivityReceiver:* PhotoBackupApplication:* WifiStateMonitor:* MainViewModel:*
```

### Quick Rebuild After Code Changes
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/Users/armin/Documents/Projects/Claude/photo-backup-app/PhotoBackupApp
./gradlew assembleDebug && /c/Users/armin/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 6. Troubleshooting

### Common Issues

**"JAVA_HOME is not set":**
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

**"adb: command not found":**
Use full path: `/c/Users/armin/AppData/Local/Android/Sdk/platform-tools/adb.exe`

**"device unauthorized":**
1. Look at phone for USB debugging authorization dialog
2. Tap "Allow"
3. Check "Always allow from this computer"

**"Directory does not contain a Gradle build":**
Make sure you're in the correct directory:
```bash
cd /c/Users/armin/Documents/Projects/Claude/photo-backup-app/PhotoBackupApp
```

**Build fails with errors:**
```bash
./gradlew clean assembleDebug
```

**Device not showing in `adb devices`:**
```bash
$ADB kill-server
$ADB start-server
$ADB devices
```

### Useful Debug Commands
```bash
# Check app permissions
$ADB shell dumpsys package com.photobackup | grep permission

# Check WorkManager jobs
$ADB shell dumpsys jobscheduler | grep photobackup

# Check if app is running
$ADB shell ps | grep photobackup

# Get app info
$ADB shell dumpsys package com.photobackup | head -50
```

---

## 7. Build Configuration Summary

| Setting | Value |
|---------|-------|
| **Application ID** | `com.photobackup` |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 34 (Android 14) |
| **Compile SDK** | 34 |
| **Kotlin Version** | 1.9.24 |
| **Gradle Version** | 8.13 |
| **AGP Version** | 8.13.2 |
| **Java Version** | 17 |

---

## 8. Key Source Files

```
PhotoBackupApp/app/src/main/kotlin/com/photobackup/
├── PhotoBackupApplication.kt          ← App entry, WiFi receiver registration
├── receiver/
│   ├── BootReceiver.kt                ← Boot completed handler
│   └── WifiConnectivityReceiver.kt    ← WiFi change handler, auto-backup trigger
├── worker/
│   ├── BackupWorker.kt                ← Main backup logic
│   ├── BackupScheduler.kt             ← WorkManager scheduling
│   └── BackupForegroundService.kt     ← Background service
├── network/
│   ├── WifiStateMonitor.kt            ← WiFi SSID detection
│   └── ServerDiscovery.kt             ← mDNS server discovery
├── data/repository/
│   └── BackupRepository.kt            ← File upload, deduplication
└── ui/main/
    └── MainViewModel.kt               ← UI state management
```

---

## 9. Server Log Location

The backup server logs to:
```
C:\Users\armin\Documents\Projects\Claude\photo-backup-app\backup_server\storage\server.log
```

Check server logs with:
```bash
cat /c/Users/armin/Documents/Projects/Claude/photo-backup-app/backup_server/storage/server.log
```

Or in Windows:
```cmd
type C:\Users\armin\Documents\Projects\Claude\photo-backup-app\backup_server\storage\server.log
```
