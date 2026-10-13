@echo off
REM Builds neo-voxy for one loader and copies the jar into the local Minecraft
REM client mods folder and the matching local test server (dev\server-<loader>\mods).
REM
REM Usage: scripts\deploy-local.bat [neoforge]
REM Override the client mods folder with the CLIENT_MODS environment variable.
setlocal

set LOADER=%~1
if "%LOADER%"=="" set LOADER=neoforge

set ROOT=%~dp0..
set LIBS=%ROOT%\%LOADER%\build\libs
if "%CLIENT_MODS%"=="" set CLIENT_MODS=%APPDATA%\.minecraft\mods
set SERVER_MODS=%ROOT%\dev\server-%LOADER%\mods

if not exist "%ROOT%\%LOADER%\build.gradle" (
    echo Unknown loader: %LOADER%
    exit /b 1
)

echo === Building %LOADER% ===
call "%ROOT%\gradlew.bat" -p "%ROOT%" :%LOADER%:build
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

REM Pick the newest release jar (build/libs keeps older versions around):
REM sorted oldest first, the last match wins; -sources / -slim / -dev variants skipped.
set JAR=
for /f "delims=" %%F in ('dir /b /o:d "%LIBS%\neo-voxy-%LOADER%-*.jar" 2^>nul') do (
    echo %%~nF | findstr /r /c:"-sources *$" /c:"-slim *$" /c:"-dev *$" /c:"-dev-shadow *$" >nul || set JAR=%LIBS%\%%F
)
if "%JAR%"=="" (
    echo No release jar found in %LIBS%
    exit /b 1
)

echo.
echo === Deploying %JAR% ===
call :deploy "%CLIENT_MODS%" || exit /b 1
if exist "%SERVER_MODS%" (
    call :deploy "%SERVER_MODS%" || exit /b 1
) else (
    echo Skipping test server: %SERVER_MODS% not found
)
endlocal
exit /b 0

:deploy
REM Remove previous neo-voxy jars (any loader, any version) so two never end up side by side.
del /q "%~1\neo-voxy-*.jar" 2>nul
copy /Y "%JAR%" "%~1\" >nul || (echo Copy to %~1 failed & exit /b 1)
echo   %~1
exit /b 0
