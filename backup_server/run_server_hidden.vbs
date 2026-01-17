Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "C:\Users\armin\Documents\Projects\Claude\photo-backup-app\backup_server"
WshShell.Run "cmd /c ""C:\Users\armin\Documents\Projects\Claude\photo-backup-app\backup_server\run_server.bat""", 0, False
