#!/usr/bin/env python3
"""
Build script for creating Windows executable using PyInstaller.

Usage (on Windows):
    pip install pyinstaller
    python build_windows.py

This creates a standalone PhotoBackupServer.exe in the dist/ folder.
"""

import os
import subprocess
import sys


def build():
    """Build the Windows executable."""

    # PyInstaller command
    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--name", "PhotoBackupServer",
        "--onefile",  # Single executable
        "--console",  # Show console window (useful for seeing API key)
        "--icon", "icon.ico",  # Optional: add icon if exists
        "--add-data", "config.py;.",  # Include config
        "--hidden-import", "uvicorn.logging",
        "--hidden-import", "uvicorn.loops",
        "--hidden-import", "uvicorn.loops.auto",
        "--hidden-import", "uvicorn.protocols",
        "--hidden-import", "uvicorn.protocols.http",
        "--hidden-import", "uvicorn.protocols.http.auto",
        "--hidden-import", "uvicorn.protocols.websockets",
        "--hidden-import", "uvicorn.protocols.websockets.auto",
        "--hidden-import", "uvicorn.lifespan",
        "--hidden-import", "uvicorn.lifespan.on",
        "--hidden-import", "zeroconf",
        "--hidden-import", "zeroconf._utils.ipaddress",
        "--collect-submodules", "uvicorn",
        "--collect-submodules", "zeroconf",
        "main.py"
    ]

    # Remove icon option if file doesn't exist
    if not os.path.exists("icon.ico"):
        cmd = [c for c in cmd if c != "--icon" and c != "icon.ico"]

    print("Building Windows executable...")
    print(f"Command: {' '.join(cmd)}")

    result = subprocess.run(cmd, cwd=os.path.dirname(os.path.abspath(__file__)))

    if result.returncode == 0:
        print("\n" + "=" * 60)
        print("Build successful!")
        print("Executable: dist/PhotoBackupServer.exe")
        print("=" * 60)
    else:
        print("\nBuild failed!")
        sys.exit(1)


if __name__ == "__main__":
    build()
