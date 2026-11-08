@echo off
rem Builds the addons, starts a real Minecraft client and plays through the in-game tests.
rem A game window opens by itself and closes at the end. Leave it alone while it runs.
rem Only some scenarios:  set INGAMETEST_ONLY=hotbar  and then run this file.
setlocal
set "HERE=%~dp0"
set "ROOT=%HERE%..\.."
for /d %%D in ("%ROOT%\tools\jdk-25*") do set "JAVA_HOME=%%~fD"
if not exist "%ROOT%\tmp" mkdir "%ROOT%\tmp"
set "TMP=%ROOT%\tmp"
set "TEMP=%ROOT%\tmp"
set "JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=%ROOT%\tmp"

for /f "tokens=1,* delims==" %%A in ('findstr /b "addons=" "%HERE%gradle.properties"') do set "ADDONS=%%B"
if not exist "%HERE%libs" mkdir "%HERE%libs"
copy /y "%HERE%..\loadchest\libs\baritone-api-fabric-*.jar" "%HERE%libs\" >nul
if errorlevel 1 (
    echo Put baritone-api-fabric-1.18.0.jar into mc-26.1.1\loadchest\libs first.
    exit /b 1
)

for %%M in (%ADDONS%) do (
    echo === Building %%M
    if not exist "%HERE%..\%%M\libs\baritone-api-fabric-1.18.0.jar" copy /y "%HERE%libs\baritone-api-fabric-*.jar" "%HERE%..\%%M\libs\" >nul
    pushd "%HERE%..\%%M"
    call gradlew.bat clean build
    if errorlevel 1 (
        popd
        echo Building %%M failed.
        exit /b 1
    )
    popd
)

echo === Running the in-game tests
pushd "%HERE%"
call gradlew.bat runClientGameTest
set "RESULT=%errorlevel%"
popd
echo.
type "%HERE%build\run\clientGameTest\ingametest-report.txt" 2>nul
exit /b %RESULT%
