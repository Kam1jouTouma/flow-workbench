@echo off
if not defined FLOW_PYTHON (echo Set FLOW_PYTHON to bundled python.exe before direct BAT use. 1>&2 & exit /b 3)
"%FLOW_PYTHON%" "%~dp0..\Python\copy_files.py" %*
exit /b %errorlevel%
