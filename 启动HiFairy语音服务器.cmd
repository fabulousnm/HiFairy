@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0voice-pc\start-hifairy-server.ps1"
if errorlevel 1 (
    echo.
    echo 启动失败，请检查上方错误信息。
    pause
)
