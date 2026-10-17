@echo off
if not defined FLOW_APP_DIR (echo Set FLOW_APP_DIR to the installed program directory. 1>&2 & exit /b 3)
if not exist "%~dp0classes" mkdir "%~dp0classes"
"%FLOW_APP_DIR%\runtime\java\bin\javac.exe" --release 17 -encoding UTF-8 -d "%~dp0classes" "%~dp0src\demo\Json.java" "%~dp0src\demo\DemoTool.java"
if errorlevel 1 exit /b %errorlevel%
"%FLOW_APP_DIR%\runtime\java\bin\jar.exe" --create --file "%~dp0java-copy-demo.jar" --main-class demo.DemoTool -C "%~dp0classes" .
exit /b %errorlevel%
