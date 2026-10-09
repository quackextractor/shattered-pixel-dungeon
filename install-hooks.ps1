<#
.SYNOPSIS
    Installs the repository's git hooks.

.DESCRIPTION
    .git/hooks is not tracked by git, so a hook written there exists only on the machine that wrote it.
    This installs the tracked copies from hooks/ and makes them executable, which is what a fresh clone
    needs before its first commit or push.

    Two hooks, split by cost and by what they can run:

      pre-commit - the version badge in README.md matches the newest released version in CHANGELOG.md,
        correcting and re-staging it if not; then every correctness gate passes, via
        `gradlew verifyall`. That is :superintelligence:gates plus :desktop:playbackcheck - the
        aggregate rather than the Superintelligence half, so that a commit touching the desktop replay
        player is tested by the replay player's own checks. Aborts the commit.

      pre-push - :desktop:viewcheck, which plays the corpus through the real rendered viewer. About 90
        seconds and it needs a real GL context, which is why it is not in pre-commit. Reports, and does
        NOT block: it is red by design while the viewer fidelity issue is open
        (superintelligence/ISSUE-viewer-frame-drift.md), and its failing set varies between runs, so it
        is not a sound pass/fail gate yet. Its own header says what to change when that issue closes.

    The pre-commit hook is not meant to be bypassed with --no-verify: a gate that can be skipped is a
    suggestion.

    Run it once per clone. Re-run it after pulling if anything under hooks/ changes.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Test-Path (Join-Path $repoRoot '.git'))) {
    Write-Host "[ERROR] $repoRoot is not a git repository." -ForegroundColor Red
    exit 1
}

foreach ($name in @('pre-commit', 'pre-push')) {
    $source = Join-Path $repoRoot "hooks/$name"
    $target = Join-Path $repoRoot ".git/hooks/$name"

    if (-not (Test-Path $source)) {
        Write-Host "[ERROR] no tracked hook at $source" -ForegroundColor Red
        exit 1
    }

    # Copied rather than symlinked: a symlink into the worktree breaks the moment the worktree is moved,
    # and the failure mode is a hook that silently stops existing.
    Copy-Item $source $target -Force

    # Windows has no executable bit, and git for Windows honours a hook whose shebang resolves through its
    # bundled sh, so this is a no-op here and matters on every other platform.
    #
    # -C and a relative pathspec, because neither was here before and both were load-bearing. git update-index
    # without -C runs against the current directory, so from anywhere but a repository it exits 128 and
    # changes nothing; an absolute Windows pathspec is rejected the same way. The exit code was discarded
    # into Out-Null, so the mode was never set and the failure was invisible - which matters more than it
    # sounds: git skips a hook without the executable bit on POSIX, so pre-push would have committed
    # cleanly and simply never run on linux or mac, and never once said so.
    & git -C $repoRoot update-index --chmod=+x -- "hooks/$name" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[WARN] could not set the executable bit on hooks/$name in the git index." -ForegroundColor Yellow
        Write-Host "[WARN] git skips a hook that is not executable on linux and mac, so it would never"
        Write-Host "[WARN] run there. Fix it with: git update-index --chmod=+x -- hooks/$name"
    }

    Write-Host "[OK]   installed $target"
}

Write-Host "[INFO] pre-commit syncs the README version badge and runs 'gradlew verifyall'."
Write-Host "[INFO] pre-push runs ':desktop:viewcheck' and reports; it does not block while"
Write-Host "[INFO] superintelligence/ISSUE-viewer-frame-drift.md is open."
Write-Host "[INFO] remove them with: Remove-Item .git/hooks/pre-commit, .git/hooks/pre-push"