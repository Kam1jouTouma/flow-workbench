@echo off
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0java-build-tool.ps1" %*
exit /b %errorlevel%
