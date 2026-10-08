<#
.SYNOPSIS
    Installs the repository's pre-commit hook.

.DESCRIPTION
    .git/hooks is not tracked by git, so a hook written there exists only on the machine that wrote it.
    This installs the tracked copy from hooks/ and makes it executable, which is what a fresh clone needs
    before the first commit.

    The hook checks two things, in order of cost:
      1. the version badge in README.md matches the newest released version in CHANGELOG.md, correcting
         and re-staging it if not;
      2. every correctness gate passes (gradlew :superintelligence:gates).

    Both abort the commit. The hook is not meant to be bypassed with --no-verify: a gate that can be
    skipped is a suggestion.

    Run it once per clone. Re-run it after pulling if hooks/pre-commit changes.
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $repoRoot 'hooks/pre-commit'
$target = Join-Path $repoRoot '.git/hooks/pre-commit'

if (-not (Test-Path $source)) {
    Write-Host "[ERROR] no tracked hook at $source" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path (Join-Path $repoRoot '.git'))) {
    Write-Host "[ERROR] $repoRoot is not a git repository." -ForegroundColor Red
    exit 1
}

# Copied rather than symlinked: a symlink into the worktree breaks the moment the worktree is moved,
# and the failure mode is a hook that silently stops existing.
Copy-Item $source $target -Force

# Windows has no executable bit, and git for Windows honours a hook whose shebang resolves through its
# bundled sh, so this is a no-op here and matters on every other platform.
& git update-index --chmod=+x -- $source 2>$null | Out-Null

Write-Host "[OK]   installed $target"
Write-Host "[INFO] the hook runs :superintelligence:gates and syncs the README version badge."
Write-Host "[INFO] remove it with: Remove-Item .git/hooks/pre-commit"