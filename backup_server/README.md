# Photo Backup Server

A local server for backing up photos and videos from Android devices over WiFi.

## Quick Start (Windows)

### Option 1: Run from Source (Easiest)

1. **Install Python** (if not already installed)
   - Download from https://python.org
   - During installation, check "Add Python to PATH"

2. **Run the server**
   - Double-click `run_server.bat`
   - The server will start and display the API key

3. **Configure the Android app**
   - Open the Photo Backup app on your phone
   - Go to Settings
   - Enter the API key shown in the server console

### Option 2: Build Standalone Executable

1. **Install PyInstaller**
   ```cmd
   pip install pyinstaller
   ```

2. **Build the executable**
   ```cmd
   python build_windows.py
   ```

3. **Run the executable**
   - The executable will be at `dist/PhotoBackupServer.exe`
   - Double-click to run

### Option 3: Create Windows Installer

1. **Build the executable first** (Option 2)

2. **Install Inno Setup**
   - Download from https://jrsoftware.org/isinfo.php

3. **Compile the installer**
   - Open `installer.iss` with Inno Setup
   - Click Compile
   - Installer will be at `installer_output/PhotoBackupServerSetup.exe`

## How It Works

1. The server broadcasts its presence on the local network using mDNS
2. The Android app discovers the server automatically
3. Photos and videos are uploaded over WiFi to your computer
4. Files are organized by date: `storage/Photos/2024/01/filename.jpg`

## Configuration

Environment variables (optional):
- `BACKUP_PORT` - Server port (default: 8080)
- `BACKUP_STORAGE_PATH` - Where to store backups (default: ./storage)
- `BACKUP_API_KEY` - API key (auto-generated if not set)

## Firewall

The server needs to be accessible on your local network. When running for the first time:
- Windows Firewall may ask to allow the connection - click "Allow"
- If using the installer, firewall rules are added automatically

## Storage Location

By default, files are stored in:
```
./storage/
├── Photos/
│   └── 2024/
│       └── 01/
│           └── IMG_20240115.jpg
└── Videos/
    └── 2024/
        └── 01/
            └── VID_20240115.mp4
```

## API Endpoints

- `GET /api/health` - Health check
- `GET /api/status` - Server status and storage info
- `POST /api/files/check` - Check which files exist (by hash)
- `POST /api/files/upload` - Upload a file
- `GET /api/files/stats` - Backup statistics

## Security

- All requests require an API key in the `X-API-Key` header
- The API key is displayed when the server starts
- Communication is local network only (no internet required)

## Troubleshooting

**Server not discovered by app:**
- Ensure both devices are on the same WiFi network
- Check Windows Firewall allows the connection
- Try entering the server IP manually in the app settings

**Uploads failing:**
- Check the API key is entered correctly in the app
- Ensure there's enough disk space
- Check server console for error messages

**Permission issues:**
- Run the server as administrator if accessing protected folders
