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
rem That form is a loop. Closing the viewer with ESC comes back to the catalog
rem rather than ending the session - the listing was still there, and ending the
rem only because a window closed made watching several recordings one process per
rem recording. Enter or a bad number reprints the list instead of exiting, which
rem is the same complaint about a stray keypress. q leaves.
rem
rem Passing a file is not a loop. That form is what a batch or a script invokes,
rem and it ends when the playback does, so nothing about unattended use changes.
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

rem Options for unattended playback.
rem
rem   --trace              one line per settled step, for diffing against the trainer
rem   --heartbeat          a liveness line every 2s, for debugging the viewer itself
rem   --close              close the window when playback finishes or diverges
rem   --close-on-diverge   close only when it diverges
rem   --fast [n]           play at n times normal speed (default 40)
rem   --mute               no audio
rem   --at X Y             place the window at X,Y - use this for a second display
rem   --windowed           normal window even if the saved preference says maximised
rem
rem A batch that checks many recordings wants: --close --fast --mute --at 1920 0
rem
rem The window cannot be hidden: the renderer needs a GL context, so it always exists. --at moves
rem it off the display being worked on instead.
rem
rem Every flag is recorded twice. SPDJOPTS is the -D form, used when replay runs java directly;
rem SPDFLAGS is the gradle -P form, used only by the fallback. They are built here, while the
rem argument is in hand, rather than re-parsed out of a string later - re-parsing a comma
rem separated value out of %SPDFLAGS% with for/f was silently producing an empty second half.
set "SPDFLAGS="
set "SPDJOPTS="

:parseflags
if "%~1"=="" goto :parsedone
if /i "%~1"=="--trace"            set "SPDFLAGS=%SPDFLAGS% -PspdTrace"            & set "SPDJOPTS=%SPDJOPTS% -Dspd.trace=1"            & shift & goto :parseflags
if /i "%~1"=="--heartbeat"        set "SPDFLAGS=%SPDFLAGS% -PspdHeartbeat"        & set "SPDJOPTS=%SPDJOPTS% -Dspd.heartbeat=1"        & shift & goto :parseflags
if /i "%~1"=="--close"            set "SPDFLAGS=%SPDFLAGS% -PspdAutoClose"         & set "SPDJOPTS=%SPDJOPTS% -Dspd.autoClose=1"         & shift & goto :parseflags
if /i "%~1"=="--close-on-diverge" set "SPDFLAGS=%SPDFLAGS% -PspdAutoCloseOnDiverge" & set "SPDJOPTS=%SPDJOPTS% -Dspd.autoCloseOnDiverge=1" & shift & goto :parseflags
if /i "%~1"=="--mute"             set "SPDFLAGS=%SPDFLAGS% -PspdMute"               & set "SPDJOPTS=%SPDJOPTS% -Dspd.mute=1"               & shift & goto :parseflags
if /i "%~1"=="--windowed"         set "SPDFLAGS=%SPDFLAGS% -PspdWindowed"           & set "SPDJOPTS=%SPDJOPTS% -Dspd.windowed=1"           & shift & goto :parseflags
if /i "%~1"=="--fast"             goto :parsefast
if /i "%~1"=="--at"               goto :parseat
goto :parsedone

rem --fast takes an optional numeric value; without one it means 40. One step is still applied per
rem frame, so only the pacing changes and the path taken is the same as at 1x.
rem
rem The value is only taken when it is numeric. Consuming the next argument unconditionally ate
rem whatever followed, so "--fast --mute file" treated "--mute" as the speed and shifted by two.
:parsefast
set "FASTARG=%~2"
echo %FASTARG%| findstr /r /c:"^[0-9][0-9.]*$" >nul
if errorlevel 1 goto :parsefastdefault
set "SPDFLAGS=%SPDFLAGS% -PspdFast=%FASTARG%"
set "SPDJOPTS=%SPDJOPTS% -Dspd.fast=%FASTARG%"
shift
shift
goto :parseflags
:parsefastdefault
set "SPDFLAGS=%SPDFLAGS% -PspdFast=40"
set "SPDJOPTS=%SPDJOPTS% -Dspd.fast=40"
shift
goto :parseflags

rem --at takes two separate numbers. A single comma separated argument was tried first and for/f
rem tokenised it unreliably, giving an empty Y and shifting the file argument.
:parseat
set "ATX=%~2"
set "ATY=%~3"
echo %ATX%| findstr /r /c:"^-*[0-9][0-9]*$" >nul || goto :parseatbad
echo %ATY%| findstr /r /c:"^-*[0-9][0-9]*$" >nul || goto :parseatbad
set "SPDFLAGS=%SPDFLAGS% -PspdMonitorX=%ATX% -PspdMonitorY=%ATY%"
set "SPDJOPTS=%SPDJOPTS% -Dspd.monitorX=%ATX% -Dspd.monitorY=%ATY%"
shift
shift
shift
goto :parseflags
:parseatbad
echo --at needs two whole numbers, eg: --at 1920 0
exit /b 1

:parsedone
if /i "%~1"=="--list"   goto :list
if /i "%~1"=="--record" goto :record
if /i "%~1"=="--verify" goto :verify

if "%~1"=="" goto :menu
call :resolve "%~1" || exit /b 1
goto :play

rem --- list ------------------------------------------------------------------

:list
call :catalog || exit /b 1
echo.
echo Play one with:  replay-viewer ^<file^>
exit /b 0

rem --- pick ------------------------------------------------------------------

rem The loop, entered at :menu. :play comes back here when the viewer closes, so
rem the label the catalog is printed from has to sit above the code that leaves
rem it rather than below.
:menu
call :catalog || exit /b 1
echo.
set "CHOICE="
set /p "CHOICE=Play which? Enter its number, or q to quit: "

rem No test for end of input here. set /p returns errorlevel 1 for a blank line *and*
rem for exhausted input, so an errorlevel check cannot tell Enter from EOF, and
rem checking it made a plain Enter quit - the exact behaviour being removed. The
rem alternatives all consume the line after the one being read, which would eat the
rem next answer in an interactive session. So blank reprints and EOF reprints too;
rem feeding this a redirected stream with nothing in it is not a thing anyone does.
rem
rem q before the digit test, because the digit test rejects it. Only q and quit,
rem so a mistyped number is still refused rather than read as a request to leave.
if /i "%CHOICE%"=="q"    goto :menuquit
if /i "%CHOICE%"=="quit" goto :menuquit

rem Empty input reprints instead of exiting. It used to print "Nothing selected."
rem and exit, which was right for a one-shot prompt and wrong here: inside a loop
rem it turns a stray Enter into the end of the session, and the listing is right
rem there to be reprinted.
if "%CHOICE%"=="" (
    echo Nothing selected.
    goto :menu
)

rem The test is inverted from what it looks like: with the digits as delimiters, an
rem all-digit string has no token left, so the loop body never runs. A token
rem appearing therefore means the input contained a non-digit.
set "ISNONNUM="
for /f "delims=0123456789" %%a in ("%CHOICE%") do set "ISNONNUM=yes"
if defined ISNONNUM (
    echo "%CHOICE%" is not one of the numbers listed above.
    goto :menu
)

rem A number outside the range is not fatal either. :bynumber has already said
rem which numbers exist; reprinting the list is what makes the next attempt read
rem against the same numbering that was just printed.
call :bynumber %CHOICE% || goto :menu

rem What :play tests to decide whether closing the viewer ends the session. Set
rem here and nowhere else: :menu is the only path that loops, and :play has no way
rem to tell which of its two callers sent it except this.
set "MENU=1"
goto :play

:menuquit
exit /b 0

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
rem
rem No --no-daemon: this runs on every selection, and paying a fresh JVM plus a
rem gradle startup for it made the menu feel broken. The daemon is started once and
rem then reused for the listing, the lookup and every later playback's return to the
rem menu, which is the whole point of being able to come back here.
call "%GRADLE%" --offline -q :superintelligence:replays --args="--select %TARGET% --dir "%REPLAYDIR%"" > "%SELECTFILE%" 2>nul
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
if defined MENU (
    echo   keys    SPACE pause   +/- speed   [ ] finer/coarser   R restart   ESC back to the list
) else (
    echo   keys    SPACE pause   +/- speed   [ ] finer/coarser   R restart   ESC quit
)
echo.
echo The [replay] lines report each keypress, which is the quickest way to tell
echo "the key did nothing" apart from "the key did something invisible".
echo.

rem Runs the cached launcher directly rather than through gradle.
rem
rem gradle was most of the wall time for a playback: it re-resolved and re-checked the build for every
rem recording, and the system properties each flag sets invalidate the task cache, so nothing was ever
rem up to date. The classpath only changes when the code does, so it is baked into a launcher once by
rem :desktop:replaycp and reused after that. Measured 4.7s versus 11.9s per recording.
rem
rem Falls back to gradle if the launcher is missing, so this cannot strand anyone.
set "RUNBAT=%CD%\desktop\build\replay-run.bat"
if not exist "%RUNBAT%" (
    echo building the replay launcher, once...
    call "%GRADLE%" --offline -q :desktop:classes :desktop:replaycp
)
if not exist "%RUNBAT%" (
    echo could not build the launcher; falling back to gradle
    call "%GRADLE%" --offline --no-daemon %SPDFLAGS% :desktop:replay --args="--file %FILE%"

    rem Read here, inside the block, with ! rather than %. %RC% would be expanded when the
    rem block was parsed - before the call above ran - so the exit code reported would be
    rem whatever had failed earlier, or nothing at all on a first run.
    set "RC=!errorlevel!"
    if defined MENU goto :menu
    exit /b !RC!
)

call "%RUNBAT%" %SPDJOPTS% -DImplementation-Title=shatteredpixel -DImplementation-Version=4.0.1 com.shatteredpixel.shatteredpixeldungeon.desktop.replay.ReplayLauncher --file "%FILE%"

rem Captured before anything else can run: the menu prompt, the catalog, even a failed
rem lookup would overwrite it, and the exit code is the only report the caller gets about
rem whether that recording played.
set "RC=!errorlevel!"

rem The viewer has exited. In menu mode that is ESC or a close window, and the session
rem continues from the catalog; given a file on the command line it is the end of the
rem run, because that form is invoked by scripts which expect one playback and one exit.
if defined MENU (
    echo.
    goto :menu
)
exit /b %RC%

rem --- shared ----------------------------------------------------------------

rem Prints the catalog.
rem
rem The corpus directory is named explicitly, and that is the whole reason this line is not just
rem ":superintelligence:replays". ReplayCatalog falls back to a relative "replays", which resolves
rem against the working directory - and gradle runs a JavaExec task from the *module* directory, not
rem the one this file lives in. So `replays` meant superintelligence\replays, which does not exist, and
rem the committed corpus was invisible: --list showed only whatever a training run happened to have
rem left in the temp directory. Naming it is the difference between the viewer working and not.
rem
rem No --no-daemon, and this is the one that matters most. The catalog is reprinted on
rem every return to the menu, so a fresh JVM and a fresh gradle startup per print made
rem coming back from a playback look like a hang: the window had closed, and nothing
rem happened for several seconds. Paying for a daemon once and reusing it is what makes
rem the menu worth returning to.
:catargs
call "%GRADLE%" --offline -q :superintelligence:replays --args="--dir "%REPLAYDIR%""
exit /b %errorlevel%

:catalog
call :catargs
exit /b %errorlevel%

rem Accepts a bare recording name as well as a path, and always leaves %FILE%
rem absolute so the gradle tasks, whose working directory is the module rather
rem than the repository root, cannot miss it.
rem
rem This is for arguments typed on the command line, not for the interactive
rem picker, which takes a number and asks the java side to resolve it.
rem
rem The name is handed to the java side rather than probed for here. It used to
rem be tested against two hardcoded directories with four extension
rem permutations, which found 15 of the 129 recordings on this machine and
rem reported the rest as "Not found" - the trainer writes to <--out>/replays and
rem --out is wherever the run was pointed, so most recordings were in a
rem directory this file had never heard of. The search now lives in one place,
rem in a language where it can be tested, exactly as :catalog and :bynumber
rem already were.
:resolve
set "FILE=%~1"
if exist "%FILE%" goto :absolute

set "RESOLVEFILE=%TEMP%\spd-replay-resolve.txt"
call "%GRADLE%" --offline --no-daemon -q :superintelligence:replays --args="--resolve %~1 --dir "%REPLAYDIR%"" > "%RESOLVEFILE%" 2>nul

rem The java side answers with either a path or a sentence, and always exits 0 so gradle
rem does not wrap a refusal in its own FAILURE block. A sentence is not a path, so the
rem two are told apart by testing what came back.
set "FOUND="
set /p FOUND=<"%RESOLVEFILE%"
del "%RESOLVEFILE%" >nul 2>&1

if not defined FOUND (
    echo Could not resolve %~1.
    echo.
    echo replay-viewer --list    lists every recording it can find
    exit /b 1
)
if not exist "!FOUND!" (
    echo !FOUND!
    exit /b 1
)
set "FILE=!FOUND!"

:absolute
for %%I in ("%FILE%") do set "FILE=%%~fI"
if not exist "%FILE%" (
    echo Not found: %~1
    exit /b 1
)
exit /b 0
