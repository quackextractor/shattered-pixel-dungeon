<#
.SYNOPSIS
    Runs a training run and prints what it did. No paths to remember.

.DESCRIPTION
    Wraps `gradlew :superintelligence:train` with the defaults this repository actually runs with, so a
    run is one line and its output lands somewhere findable:

      out/run/       the checkpoint, resumable with -Resume
      out/evidence/  the metrics CSV and the full log, named after the run

    Everything else is a switch. `./train.ps1 -?` lists them.

.EXAMPLE
    ./train.ps1
    The short, aggressive cycle, and the default: three generations, four workers, one episode each,
    a verdict in under a minute, and the previous run of the same name beside it.

.EXAMPLE
    ./train.ps1 -Long
    30 generations, the shipped reward function, pool sized to the machine.

.EXAMPLE
    ./train.ps1 -Long -Profile depth_only -Generations 60 -Name depth-only-60
    The experiment: only a new floor or a win pays.
    Writes out/evidence/depth-only-60.csv and .log.

.EXAMPLE
    ./train.ps1 -Long -Resume
    Continue the last run for another 30 generations from out/run/weights.bin.

.EXAMPLE
    ./train.ps1 -Long -Resume -Generations 2
    Two more generations, not "up to generation 2". -Generations is always added to the checkpoint's.
#>
[CmdletBinding()]
param(
    # Generations to run. Always the number to ADD: -Resume from generation 20 with -Generations 2 stops at 22.
    [int]    $Generations = 30,

    # Worker processes. 0 lets the trainer size the pool to the machine and its memory.
    [int]    $Workers = 0,

    # Episodes per worker per generation.
    [int]    $Episodes = 4,

    # Trainer seed. Fix it to compare two runs; change it for a different search each time.
    [long]   $Seed = 20261013,

    # Reward profile: default pays every term, depth_only pays only for a new floor or a win.
    [ValidateSet('default', 'depth_only')]
    [string] $Profile = 'default',

    # Threads for the PPO update, which is the run's critical path.
    [int]    $UpdateThreads = 8,

    # Generations between checkpoints. The last one always checkpoints.
    [int]    $CheckpointEvery = 10,

    # Label for the CSV and the log in out/evidence. Defaults to a name built from the settings.
    [string] $Name,

    # Continue the run in out/run/weights.bin instead of starting fresh.
    [switch] $Resume,

    # The short cycle, which is the default; kept so the spelling the documents use still works.
    [switch] $Quick,

    # The long run: 30 generations, the pool sized to the machine. Anything named on the command line wins.
    [switch] $Long,

    # Directory of demonstration recordings (goexplore output) imitated every generation.
    [string] $Demos
)

$ErrorActionPreference = 'Stop'

function Show-Summary {
    param([string] $Csv, [string] $Log, [double] $Seconds, [string] $Bin)

    if (-not (Test-Path -LiteralPath $Csv)) {
        Write-Host '  [WARN]  no metrics CSV was written, so there is nothing to summarise' -ForegroundColor Yellow
        return
    }

    $rows = @(Import-Csv -LiteralPath $Csv)
    if ($rows.Count -eq 0) { return }

    $last  = $rows[-1]
    $best  = $rows | Sort-Object { [double]$_.bestScore } -Descending | Select-Object -First 1
    $deep  = $rows | Sort-Object { [int]$_.bestDepth } -Descending | Select-Object -First 1
    $slow  = $rows | Sort-Object { [double]$_.wallSeconds } -Descending | Select-Object -First 1
    $wall  = ($rows | Measure-Object -Property wallSeconds -Sum).Sum

    # a trend is a claim about where it went, not only where it ended: first third against last third
    $third = [Math]::Max(1, [int]($rows.Count / 3))
    $head  = $rows[0..($third - 1)]
    $tail  = $rows[($rows.Count - $third)..($rows.Count - 1)]

    Write-Host ''
    Write-Host '  RUN' -ForegroundColor Cyan
    Write-Host ("    generations  {0}   {1:N1} min wall   {2:N0}s per generation" -f `
        $rows.Count, ($Seconds / 60), ($wall / $rows.Count))
    Write-Host ("    score        mean {0:N0} -> {1:N0}    best {2:N0} at generation {3}" -f `
        (Avg $head 'meanScore'), (Avg $tail 'meanScore'), [double]$best.bestScore, $best.generation)
    Write-Host ("    depth        mean {0:N2} -> {1:N2}    deepest {2} at generation {3}" -f `
        (Avg $head 'meanDepth'), (Avg $tail 'meanDepth'), $deep.bestDepth, $deep.generation)
    Write-Host ("    endings      {0}" -f (Endings $last))
    Write-Host ("    slowest      generation {0} at {1:N0}s" -f $slow.generation, [double]$slow.wallSeconds)
    if (Test-Path -LiteralPath $Bin) {
        Write-Host ("    checkpoint   {0}  ({1:N0} MB, resumable with -Resume)" -f `
            $Bin, ((Get-Item -LiteralPath $Bin).Length / 1MB))
    }
    Write-Host ''
    Write-Host '  TREND   first third -> last third' -ForegroundColor Cyan
    $headScore = Avg $head 'meanScore'
    $tailScore = Avg $tail 'meanScore'
    foreach ($entry in @( @{ label = 'first'; set = $head }, @{ label = 'last'; set = $tail } )) {
        Write-Host ("    {0,-6} {1}  score {2,9:N0}   depth {3:N2}" -f `
            $entry.label, (Bar (Avg $entry.set 'meanScore') $headScore $tailScore 24), `
            (Avg $entry.set 'meanScore'), (Avg $entry.set 'meanDepth'))
    }
    Write-Host ''
    if (Test-Path -LiteralPath "$Csv.prev") {
        $prev = @(Import-Csv -LiteralPath "$Csv.prev")
        if ($prev.Count -gt 0) {
            Write-Host '  AGAINST THE PREVIOUS RUN OF THIS NAME' -ForegroundColor Cyan
            Write-Host ("    score        {0:N0} -> {1:N0}" -f (Avg $prev 'meanScore'), (Avg $rows 'meanScore'))
            Write-Host ("    depth        {0:N2} -> {1:N2}" -f (Avg $prev 'meanDepth'), (Avg $rows 'meanDepth'))
            Write-Host ("    turns        {0:N0} -> {1:N0}" -f (Avg $prev 'meanTurns'), (Avg $rows 'meanTurns'))
            Write-Host ("    endings      {0}  ->  {1}" -f (Endings $prev[-1]), (Endings $last))
            Write-Host ''
        }
    }
    Write-Host "  log     $Log" -ForegroundColor DarkGray
    Write-Host ''
}

function Endings {
    param($Row)
    $parts = @()
    foreach ($p in $Row.PSObject.Properties) {
        if ($p.Name -like 'end*' -and [int]$p.Value -gt 0) {
            $parts += ('{0} {1}' -f $p.Name.Substring(3).ToLower(), $p.Value)
        }
    }
    if ($parts.Count -eq 0) { return 'none recorded' }
    return ($parts -join ', ')
}

function Avg {
    param($Set, [string] $Field)
    if ($null -eq $Set -or @($Set).Count -eq 0) { return 0 }
    $m = @($Set) | Measure-Object -Property $Field -Average
    if ($m.Count -eq 0) { return 0 }
    return [double]$m.Average
}

function Bar {
    param([double] $Value, [double] $Low, [double] $High, [int] $Width)
    # scaled between the run's own two thirds, so the bar shows direction rather than absolute size
    $span = [Math]::Abs($High - $Low)
    if ($span -lt 1e-9) { return ('[' + ('*' * $Width) + ']') }
    $filled = [int][Math]::Round(($Value - $Low) / $span * $Width)
    $filled = [Math]::Max(0, [Math]::Min($Width, $filled))
    return ('[' + ('#' * $filled) + ('-' * ($Width - $filled)) + ']')
}

$module = Split-Path -Parent $MyInvocation.MyCommand.Path      # .../superintelligence
$repo   = Split-Path -Parent $module                          # the repository root

Push-Location $repo
try {
    $evidence = Join-Path $module 'out/evidence'
    $runDir   = Join-Path $module 'out/run'
    foreach ($dir in @($evidence, $runDir)) {
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    }

    if (-not $Long) {
        if (-not $PSBoundParameters.ContainsKey('Generations'))    { $Generations = 3 }
        if (-not $PSBoundParameters.ContainsKey('Episodes'))       { $Episodes = 1 }
        if (-not $PSBoundParameters.ContainsKey('UpdateThreads'))  { $UpdateThreads = 8 }
        if (-not $PSBoundParameters.ContainsKey('CheckpointEvery')){ $CheckpointEvery = 3 }
        if (-not $PSBoundParameters.ContainsKey('Workers'))        { $Workers = 4 }
        if (-not $PSBoundParameters.ContainsKey('Name'))           { $Name = "$Profile-quick" }
    }

    if (-not $Name) { $Name = "$Profile-$Generations" }
    $group = ($Name -replace '[^A-Za-z0-9._-]', '-')
    $csv = Join-Path $evidence "$group.csv"
    $log = Join-Path $evidence "$group.log"
    $bin = Join-Path $runDir 'weights.bin'

    # a stale CSV would make the summary below describe the previous run
    if (Test-Path -LiteralPath $csv) {
        Move-Item -LiteralPath $csv -Destination "$csv.prev" -Force
    }

    $trainArgs = @(
        '--reward-profile',   $Profile
        '--generations',      $Generations
        '--episodes',         $Episodes
        '--seed',             $Seed
        '--update-threads',   $UpdateThreads
        '--checkpoint-every', $CheckpointEvery
        '--replay-group',    $group
        '--metrics',          $csv
        '--save',             $bin
    )
    if ($Workers -gt 0) { $trainArgs += @('--workers', $Workers) }
    # The checkpoint this run's own --save writes. -Resume means "carry on from what this run last
    # saved", and $bin is that file - so a bare '--resume', which is what this used to append, was
    # both wrong (the flag takes a path, so the parser read off the end of the argument list and
    # answered ArrayIndexOutOfBoundsException, naming neither the flag nor what was missing) and
    # would have pointed at a different run's policy if it had parsed.
    if ($Resume)        { $trainArgs += @('--resume', $bin) }
    if ($Demos)         { $trainArgs += @('--demos', $Demos) }

    $argLine = ($trainArgs -join ' ')
    Write-Host ''
    Write-Host '  run     ' $Name -ForegroundColor Cyan
    Write-Host '  group   ' $group -ForegroundColor DarkGray
    Write-Host '  cmd     ./gradlew :superintelligence:train --offline --args="' $argLine '"' -ForegroundColor DarkGray
    Write-Host '  out     ' $csv -ForegroundColor DarkGray
    Write-Host '           ' $log -ForegroundColor DarkGray
    Write-Host ''

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $ErrorActionPreference = 'Continue'
    & ./gradlew :superintelligence:train --offline --console=plain "--args=$argLine" *>&1 |
        Tee-Object -FilePath $log
    $exit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $watch.Stop()

    if ($exit -ne 0) {
        Write-Host ''
        Write-Host "  [ERROR] training exited $exit after $([int]$watch.Elapsed.TotalSeconds)s - log at $log" -ForegroundColor Red
        exit $exit
    }

    Show-Summary -Csv $csv -Log $log -Seconds $watch.Elapsed.TotalSeconds -Bin $bin
}
finally {
    Pop-Location
}