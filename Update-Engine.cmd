@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Update-NativeEngine.ps1" %*
exit /b %errorlevel%
