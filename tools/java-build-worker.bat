@echo off
"%FLOW_JAVAC%" --release %FLOW_BUILD_RELEASE% -encoding UTF-8 -d "%FLOW_BUILD_CLASSES%" @"%FLOW_BUILD_ARGFILE%"
if errorlevel 1 exit /b %errorlevel%
if "%FLOW_BUILD_MAINCLASS%"=="" goto library
"%FLOW_JAR%" "-J-Djava.io.tmpdir=%FLOW_BUILD_TMP%" --create --file "%FLOW_BUILD_OUTPUT%" --main-class "%FLOW_BUILD_MAINCLASS%" -C "%FLOW_BUILD_CLASSES%" .
exit /b %errorlevel%
:library
"%FLOW_JAR%" "-J-Djava.io.tmpdir=%FLOW_BUILD_TMP%" --create --file "%FLOW_BUILD_OUTPUT%" -C "%FLOW_BUILD_CLASSES%" .
exit /b %errorlevel%
