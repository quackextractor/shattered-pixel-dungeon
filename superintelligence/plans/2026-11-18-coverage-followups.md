# Coverage Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the six risks left open by commits `1490124c2` and `263faaf0f`: the fist-only deferral, the missing headless gate for it, the unguarded `Actor.headlessStep()` callers, the unrun `./gradlew test`, the missing green run after the mutations, and the stale agent worktrees and branches.

**Architecture:** The completion deferral moves out of the `HeadlessSprite` template into a new `HeadlessScheduler` and applies to every mob: the acting mob's own `attack` and `zap` completion runs straight after its `act()` returns, as the rendered game does. Headless probes in `mobspritecheck` pin the order, a constant-pool scan refuses any scheduler step that bypasses `HeadlessScheduler`, and the viewer gates run last on the final tree.

**Tech Stack:** Java 17+, Gradle (`:superintelligence`, `:desktop`), Git Bash commands (in PowerShell one per line, never `&&`).

**Spec:** the user's request of 2026-11-18 ("Resolve all of the risks") against the risk list in the closing report of that session, restated in the table below; binding rules `CLAUDE.md`, `superintelligence/AGENTS.md`, `superintelligence/testing-guide.md`, `superintelligence/HANDOFF.md`.

| # | Risk | Task |
| --- | --- | --- |
| R1 | Deferral covers `YogFist` only; `Monk`, `RipperDemon`, `Spinner`, `VaultBossElemental` also run code after `super.act()` | 1 |
| R2 | No headless gate guards the ordering | 1 |
| R3 | No green viewer run after the mutations | 4 |
| R4 | `./gradlew test` was not run | 3 |
| R5 | A new caller of `Actor.headlessStep()` would skip the deferral | 2 |
| R6 | Old agent worktrees and branches remain | 5 |

Found before planning (evidence `superintelligence/out/evidence/followups/`):

- `Monk.act` tests `focusCooldown <= 0` after `super.act()` and `Monk.spend` lowers it, so an inline attack completion grants `Focus` one act earlier headless than rendered. Read from the code; no existing recording reaches it.
- `superintelligence/build.gradle` generates 21 stand-in sprite classes by copying `HeadlessSprite.java` as text, so a `static` field in that file exists once per generated class. A completion parked by `HeadlessRipperSprite` would never be drained by `HeadlessSprite.stepScheduler()`. The scheduler state therefore cannot live in the template.
- `./gradlew test` fails at configuration: `SDK location not found` for `:android:testDebugUnitTest`. `./gradlew test -x :android:test` exits 0; every module reports `test NO-SOURCE`. No module has a test source set; the gates are the tests.
- 18 agent worktrees, 3 with uncommitted files; 37 `worktree-agent-*` branches, 32 not ancestors of master. `perf/throughput` is not an agent branch.

## Global Constraints

- No code comments in `superintelligence/`. No emoji. Logs use `[OK]` / `[WARN]` / `[ERROR]`.
- 500-line limit per file outside `core`, `SPD-classes`, `desktop`.
- No `core` / `SPD-classes` edits are committed by this plan.
- Conventional Commits 1.0.0. Never `--no-verify`. No attribution trailer of any kind. Stage by explicit path.
- Every committed recording in `test-replays/` must still play without re-recording: `corpusplayback`, `viewcheck` and `paritycheck` stay green.
- Never weaken a scenario, assertion, gate or tolerance.
- One gate at a time, each under `timeout`. Evidence goes to `superintelligence/out/evidence/followups/`.
- After three failed attempts on one step: stop and report the exact errors.
- Non-goal: the hero's own `attack` and `operate` completions stay inline. They were not in the risk list and `Hero` is not a `Mob`.

## Review Focus

1. **A completion parked by a generated stand-in is never drained.** The template is copied per sprite class. Pinned by the `Ghoul` probe in Task 1 (its sprite is a generated class).
2. **A completion asked for outside a scheduler step is dropped.** It must run at once, as today. Pinned by `an attack outside a step completes at once` in Task 1.
3. **A mob that is not the acting actor gets deferred.** Only the acting mob holds the scheduler in the rendered game. Pinned by `a mob attacking during another's act completes at once` in Task 1.
4. **An `act()` that throws leaves deferral armed.** Pinned by `a step that threw leaves nothing parked` in Task 1.
5. **An existing recording shifts.** Pinned by the hash comparison in Task 1 Step 6 and by `paritycheck` and `corpusplayback`.

---

### Task 1: `HeadlessScheduler` and the deferral for every mob (R1, R2)

**Files:**
- Create: `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/headless/HeadlessScheduler.java`
- Create: `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/diag/ActOrderCases.java`
- Modify: `.../headless/HeadlessSprite.java` (drop `stepScheduler`, `completionAwaitingActEnd`, the `YogFist` branches)
- Modify: `.../env/LevelPipeline.java`, `.../diag/MobSpriteCheck.java`, `desktop/.../replay/ReplayPlayer.java` (call `HeadlessScheduler.step()`)

**Interfaces:**
- Produces: `public static boolean HeadlessScheduler.step()` (wraps `Actor.headlessStep()`, same return meaning); `static void HeadlessScheduler.afterAct( Char ch, Runnable completion )` (package-private; parks when a step is in progress, `ch instanceof Mob` and `ch == Actor.currentActor()`, otherwise runs it at once); `static void ActOrderCases.run()`.

- [ ] **Step 1: Write the failing checks.** `ActOrderCases.run()` is called from `MobSpriteCheck.main` after `SpriteCastCases.run()`. Probes are subclasses overriding `act()` (sets a flag for its duration) and `onAttackComplete()` / `onZapComplete()` (records whether the flag was set). Cases, each on a fresh run:
  - `a Rat's attack lands after its act` (plain `HeadlessSprite`): completed, and not inside `act()`.
  - `a Ghoul's attack lands after its act` (generated stand-in): same assertions.
  - `a RedShaman's bolt lands after its act`: same assertions on `onZapComplete`.
  - `an attack outside a step completes at once`: `probe.sprite.attack( cell )` called directly; completed is true on return.
  - `a mob attacking during another's act completes at once`: an instigator probe whose `act()` calls a second probe's `sprite.attack( cell )`; the second completed inside the instigator's `act()`.
  - `a step that threw leaves nothing parked`: a probe whose `act()` throws, caught by the case; a direct `sprite.attack( cell )` afterwards completes at once.
- [ ] **Step 2: Run red.** `timeout 300 ./gradlew.bat :superintelligence:mobspritecheck`. Expected: `[ERROR]` naming the Rat, Ghoul and RedShaman cases as completed inside `act()` (only fists are deferred today).
- [ ] **Step 3: Implement `HeadlessScheduler`, route `HeadlessSprite.attack( int )` and `zap( int )` through `afterAct`, and switch the three callers to `HeadlessScheduler.step()`.** `step()` clears its in-progress flag in a `finally`; parked completions run in FIFO order after `Actor.headlessStep()` returns, and when any ran the result is `Actor.currentActor() != null`. The `Spinner`, `Eye` and `DM100` branches of `zap` become the completion's body.
- [ ] **Step 4: Run green.** Same command. Expected: `[OK]     item-created mobs: 18 checks passed` (12 before, 6 added).
- [ ] **Step 5: Regenerate the scenario recordings.** `timeout 600 ./gradlew.bat :superintelligence:scenariocheck`. Expected: `889 recorded, verified twice and written, 0 expected reach gaps, 0 known failures`. A scenario that fails only because its pinned seed's draws moved is re-pinned by seed search, never weakened.
- [ ] **Step 6: Compare recordings.** `sha1sum` of `out/scenarios/*.replay` against `out/evidence/mechanic-coverage/fist/replays-after.sha1`. Expected: identical. A recording that differs is listed in the ledger and must play clean in Task 4's `scenarioview`; if it does not, stop and debug, do not re-pin around it.
- [ ] **Step 7: Corpus.** `timeout 900 ./gradlew.bat :superintelligence:paritycheck` (expected `32 recording(s) over 8 seed(s) replayed exactly, twice`), then `timeout 900 ./gradlew.bat :desktop:corpusplayback` (expected `[OK]     headless playback: 28 checks passed`).
- [ ] **Step 8: Commit after Task 4's viewer gates are green on this tree** (Task 4 Steps 1-2 run here): `fix(headless): land every mob's attack and bolt after its act`, with CHANGELOG `### Fixed`, the `mobspritecheck` row of `testing-guide.md` and `HANDOFF.md` section 2.

### Task 2: Refuse a scheduler step that bypasses `HeadlessScheduler` (R5)

**Files:**
- Create: `.../diag/SchedulerStepScan.java`
- Modify: `.../diag/RenderDrawCheck.java` (`forEachClass` and its visitor become package-visible), `.../diag/MobSpriteCheck.java`, `desktop/.../replay/PlaybackCheck.java`

**Interfaces:**
- Consumes: `HeadlessScheduler` from Task 1.
- Produces: `public static List< String > SchedulerStepScan.directCallers( Class< ? >... anchors )`: names of the classes found beside each anchor whose constant pool holds the UTF8 entry `headlessStep`, without `HeadlessScheduler`.

- [ ] **Step 1: Write the checks.** `MobSpriteCheck`: `every scheduler step goes through HeadlessScheduler`, scanning beside `HeadlessScheduler.class`. `PlaybackCheck`: `checkTheViewerStepsThroughTheHeadlessScheduler`, scanning beside `ReplayPlayer.class`. Each fails naming every offending class.
- [ ] **Step 2: Implement `SchedulerStepScan.directCallers`** on `RenderDrawCheck.forEachClass`.
- [ ] **Step 3: Run green.** `mobspritecheck` expected `19 checks passed`; `timeout 900 ./gradlew.bat :desktop:corpusplayback` expected `29 checks passed`.
- [ ] **Step 4: Mutation, twice.** `LevelPipeline` calling `Actor.headlessStep()` directly: `mobspritecheck` red naming `LevelPipeline`. `ReplayPlayer` likewise: `corpusplayback` red naming `ReplayPlayer`. Revert each with `git checkout --`.
- [ ] **Step 5: Commit** `test(gates): refuse a scheduler step that bypasses the headless scheduler`, with the `mobspritecheck` and `playbackcheck` rows of `testing-guide.md` and a CHANGELOG `### Added` line carrying both red lines.

### Task 3: The test task (R4)

**Files:** Modify `superintelligence/testing-guide.md` (one row: `./gradlew test`).

- [ ] **Step 1:** `timeout 300 ./gradlew.bat test -x :android:test` on the final tree. Expected: exit 0, every `:test` task `NO-SOURCE`.
- [ ] **Step 2:** Add the row: the bare command needs an Android SDK (`SDK location not found`), `-x :android:test` is the form that runs here, and no module has a test source set, so `verifyall` is where logic is tested. Committed with Task 2's commit.

### Task 4: Viewer gates on the final behaviour (R3)

- [ ] **Step 1:** `timeout 1500 ./gradlew.bat :desktop:scenarioview` (about 6 min, nothing else running). Expected: `rendered playback: 889 recordings played clean in the real viewer`. Log: `followups/scenarioview-full.log`.
- [ ] **Step 2:** `timeout 1500 ./gradlew.bat :desktop:viewcheck` (about 7 min). Expected: `rendered playback: 37 recordings played clean in the real viewer`. Log: `followups/viewcheck.log`.
- [ ] **Step 3:** `verifyall` runs in the pre-commit hook of each commit; read its `all gates passed` line.

### Task 5: Remove the agent worktrees and branches (R6)

- [ ] **Step 1: Back up.** `git bundle create superintelligence/out/evidence/followups/agent-branches.bundle <every worktree-agent-* branch>`, `git bundle verify` it, and write `agent-branches.txt` (name and SHA). For each of the three dirty worktrees write `git diff HEAD` to `<name>.patch` and copy its untracked files beside it.
- [ ] **Step 2: Remove.** `git worktree remove --force` (twice-forced for locked ones) for every worktree under `.claude/worktrees/`, `git worktree prune`, `git branch -D` for every `worktree-agent-*` branch. `perf/throughput` and `master` are not touched.
- [ ] **Step 3: Verify.** `git worktree list` prints one line; `git branch` prints `master` and `perf/throughput`; `git status` is clean. Update `HANDOFF.md` section 5 (folded into the last commit).
