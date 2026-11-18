# Handoff: full game-mechanic coverage by scenarios

State as of 2026-11-18. The plan `superintelligence/plans/2026-11-15-mechanic-coverage.md` is complete, Task 35 included.

## 1. Where this stands

- **Coverage:** `coveragecheck` reports **966 of 966 covered, 21 excluded**. There is no ratchet: `coverage/pending.txt` is deleted and any denominator key no scenario declares fails the gate as `uncovered: <key>`.
- **Gates on the closing tree:** full `scenariocheck` 889 scenarios (9.1 s), full `:desktop:scenarioview` 889 of 889 clean in the real viewer (312.8 s at 20 children), `:desktop:viewcheck` 37 of 37 clean (417.3 s at 20 children), `paritycheck` 32 recordings exact, `verifyall` green in the pre-commit hook.
- Evidence: `superintelligence/out/evidence/mechanic-coverage/` (`scenarioview-full.log`, `task-35-*.log`, `fist/`).

## 2. How the last two keys closed

`actors.mobs.YogFist$BurningFist` and `actors.mobs.YogFist$SoiledFist` diverged in the viewer because `HeadlessSprite` completed a fist's `attack` and `zap` inline, inside `act()`. The rendered game runs the completion as an animation callback after `act()` returns, and those two fists draw RNG after `super.act()`.

- `HeadlessSprite.stepScheduler()` wraps `Actor.headlessStep()` and runs a fist's parked completion straight after that actor's `act()`. `LevelPipeline.runToHeroReady`, `ReplayPlayer.driveToHeroReady` and `mobspritecheck` step through it. Any new caller of `Actor.headlessStep()` must use it too, or a fist's completion never runs.
- The zap alone was not enough: the melee slam had the same fault and showed up as an HP divergence at step 3 once the zap was fixed.
- Under the corrected draw order the default seed gave the level-1 hero no damaging hit in 20 swings, so the two scenarios are pinned to `SCN-0` and `SCN-2` (seed search with the family's `expect` temporarily a `fixture`).
- The other 887 scenario recordings are byte-identical before and after (`fist/replays-before.sha1`, `fist/replays-after.sha1`).
- Scope limit, deliberate: the deferral applies to `YogFist` only. `Monk`, `RipperDemon`, `Spinner` and `VaultBossElemental` also run code after `super.act()`; their recordings play clean in the viewer today, so they were left alone to keep every existing recording bit-identical. A future mob that draws after `super.act()` and diverges in `scenarioview` is this same fault.

## 3. Rulings that shaped the tree

- `WardSentry` is **excluded** as dead code: `Ward.toSentry()` is never called.
- `LastShopLevel` is **excluded**: `Dungeon.newLevel` has no case for it.
- `GradientCheck` uses `eps = 1e-2`: at `1e-3` most parameters sat in the noise band and a near-zero bias failed on measurement error.
- Engine logic committed with the user's approval: `ShopRoom.ChooseBag` `LinkedHashMap`, `Document.readPage` guard, plus observation-only getters (`Dungeon.observationDistance`, `MagesStaff.wand`, `CharSprite.isJumping`).
- `coveragecheck` prints `covered, <n> excluded` with no `pending` figure, as the plan's Task 35 specifies; the figure has nothing left to count.

## 4. Process notes learned the hard way

- `scenarioview` stamps `playback=ok` into the recordings it plays, so hash `out/scenarios` straight after `scenariocheck`, not after a viewer run, when comparing recordings.
- `ScenarioRunner` moves to the next seed only on `NoFixture`, never on a failed `expect`; `--seeds N` therefore cannot search past a failing seed without a `fixture` check.
- A killed pre-commit run leaves `.git/pre-commit-unstaged.patch` and `.git/pre-commit-unstaged/`; delete both before committing again.
- Agents sometimes write stray files into the main checkout. Check `git status` before every merge.
- Coverage figures are branch-local. Re-run `coveragecheck` on the merged tree and use its number in TODO and CHANGELOG.

## 5. What is left

Nothing in this plan. Open items unrelated to it are in `TODO.md` and `issues.md`. Old agent worktrees and branches under `.claude/worktrees/` are merged or abandoned and can be removed.
