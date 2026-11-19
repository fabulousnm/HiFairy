@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0voice-pc\start-hifairy-server.ps1"
echo.
pause
