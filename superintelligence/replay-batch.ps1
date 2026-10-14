param(
  # Beside this script, which is the module. It used to default to a temp path that
  # regenerate-corpus.ps1 had stopped writing to years earlier, so the default either found
  # nothing or found something nobody had produced.
  [string]$Dir = (Join-Path $PSScriptRoot 'test-replays'),
  [int]$Limit = 0,
  [string]$Speed = "400",
  # Concurrent playbacks. Each is its own JVM with its own GL context, so this is the same shape as
  # viewcheck's parallel children - but see the note above the loop: at N = 10 a batch reports
  # divergences that are not there, so 1 is the default and higher values are a smoke test.
  [int]$Parallel = 1,
  # Per-recording wall clock ceiling. A viewer that stops progressing still owns its window and its
  # process, so the run has to be killed rather than left to block the batch.
  [int]$PerFileSeconds = 150
)

# Plays every recording in $Dir and prints one line each: the seed, and either the step playback
# diverged at or the fact that it reached the end.
#
# --close is not optional here. Without it a finished playback leaves the window open waiting for a
# keypress, which no batch can send, so a completed run and a hung one look the same and the batch
# hangs instead of reporting.

$ErrorActionPreference = 'Stop'

# Both paths are resolved from this script's own location. The classpath file is written by
# :desktop:replaycp into the desktop module, which is one level up from here.
$module = $PSScriptRoot
$repo   = Split-Path -Parent $module
$cp = (Get-Content (Join-Path $repo 'desktop\build\replay-classpath.txt') -Raw).Trim()
$cp = "$cp;$(Join-Path $module 'build\classes\java\main')"

# Recursive, because a training run given --replay-group files its recordings one level down. A
# flat listing over trainer-replays would report zero recordings exactly when there are the most.
#
# Two extensions, deliberately, matching ReplayCatalog's own rule: the checked-in fixtures are
# .replay and the trainer writes <seed>.dat. Filtering on .replay alone reported
#
#   total 0  clean 0  diverged 0  other 0
#
# over a directory holding sixteen trainer recordings - which reads as a clean run of nothing,
# and is the one number a caller would believe.
$files = Get-ChildItem $Dir -Recurse -File |
         Where-Object { $_.Extension -in '.replay', '.dat' } |
         Sort-Object FullName
if ($Limit -gt 0) { $files = $files | Select-Object -First $Limit }

if ($files.Count -eq 0) {
  Write-Output "  no .replay or .dat under $Dir"
  Write-Output ""
  Write-Output "  total 0  clean 0  diverged 0  other 0"
  exit 0
}

$diverged = 0; $clean = 0; $other = 0

# One playback at a time by default; -Parallel N runs N.
#
# Each playback is its own JVM with its own GL context and its own window, which is what makes this
# safe to fan out: nothing is shared but the classpath. The same shape as viewcheck's parallel children.
#
# Measured, and it is a weaker guarantee than viewcheck's: at -Parallel 10 over this run's 16
# recordings, **two reported DIVERGED and both played clean one at a time** -
#
#     -Parallel 10   14 clean, 2 DIVERGED  (step 112 and step 92, both "engine time is 2.0, recording
#                                                     says 89.0", i.e. the clock near zero)
#     1 at a time      2 clean, 0 DIVERGED
#
# Playback is timing-sensitive and ten GL windows on one machine is enough to starve a frame, so a
# starved playback reports a divergence that is not one. viewcheck's documentation claims membership
# stays reliable under fan-out; **here it does not**, and a batch run's counts should be read as a
# smoke test that says which files to re-run one at a time, not as a verdict.
$parallel = [Math]::Max(1, $Parallel)
$running = @()

function Start-Playback {
  param($File, $CP, $SpeedValue)

  # Started as a job so it can be killed on a deadline. Invoke-Expression waits forever by design, which
  # is exactly the failure this script exists to survive.
  # -Dspd.autoClose is what makes the viewer exit rather than leave its window up, and the batch
  # script has no way to press ESC. Same launcher the .bat uses, so this exercises that path.
  $job = Start-Job -ScriptBlock {
    param($cp, $file, $speed)
    & java -cp $cp "-Dspd.mute=1" "-Dspd.autoClose=1" "-Dspd.fast=$speed" "-Dspd.monitorX=3000" "-Dspd.monitorY=0" `
          com.shatteredpixel.shatteredpixeldungeon.desktop.replay.ReplayLauncher --file $file 2>&1 | Out-String
  } -ArgumentList $CP, $File.FullName, $SpeedValue

  [PSCustomObject]@{ File = $File; Job = $job; Watch = [Diagnostics.Stopwatch]::StartNew() }
}

function Complete-Playback {
  param($Item, $PerFile)

  $done = Wait-Job $Item.Job -Timeout $PerFile
  $Item.Watch.Stop()
  $f = $Item.File

  if ($null -eq $done) {
    Stop-Job $Item.Job -ErrorAction SilentlyContinue
    Write-Output ("  {0,-22} TIMED OUT after {1}s" -f $f.BaseName, $PerFile)
    $script:other++
    return
  }

  $out = Receive-Job $Item.Job
  Remove-Job $Item.Job -Force -ErrorAction SilentlyContinue

  # Two ways to finish cleanly, and recognising only one made a working viewer look broken.
  # A recording that declares why it ended - TURN_LIMIT, STALLED - now ends by saying so, because the
  # viewer cannot otherwise tell a complete recording from a truncated one. Reading only
  # "replay finished - all N steps played" reported all of those as HALTED, which counted seven
  # successes as failures.
  if ($out -match 'DIVERGED at step (\d+)') {
    $detail = ($out -split "`n" | Where-Object { $_ -match 'DIVERGED at step' } | Select-Object -First 1)
    Write-Output ("  {0,-22} DIVERGED step {1,-6} {2,6:N0}ms  {3}" -f $f.BaseName, $Matches[1], $Item.Watch.Elapsed.TotalMilliseconds, $detail.Trim())
    $script:diverged++
  } elseif ($out -match 'replay finished - all (\d+) steps played') {
    Write-Output ("  {0,-22} CLEAN      {1,6} steps {2,6:N0}ms" -f $f.BaseName, $Matches[1], $Item.Watch.Elapsed.TotalMilliseconds)
    $script:clean++
  } elseif ($out -match 'run ended - the recording ends here as (\w+)') {
    Write-Output ("  {0,-22} CLEAN      ended as {1,-10} {2,6:N0}ms" -f $f.BaseName, $Matches[1], $Item.Watch.Elapsed.TotalMilliseconds)
    $script:clean++
  } else {
    $why = ($out -split "`n" | Where-Object { $_ -match 'halted' } | Select-Object -First 1)
    Write-Output ("  {0,-22} HALTED     {1,6}ms  {2}" -f $f.BaseName, $Item.Watch.Elapsed.TotalMilliseconds, $why.Trim())
    $script:other++
  }
}

$started = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($f in $files) {
  while ($running.Count -ge $parallel) {
    Complete-Playback $running[0] $PerFileSeconds
    $running = @($running | Select-Object -Skip 1)
  }
  $running += Start-Playback -File $f -CP $cp -SpeedValue $Speed
}
while ($running.Count -gt 0) {
  Complete-Playback $running[0] $PerFileSeconds
  $running = @($running | Select-Object -Skip 1)
}
$started.Stop()

# A viewer that stopped progressing still owns its window and its process. Swept once at the end
# rather than after each file, because with -Parallel N a per-file sweep would kill siblings that are
# mid-playback and report them as HALTED.
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match 'ReplayLauncher' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Write-Output ""
Write-Output "  total $($files.Count)  clean $clean  diverged $diverged  other $other"
Write-Output "  $parallel at a time, $([Math]::Round($started.Elapsed.TotalSeconds,1))s wall"