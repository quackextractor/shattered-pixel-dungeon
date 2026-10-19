param(
  [string]$Dir = (Join-Path $PSScriptRoot 'test-replays'),
  [int]$Limit = 0,
  [string]$Speed = "400",
  [int]$Parallel = 1,
  [int]$PerFileSeconds = 150
)

$ErrorActionPreference = 'Stop'

$module = $PSScriptRoot
$repo   = Split-Path -Parent $module
Push-Location $repo
& ./gradlew :desktop:replaycp -q
$built = $LASTEXITCODE
Pop-Location
if ($built -ne 0) { throw "build failed" }
$cp = (Get-Content (Join-Path $repo 'desktop\build\replay-classpath.txt') -Raw).Trim()

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

$parallel = [Math]::Max(1, $Parallel)
$running = @()

$logDir = Join-Path $module 'out\evidence\replay-batch-log'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Start-Playback {
  param($File, $CP, $SpeedValue, $Index)

  $log = Join-Path $logDir "$Index-$($File.BaseName).log"
  $proc = Start-Process -FilePath 'java' -PassThru -NoNewWindow `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
    -ArgumentList @('-cp', $CP, '-Dspd.mute=1', '-Dspd.autoClose=1', "-Dspd.fast=$SpeedValue",
                    '-Dspd.monitorX=3000', '-Dspd.monitorY=0',
                    'com.shatteredpixel.shatteredpixeldungeon.desktop.replay.ReplayLauncher', '--file', $File.FullName)

  [PSCustomObject]@{ File = $File; Proc = $proc; Log = $log; Watch = [Diagnostics.Stopwatch]::StartNew() }
}

function Complete-Playback {
  param($Item, $PerFile)

  $Item.Proc.WaitForExit($PerFile * 1000) | Out-Null
  $Item.Watch.Stop()
  $f = $Item.File

  if (-not $Item.Proc.HasExited) {
    & taskkill /T /F /PID $Item.Proc.Id 2>&1 | Out-Null
    Write-Output ("  {0,-22} TIMED OUT after {1}s" -f $f.BaseName, $PerFile)
    $script:other++
    return
  }

  $out = "$(Get-Content $Item.Log -Raw -ErrorAction SilentlyContinue)`n$(Get-Content "$($Item.Log).err" -Raw -ErrorAction SilentlyContinue)"

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
    Write-Output ("  {0,-22} HALTED     {1,6}ms  {2}" -f $f.BaseName, $Item.Watch.Elapsed.TotalMilliseconds, "$why".Trim())
    $script:other++
  }
}

$started = [System.Diagnostics.Stopwatch]::StartNew()
$index = 0
foreach ($f in $files) {
  while ($running.Count -ge $parallel) {
    Complete-Playback $running[0] $PerFileSeconds
    $running = @($running | Select-Object -Skip 1)
  }
  $running += Start-Playback -File $f -CP $cp -SpeedValue $Speed -Index ($index++)
}
while ($running.Count -gt 0) {
  Complete-Playback $running[0] $PerFileSeconds
  $running = @($running | Select-Object -Skip 1)
}
$started.Stop()

Write-Output ""
Write-Output "  total $($files.Count)  clean $clean  diverged $diverged  other $other"
Write-Output "  $parallel at a time, $([Math]::Round($started.Elapsed.TotalSeconds,1))s wall"

if ($diverged + $other -gt 0) { exit 1 }
