<#
.SYNOPSIS
    Installs the repository's git hooks.

.DESCRIPTION
    .git/hooks is not tracked by git, so a hook written there exists only on the machine that wrote it.
    This installs the tracked copies from hooks/ and makes them executable, which is what a fresh clone
    needs before its first commit.

    One hook, not two. Only pre-commit is installed.

      pre-commit - two version badges, then every correctness gate passes, via `gradlew verifyall`. That
        is :superintelligence:gates plus :desktop:playbackcheck - the aggregate rather than the
        Superintelligence half, so that a commit touching the desktop replay player is tested by the
        replay player's own checks. Aborts the commit.

        The badges are two, because the repository versions two subjects that must never be compared to
        each other. The root README's `version-` badge is the game's, and it is checked against the
        newest `v*` git tag rather than against anything written by hand, so it follows an upstream merge
        without anyone editing it. The root README's `superintelligence-` badge and the module's own
        README `version-` badge are both checked against `superintelligence/CHANGELOG.md`. Each is
        corrected and re-staged rather than refused, which is what this hook has always done for one
        badge. With no tags in the clone the game badge is skipped with a warning rather than guessed at.

    There used to be a pre-push hook as well, running :desktop:viewcheck. It is no longer installed.

      It was a reporting hook, never blocking, and its own header had been arguing for its own removal
      since the viewer fidelity issue it referenced was fixed: a hook that needs a GL context cannot run
      on a build machine, and one that cannot run everywhere gets deleted by the first person it annoys.
      What changed it was speed. viewcheck used to take ~220s, which is long enough to be worth skipping;
      it now defaults to 8 concurrent children and takes ~36s for the same 17 recordings (see
      ViewCheck.parallelism), so the argument that it was too slow to be worth running no longer holds -
      and a hook that fires on every push of a repository whose pushes are rare is paying a real cost for
      a number that is also available by running one command.

      The script is still tracked at hooks/pre-push if you want it on a machine that always has a
      display. Copy it to .git/hooks/ yourself, or run it by hand:
          gradlew :desktop:viewcheck

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

$installed = @('pre-commit')

# A pre-push installed by an earlier run of this script is removed rather than left behind, so that
# re-running the installer after this change actually takes effect. Only the copy under .git/hooks is
# touched; the tracked script at hooks/pre-push stays.
$stale = Join-Path $repoRoot '.git/hooks/pre-push'
if (Test-Path $stale) {
    Remove-Item $stale -Force
    Write-Host "[OK]   removed $stale (the pre-push viewer gate is no longer installed)"
}

foreach ($name in $installed) {
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

Write-Host "[INFO] pre-commit checks two version badges (game vs newest v* tag, module vs its own"
Write-Host "[INFO] changelog) and runs 'gradlew verifyall'."
Write-Host "[INFO] the rendered viewer gate is NOT installed; run it by hand with:"
Write-Host "[INFO]     gradlew :desktop:viewcheck"
Write-Host "[INFO] re-install it with: Copy-Item hooks/pre-push .git/hooks/pre-push"
Write-Host "[INFO] remove the hook with: Remove-Item .git/hooks/pre-commit"