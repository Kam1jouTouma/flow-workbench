@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-demo.ps1"
if errorlevel 1 (
    echo Preparation failed. See the message above.
    pause
    exit /b 1
)
pause
