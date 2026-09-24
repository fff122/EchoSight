@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在启动盲人识物助手...
echo.
buildenv\Scripts\python.exe blind_assistant.py
echo.
pause
