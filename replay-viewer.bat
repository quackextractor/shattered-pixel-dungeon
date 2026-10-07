@echo off
rem ---------------------------------------------------------------------------
rem Plays, records, lists and verifies replay recordings.
rem
rem   replay-viewer                    list the recordings and play one by number
rem   replay-viewer <file>             play a specific recording
rem   replay-viewer --list             list the available recordings and stop
rem   replay-viewer --record <seed>    record a new episode into replays\
rem   replay-viewer --verify <file>    check a recording still reproduces, headless
rem
rem With no argument it lists every recording, grouped by hero class and ranked
rem by score, and asks for a number. A name is not accepted here: the numbers are
rem what the listing shows, so a number is what can be typed back, and one way to
rem select a recording is one that cannot be wrong in a new way.
rem
rem The grouping, ranking and number-to-file mapping live in ReplayCatalog rather
rem than here. Windows sort.exe on this machine rejects /n as an invalid switch,
rem and the scores are floating point and can be negative, so sorting them in
rem batch would be lexical and therefore wrong. It also cannot group by a field
rem parsed out of each file. Duplicating a comparator in a language with no way
rem to test it is the same mistake twice.
rem
rem Everything goes through gradle rather than a hand-built classpath. Paths are
rem made absolute first, because the gradle "run" task uses the module directory
rem as its working directory, so a relative path silently resolves somewhere else.
rem
rem Nothing here kills a java process. The gradle daemon is a java process too,
rem and killing it leaves stale locks that hang the next build.
rem
rem Batch file, so CRLF line endings are required. With LF only, "call :label"
rem fails in ways that look like a missing label.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "REPLAYDIR=%CD%\replays"
set "TRAINDIR=%TEMP%\spd-train\replays"
set "GRADLE=%CD%\gradlew.bat"

if /i "%~1"=="--list"   goto :list
if /i "%~1"=="--record" goto :record
if /i "%~1"=="--verify" goto :verify

if "%~1"=="" goto :pick
call :resolve "%~1" || exit /b 1
goto :play

rem --- list ------------------------------------------------------------------

:list
call :catalog || exit /b 1
echo.
echo Play one with:  replay-viewer ^<file^>
exit /b 0

rem --- pick ------------------------------------------------------------------

:pick
call :catalog || exit /b 1
echo.
set /p "CHOICE=Play which? Enter its number: "

if "%CHOICE%"=="" (
    echo Nothing selected.
    exit /b 1
)

rem The test is inverted from what it looks like: with the digits as delimiters, an
rem all-digit string has no token left, so the loop body never runs. A token
rem appearing therefore means the input contained a non-digit.
set "ISNONNUM="
for /f "delims=0123456789" %%a in ("%CHOICE%") do set "ISNONNUM=yes"
if defined ISNONNUM (
    echo "%CHOICE%" is not one of the numbers listed above.
    exit /b 1
)

call :bynumber %CHOICE% || exit /b 1
goto :play

rem Resolves a number from the listing onto a file. The java side owns the order
rem and answers the query, rather than this file keeping a second copy of the
rem same sort - which would look correct and then drift the first time the
rem comparator changed, so the number a person was looking at would quietly stop
rem matching the recording under it.
:bynumber
set "TARGET=%~1"
set "SELECTFILE=%TEMP%\spd-replay-select.txt"

rem Through a file rather than a `for /f` backtick loop. Inside one of those the
rem quotes around --args="..." do not survive and gradle is handed a broken
rem argument list, which fails with a bare "FAILURE" and no explanation. The path
rem is read back with set /p, which takes the first line and nothing else.
call "%GRADLE%" --offline --no-daemon -q :superintelligence:replays --args="--dir %REPLAYDIR% --dir %TRAINDIR% --select %TARGET%" > "%SELECTFILE%" 2>nul
if errorlevel 1 (
    echo No recording numbered %TARGET%.
    del "%SELECTFILE%" >nul 2>&1
    exit /b 1
)

set "FILE="
set /p FILE=<"%SELECTFILE%"
del "%SELECTFILE%" >nul 2>&1

rem the answer is a bare path on one line; anything else means the query failed
if not defined FILE (
    echo No recording numbered %TARGET%.
    exit /b 1
)
if not exist "!FILE!" (
    echo Not found: !FILE!
    exit /b 1
)
exit /b 0

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

rem Prints the catalog.
:catalog
call "%GRADLE%" --offline --no-daemon -q :superintelligence:replays --args="--dir %REPLAYDIR% --dir %TRAINDIR%"
exit /b %errorlevel%

rem Accepts a bare recording name as well as a path, and always leaves %FILE%
rem absolute so the gradle tasks, whose working directory is the module rather
rem than the repository root, cannot miss it.
rem
rem This is for arguments typed on the command line, not for the interactive
rem picker, which takes a number and asks the java side to resolve it. Both
rem directories are searched because the trainer writes into a temp directory
rem while the checked-in fixtures live in the repo.
:resolve
set "FILE=%~1"
if exist "%FILE%" goto :absolute
if exist "%REPLAYDIR%\%~1" (
    set "FILE=%REPLAYDIR%\%~1"
    goto :absolute
)
if exist "%TRAINDIR%\%~1" (
    set "FILE=%TRAINDIR%\%~1"
    goto :absolute
)

rem the name without an extension, against both directories
set "STEM=%~1"
if /i "%STEM:~-7%"==".replay" set "STEM=%STEM:~0,-7%"
if /i "%STEM:~-4%"==".dat" set "STEM=%STEM:~0,-4%"

if defined STEM if exist "%REPLAYDIR%\%STEM%.replay" set "FILE=%REPLAYDIR%\%STEM%.replay"
if defined STEM if exist "%REPLAYDIR%\%STEM%.dat" set "FILE=%REPLAYDIR%\%STEM%.dat"
if defined STEM if exist "%TRAINDIR%\%STEM%.replay" set "FILE=%TRAINDIR%\%STEM%.replay"
if defined STEM if exist "%TRAINDIR%\%STEM%.dat" set "FILE=%TRAINDIR%\%STEM%.dat"
if defined FILE goto :absolute

echo Not found: %~1
echo Recordings are in %REPLAYDIR% and %TRAINDIR%
exit /b 1

:absolute
for %%I in ("%FILE%") do set "FILE=%%~fI"
if not exist "%FILE%" (
    echo Not found: %~1
    exit /b 1
)
exit /b 0