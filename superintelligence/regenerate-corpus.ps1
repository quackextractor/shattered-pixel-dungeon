param(
    [string]$Dir = (Join-Path $PSScriptRoot 'test-replays'),
    [int]$ShortTurns = 25,
    [int]$MidTurns = 150,
    [int]$LongTurns = 600,
    [int]$DeathProbeSeconds = 20,
    [int]$PerFileSeconds = 120,
    [int]$Parallel = 0,
    [int]$BudgetSeconds = 60
)

$ErrorActionPreference = 'Stop'

$module = $PSScriptRoot
$repo   = Split-Path -Parent $module
Push-Location $repo

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "building classes..."
& ./gradlew :superintelligence:classes -q
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "build failed" }

$cp = (Get-Content (Join-Path $repo 'desktop\build\replay-classpath.txt') -Raw).Trim()
$classes = Join-Path $module 'build\classes\java\main'
$resources = Join-Path $module 'build\resources\main'
$cp = "$classes;$resources;$cp"
$main = 'com.shatteredpixel.shatteredpixeldungeon.superintelligence.Main'

if ($Parallel -le 0) {
    $Parallel = [Math]::Max(2, (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors - 2)
}

if (-not (Test-Path $Dir)) { New-Item -ItemType Directory -Path $Dir | Out-Null }

Get-ChildItem $Dir -File -Filter *.replay -ErrorAction SilentlyContinue | Remove-Item -Force
$rejected = Join-Path $Dir 'rejected'
if (Test-Path $rejected) { Remove-Item $rejected -Recurse -Force }

$logDir = Join-Path $env:TEMP "spd-corpus-log"
if (Test-Path $logDir) { Remove-Item $logDir -Recurse -Force }
New-Item -ItemType Directory -Path $logDir | Out-Null

$script:spawned = [System.Collections.ArrayList]::new()

trap {
    foreach ($proc in $script:spawned) {
        try {
            if ($proc -and -not $proc.HasExited) { $proc.Kill() }
        } catch {
        }
    }
    Write-Host ""
    Write-Host "[ERROR] corpus build aborted; every process it started has been killed" -ForegroundColor Red
    Pop-Location
    break
}

function Start-Recording {
    param([string]$Name, [string]$Hero, [int]$Turns, [string]$Seed, [string]$SaveDir = $Dir)

    $path = Join-Path $SaveDir "$Name.replay"
    $log = Join-Path $logDir "$Name.log"

    $work = Join-Path $logDir "work-$Name"
    if (Test-Path $work) { Remove-Item $work -Recurse -Force }
    New-Item -ItemType Directory -Path $work | Out-Null

    $proc = Start-Process -FilePath 'java' -PassThru -NoNewWindow `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
        -ArgumentList @('-cp', $cp, $main, 'rollout', '--seed', $Seed, '--hero', $Hero,
                        '--max-turns', "$Turns", '--save', $path, '--out', $work, '--no-color')

    $null = $script:spawned.Add($proc)

    [PSCustomObject]@{
        Name = $Name; Hero = $Hero; Seed = $Seed; Path = $path; Log = $log; Work = $work; Proc = $proc
    }
}

function Complete-Recording {
    param($Job)

    $Job.Proc.WaitForExit($PerFileSeconds * 1000) | Out-Null
    if (-not $Job.Proc.HasExited) {
        $Job.Proc.Kill()
        return [PSCustomObject]@{ Job = $Job; Ok = $false; Reason = "timed out after ${PerFileSeconds}s" }
    }

    $out = (Get-Content $Job.Log -Raw -ErrorAction SilentlyContinue)
    $err = (Get-Content "$($Job.Log).err" -Raw -ErrorAction SilentlyContinue)

    if (-not (Test-Path $Job.Path)) {
        $first = (($out + "`n" + $err) -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 1)
        return [PSCustomObject]@{ Job = $Job; Ok = $false; Reason = "no file; $first" }
    }

    $steps = (Get-Content $Job.Path | Select-String -Pattern '^[A-Z_]+ ').Count
    $termination = '?'
    foreach ($reason in @('DEATH', 'VICTORY', 'TURN_LIMIT', 'STALLED')) {
        if ($out -match "outcome\s+$reason") { $termination = $reason; break }
    }

    if ($termination -eq '?') {
        return [PSCustomObject]@{ Job = $Job; Ok = $false
            Reason = "wrote a file but reported no outcome; see $($Job.Log)" }
    }

    [PSCustomObject]@{ Job = $Job; Ok = $true; Steps = $steps; Termination = $termination; Output = $out }
}

$heroes = @('WARRIOR', 'MAGE', 'ROGUE', 'HUNTRESS', 'DUELIST', 'CLERIC')

$plan = @()
foreach ($hero in $heroes) {
    $plan += , @("$($hero.ToLower())-short", $hero, $ShortTurns)
}
foreach ($hero in $heroes) {
    $plan += , @("$($hero.ToLower())-mid", $hero, $MidTurns)
}
foreach ($hero in @('WARRIOR', 'MAGE')) {
    $plan += , @("$($hero.ToLower())-long", $hero, $LongTurns)
}
foreach ($hero in @('WARRIOR', 'MAGE')) {
    $plan += , @("$($hero.ToLower())-alt", $hero, $MidTurns)
}

Write-Host "recording $($plan.Count) episodes, $Parallel at a time, into $Dir"

function Report-Recording {
    param($Finished)
    if ($Finished.Ok) {
        Write-Host ("  {0,-22} {1,-10} {2,6} steps  {3}" -f `
            $Finished.Job.Name, $Finished.Job.Hero, $Finished.Steps, $Finished.Termination)
    } else {
        Write-Host ("  {0,-22} {1,-10} FAILED: {2}" -f $Finished.Job.Name, $Finished.Job.Hero, $Finished.Reason) -ForegroundColor Red
        $script:failed += "$($Finished.Job.Name).replay"
    }
}

$failed = @()
$running = @()
foreach ($entry in $plan) {
    while ($running.Count -ge $Parallel) {
        Report-Recording (Complete-Recording $running[0])
        $running = @($running | Select-Object -Skip 1)
    }
    $running += Start-Recording -Name $entry[0] -Hero $entry[1] -Turns $entry[2] -Seed $entry[0]
}
while ($running.Count -gt 0) {
    Report-Recording (Complete-Recording $running[0])
    $running = @($running | Select-Object -Skip 1)
}

Write-Host "probing for a death-terminated run (up to ${DeathProbeSeconds}s)"

$probeDir = Join-Path $logDir 'probes'
New-Item -ItemType Directory -Path $probeDir -Force | Out-Null

$probeCount = 12
$probes = @()
for ($i = 0; $i -lt $probeCount; $i++) {
    $probes += Start-Recording -Name "probe-death-$i" -Hero 'WARRIOR' -Turns 200 -Seed "death-probe-$i" -SaveDir $probeDir
}

$deadline = [DateTime]::UtcNow.AddSeconds($DeathProbeSeconds)
$winner = $null
foreach ($probe in $probes) {
    $remaining = [int]([Math]::Max(1, ($deadline - [DateTime]::UtcNow).TotalMilliseconds))
    $probe.Proc.WaitForExit($remaining) | Out-Null
    if (-not $probe.Proc.HasExited) { $probe.Proc.Kill(); continue }
    $out = (Get-Content $probe.Log -Raw -ErrorAction SilentlyContinue)
    if ($out -match 'outcome\s+DEATH') { $winner = $probe; break }
}

if ($winner) {
    $deathPath = Join-Path $Dir 'warrior-death.replay'
    Copy-Item $winner.Path $deathPath -Force
    $steps = (Get-Content $deathPath | Select-String -Pattern '^[A-Z_]+ ').Count
    Write-Host ("  {0,-22} {1,-10} {2,6} steps  DEATH (seed {3})" -f 'warrior-death', 'WARRIOR', $steps, $winner.Seed)
} else {
    Write-Host "  no seed died within the budget; the corpus has no death recording" -ForegroundColor Yellow
}

foreach ($probe in $probes) {
    if (-not $probe.Proc.HasExited) {
        $probe.Proc.Kill()
        $probe.Proc.WaitForExit(5000) | Out-Null
    }
}
Remove-Item $probeDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "rebuilding the three fixtures that are not rollouts"
$fixtures = @(
    @{ Name = 'duelist-drop.replay';   Command = 'dropfixture';   Seed = 'DROPFIXTURE' }
    @{ Name = 'duelist-throw.replay';  Command = 'pressfixture';  Seed = 'PRESSFIXTURE' }
)
foreach ($fixture in $fixtures) {
    $log = Join-Path $logDir "fixture-$($fixture.Name).log"
    $work = Join-Path $logDir "fixturework-$($fixture.Name)"
    if (Test-Path $work) { Remove-Item $work -Recurse -Force }
    New-Item -ItemType Directory -Path $work | Out-Null

    $proc = Start-Process -FilePath 'java' -PassThru -NoNewWindow `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
        -ArgumentList @('-cp', $cp, $main, $fixture.Command,
                        '--save', (Join-Path $Dir $fixture.Name),
                        '--out', $work, '--no-color')
    $null = $script:spawned.Add($proc)
    $proc.WaitForExit($PerFileSeconds * 1000) | Out-Null
    if (-not $proc.HasExited) { $proc.Kill() }

    if (Test-Path (Join-Path $Dir $fixture.Name)) {
        Write-Host ("  {0,-24} rebuilt" -f $fixture.Name)
    } else {
        Write-Host ("  {0,-24} NOT REBUILT - see {1}" -f $fixture.Name, $log) -ForegroundColor Red
        $failed += $fixture.Name
    }
}

$deathLog = Join-Path $logDir 'fixture-duelist-death-last.log'
$deathBuilt = Join-Path $module 'out\evidence\duelist-acceptance-fixture.replay'
if (Test-Path $deathBuilt) { Remove-Item $deathBuilt -Force }
$deathProc = Start-Process -FilePath 'java' -PassThru -NoNewWindow -WorkingDirectory $module `
    -RedirectStandardOutput $deathLog -RedirectStandardError "$deathLog.err" `
    -ArgumentList @('-cp', $cp, 'com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReproCheck', 'deathfixture')
$null = $script:spawned.Add($deathProc)
$deathProc.WaitForExit($PerFileSeconds * 1000) | Out-Null
if (-not $deathProc.HasExited) { $deathProc.Kill() }

if (Test-Path $deathBuilt) {
    Move-Item $deathBuilt (Join-Path $Dir 'duelist-death-last.replay') -Force
    Write-Host ("  {0,-24} rebuilt" -f 'duelist-death-last.replay')
} else {
    Write-Host ("  {0,-24} NOT REBUILT - see {1}" -f 'duelist-death-last.replay', $deathLog) -ForegroundColor Red
    $failed += 'duelist-death-last.replay'
}

Write-Host "verifying every recording reproduces, $Parallel at a time"

$files = @(Get-ChildItem $Dir -File -Filter *.replay | Sort-Object Name)
$pending = @()
foreach ($f in $files) {
    $log = Join-Path $logDir "verify-$($f.BaseName).log"
    $work = Join-Path $logDir "verifywork-$($f.BaseName)"
    if (Test-Path $work) { Remove-Item $work -Recurse -Force }
    New-Item -ItemType Directory -Path $work | Out-Null

    $proc = Start-Process -FilePath 'java' -PassThru -NoNewWindow `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
        -ArgumentList @('-cp', $cp, $main, 'verify', $f.FullName, '--out', $work, '--no-color')
    $null = $script:spawned.Add($proc)
    $pending += , [PSCustomObject]@{ File = $f; Proc = $proc; Log = $log }
}

$bad = @()
$verified = @()
while ($pending.Count -gt 0) {
    $item = $pending[0]
    $pending = @($pending | Select-Object -Skip 1)

    $item.Proc.WaitForExit($PerFileSeconds * 1000) | Out-Null
    if (-not $item.Proc.HasExited) {
        $item.Proc.Kill()
        $bad += $item.File.Name
        continue
    }

    $out = (Get-Content $item.Log -Raw -ErrorAction SilentlyContinue)
    if ($out -match '\[OK\]') {
        $steps = (Select-String -InputObject $out -Pattern 'steps=(\d+)').Matches.Groups[1].Value
        $verified += , [PSCustomObject]@{ Name = $item.File.Name; Steps = [int]$steps }
    } else {
        $bad += $item.File.Name
        if (-not (Test-Path $rejected)) { New-Item -ItemType Directory -Path $rejected | Out-Null }
        Move-Item $item.File.FullName (Join-Path $rejected $item.File.Name) -Force
    }
}

foreach ($v in ($verified | Sort-Object Name)) {
    Write-Host ("  {0,-24} reproduces ({1} steps)" -f $v.Name, $v.Steps)
}
foreach ($name in $bad) {
    Write-Host ("  {0,-24} DOES NOT REPRODUCE - moved to rejected/" -f $name) -ForegroundColor Red
}

$stopwatch.Stop()

$kept = @($verified).Count
$onDisk = (Get-ChildItem $Dir -File -Filter *.replay).Count
if ($onDisk -ne $kept) {
    Write-Host "  WARNING: $onDisk files on disk but $kept verified; the corpus is not what was reported" -ForegroundColor Red
}

$heroes_covered = @($verified | ForEach-Object { ($_.Name -split '-')[0] } | Sort-Object -Unique)

Write-Host ""
Write-Host "corpus rebuilt: $kept recordings verified, in $([Math]::Round($stopwatch.Elapsed.TotalSeconds, 1))s"
Write-Host "  heroes covered: $($heroes_covered -join ', ')"
Write-Host "  slowest recording: $((($verified | Sort-Object Steps -Descending | Select-Object -First 1).Steps)) steps"

if ($stopwatch.Elapsed.TotalSeconds -gt $BudgetSeconds) {
    Write-Host ("  WARNING: over the ${BudgetSeconds}s budget by $([Math]::Round($stopwatch.Elapsed.TotalSeconds - $BudgetSeconds, 1))s") -ForegroundColor Yellow
}

if ($failed.Count -gt 0) {
    Write-Host "$($failed.Count) recording(s) not built: $($failed -join ', ')" -ForegroundColor Red
}
if ($bad.Count -gt 0) {
    Write-Host "$($bad.Count) recording(s) rejected: $($bad -join ', ')" -ForegroundColor Yellow
}
if ($failed.Count + $bad.Count -gt 0) {
    Pop-Location
    exit 1
}

Pop-Location
exit 0