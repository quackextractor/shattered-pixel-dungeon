@echo off
rem ---------------------------------------------------------------------------
rem Plays, records and verifies replay recordings.
rem
rem   replay-viewer                  play the most recent recording in replays\
rem   replay-viewer <file>           play a specific recording
rem   replay-viewer --list           list the available recordings
rem   replay-viewer --record <seed>  record a new episode into replays\
rem   replay-viewer --verify <file>  check a recording still reproduces, headless
rem
rem Everything goes through gradle rather than a hand-built classpath. Paths are
rem made absolute first, because the gradle "run" task uses the module directory
rem as its working directory, so a relative path silently resolves somewhere else.
rem
rem Nothing here kills a java process. The gradle daemon is a java process too,
rem and killing it leaves stale locks that hang the next build.
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

set "REPLAYDIR=%CD%\replays"
set "GRADLE=%CD%\gradlew.bat"

if /i "%~1"=="--list"   goto :list
if /i "%~1"=="--record" goto :record
if /i "%~1"=="--verify" goto :verify

if "%~1"=="" goto :picknewest
call :resolve "%~1" || exit /b 1
goto :play

rem --- list ------------------------------------------------------------------

:list
if not exist "%REPLAYDIR%\*.replay" goto :norecords
for %%f in ("%REPLAYDIR%\*.replay") do echo   %%~nxf
echo.
echo Play one with:  replay-viewer ^<file^>
exit /b 0

:norecords
echo No recordings in %REPLAYDIR%
echo Record one with:  replay-viewer --record my-seed
exit /b 1

rem --- pick the newest -------------------------------------------------------

:picknewest
if not exist "%REPLAYDIR%\*.replay" goto :norecords
rem No "usebackq" here on purpose. With it, single quotes mean a literal string and
rem backticks mean a command; without it, single quotes run the command. Adding
rem usebackq to this loop made CHOSEN the text "dir /b /o-d ..." instead of a file
rem name, and the run then failed with a nonsense "Not found" path.
set "CHOSEN="
for /f "delims=" %%l in ('dir /b /o-d "%REPLAYDIR%\*.replay" 2^>nul') do (
    if not defined CHOSEN set "CHOSEN=%%l"
)
if not defined CHOSEN goto :norecords
call :resolve "%REPLAYDIR%\%CHOSEN%" || exit /b 1
echo No file given, playing the most recent recording.
goto :play

rem --- record ----------------------------------------------------------------

:record
if "%~2"=="" (
    echo Usage: replay-viewer --record ^<seed^>
    exit /b 1
)
if not exist "%REPLAYDIR%" mkdir "%REPLAYDIR%"
echo Recording seed "%~2" with the scripted policy. Deterministic, so the same
echo seed always produces the same recording.
echo.
call "%GRADLE%" --offline --no-daemon -q :superintelligence:run --args="rollout --seed %~2 --max-turns 1500 --no-color --save %REPLAYDIR%/%~2.replay"
if errorlevel 1 (
    echo.
    echo Recording failed.
    exit /b 1
)
echo.
echo Saved %REPLAYDIR%\%~2.replay
echo Play it with:  replay-viewer %~2.replay
exit /b 0

rem --- verify ----------------------------------------------------------------

:verify
if "%~2"=="" (
    echo Usage: replay-viewer --verify ^<file^>
    exit /b 1
)
call :resolve "%~2" || exit /b 1
echo Verifying %FILE% ...
echo.
call "%GRADLE%" --offline --no-daemon -q :superintelligence:run --args="verify %FILE% --no-color"
exit /b %errorlevel%

rem --- play ------------------------------------------------------------------

:play
echo.
echo   replay  %FILE%
echo   keys    SPACE pause   +/- speed   [ ] finer/coarser   R restart   ESC quit
echo.
echo The [replay] lines report each keypress, which is the quickest way to tell
echo "the key did nothing" apart from "the key did something invisible".
echo.
call "%GRADLE%" --offline --no-daemon :desktop:replay --args="--file %FILE%"
exit /b %errorlevel%

rem --- shared ----------------------------------------------------------------

rem Accepts a bare recording name as well as a path, and always leaves %FILE%
rem absolute so the gradle tasks, whose working directory is the module rather
rem than the repository root, cannot miss it.
:resolve
set "FILE=%~1"
if exist "%FILE%" goto :absolute
if exist "%REPLAYDIR%\%~1" (
    set "FILE=%REPLAYDIR%\%~1"
    goto :absolute
)
echo Not found: %~1
exit /b 1

:absolute
for %%I in ("%FILE%") do set "FILE=%%~fI"
if not exist "%FILE%" (
    echo Not found: %~1
    exit /b 1
)
exit /b 0
