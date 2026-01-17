# WiFi Photo Backup

Automatically backup photos and videos from your Android phone to your Windows PC over WiFi.

## Features

- **Automatic backup** - Backs up when connected to your home WiFi
- **Zero configuration networking** - Server is discovered automatically via mDNS
- **Duplicate detection** - Only uploads new files (using SHA-256 hashes)
- **Organized storage** - Files organized by date: `Photos/2024/01/filename.jpg`
- **Background sync** - Uses Android WorkManager for reliable background processing
- **Resume support** - Interrupted uploads can be resumed

## Components

### 1. Desktop Server (Python)

A FastAPI server that runs on your Windows PC and receives backups.

**Quick Start:**
```bash
cd backup_server
pip install -r requirements.txt
python main.py
```

Or on Windows, just double-click `run_server.bat`.

The server will display an API key - you'll need this for the Android app.

### 2. Android App (Kotlin)

An Android app that automatically backs up photos and videos.

**Build:**
1. Open `PhotoBackupApp/` in Android Studio
2. Build and install on your device
3. Open Settings, enter the API key from the server
4. Set your home WiFi network name

## How It Works

```
┌─────────────────┐         WiFi          ┌─────────────────┐
│   Android App   │ ───────────────────── │  Python Server  │
│                 │                       │                 │
│ • Detect WiFi   │   mDNS Discovery      │ • Broadcast via │
│ • Scan photos   │ <──────────────────── │   mDNS         │
│ • Upload files  │                       │                 │
│                 │   HTTP POST           │ • Receive files │
│                 │ ────────────────────> │ • Deduplicate   │
│                 │                       │ • Organize      │
└─────────────────┘                       └─────────────────┘
```

1. Server broadcasts its presence via mDNS (`_photobackup._tcp.local.`)
2. Android app discovers server when connected to home WiFi
3. App scans MediaStore for photos/videos
4. Checks with server which files are already backed up
5. Uploads new files with progress notification
6. Server organizes files by date and media type

## Storage Structure

Files are stored on your PC in:
```
storage/
├── Photos/
│   └── 2024/
│       ├── 01/
│       │   └── IMG_20240115.jpg
│       └── 02/
│           └── IMG_20240220.jpg
└── Videos/
    └── 2024/
        └── 01/
            └── VID_20240115.mp4
```

## Requirements

### Server
- Python 3.10+
- Windows, macOS, or Linux

### Android App
- Android 8.0 (API 26) or higher
- Permissions: Location (for WiFi SSID), Media access, Notifications

## Building Windows Installer

To create a standalone Windows executable:

```bash
cd backup_server
pip install pyinstaller
python build_windows.py
```

To create a Windows installer (requires [Inno Setup](https://jrsoftware.org/isinfo.php)):
1. Build the executable first
2. Open `installer.iss` in Inno Setup Compiler
3. Click Compile

## Security

- API key authentication for all requests
- Local network only (no internet required)
- Files verified with SHA-256 checksums

## Configuration

### Server Environment Variables
- `BACKUP_PORT` - Server port (default: 8080)
- `BACKUP_STORAGE_PATH` - Storage directory (default: ./storage)
- `BACKUP_API_KEY` - API key (auto-generated if not set)

### Android App Settings
- Home WiFi network name(s)
- API key from server
- Auto-backup toggle
- Backup photos/videos toggles

## License

MIT License
