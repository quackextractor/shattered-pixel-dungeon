param(
  [string]$Dir = "$env:TEMP\spd-regen\replays",
  [int]$Limit = 0,
  [string]$Speed = "400",
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
$cp = (Get-Content 'desktop\build\replay-classpath.txt' -Raw).Trim()
$cp = "$cp;superintelligence\build\classes\java\main"

$files = Get-ChildItem $Dir -File -Filter *.replay | Sort-Object Name
if ($Limit -gt 0) { $files = $files | Select-Object -First $Limit }

$diverged = 0; $clean = 0; $other = 0

foreach ($f in $files) {
  # Started as a job so it can be killed on a deadline. Invoke-Expression waits forever by design, which
  # is exactly the failure this script exists to survive.
  # -Dspd.autoClose is what makes the viewer exit rather than leave its window up, and the batch
  # script has no way to press ESC. Same launcher the .bat uses, so this exercises that path.
  $job = Start-Job -ScriptBlock {
    param($cp, $file, $speed)
    & java -cp $cp "-Dspd.mute=1" "-Dspd.autoClose=1" "-Dspd.fast=$speed" "-Dspd.monitorX=3000" "-Dspd.monitorY=0" `
          com.shatteredpixel.shatteredpixeldungeon.desktop.replay.ReplayLauncher --file $file 2>&1 | Out-String
  } -ArgumentList $cp, $f.FullName, $Speed

  $sw = [Diagnostics.Stopwatch]::StartNew()
  $done = Wait-Job $job -Timeout $PerFileSeconds
  $sw.Stop()

  if ($null -eq $done) {
    Stop-Job $job -ErrorAction SilentlyContinue
    Write-Output ("  {0,-22} TIMED OUT after {1}s" -f $f.BaseName, $PerFileSeconds)
    $other++
  } else {
    $out = Receive-Job $job
    Remove-Job $job -Force -ErrorAction SilentlyContinue

    # Two ways to finish cleanly, and recognising only one made a working viewer look broken.
    # A recording that declares why it ended - TURN_LIMIT, STALLED - now ends by saying so, because the
    # viewer cannot otherwise tell a complete recording from a truncated one. Reading only
    # "replay finished - all N steps played" reported all of those as HALTED, which counted seven
    # successes as failures.
    if ($out -match 'DIVERGED at step (\d+)') {
      $detail = ($out -split "`n" | Where-Object { $_ -match 'DIVERGED at step' } | Select-Object -First 1)
      Write-Output ("  {0,-22} DIVERGED step {1,-6} {2,6:N0}ms  {3}" -f $f.BaseName, $Matches[1], $sw.Elapsed.TotalMilliseconds, $detail.Trim())
      $diverged++
    } elseif ($out -match 'replay finished - all (\d+) steps played') {
      Write-Output ("  {0,-22} CLEAN      {1,6} steps {2,6:N0}ms" -f $f.BaseName, $Matches[1], $sw.Elapsed.TotalMilliseconds)
      $clean++
    } elseif ($out -match 'run ended - the recording ends here as (\w+)') {
      Write-Output ("  {0,-22} CLEAN      ended as {1,-10} {2,6:N0}ms" -f $f.BaseName, $Matches[1], $sw.Elapsed.TotalMilliseconds)
      $clean++
    } else {
      $why = ($out -split "`n" | Where-Object { $_ -match 'halted' } | Select-Object -First 1)
      Write-Output ("  {0,-22} HALTED     {1,6}ms  {2}" -f $f.BaseName, $sw.Elapsed.TotalMilliseconds, $why.Trim())
      $other++
    }
  }

  Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'ReplayLauncher' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

Write-Output ""
Write-Output "  total $($files.Count)  clean $clean  diverged $diverged  other $other"