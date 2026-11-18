# Handoff: full game-mechanic coverage by scenarios

State as of 2026-11-18. The plan `superintelligence/plans/2026-11-15-mechanic-coverage.md` is complete, Task 35 included.

## 1. Where this stands

- **Coverage:** `coveragecheck` reports **966 of 966 covered, 21 excluded**. There is no ratchet: `coverage/pending.txt` is deleted and any denominator key no scenario declares fails the gate as `uncovered: <key>`.
- **Gates on the closing tree:** full `scenariocheck` 889 scenarios (9.1 s), full `:desktop:scenarioview` 889 of 889 clean in the real viewer (312.8 s at 20 children), `:desktop:viewcheck` 37 of 37 clean (417.3 s at 20 children), `paritycheck` 32 recordings exact, `verifyall` green in the pre-commit hook.
- Evidence: `superintelligence/out/evidence/mechanic-coverage/` (`scenarioview-full.log`, `task-35-*.log`, `fist/`).
- Follow-ups of 2026-11-18 (plan `superintelligence/plans/2026-11-18-coverage-followups.md`, evidence `out/evidence/followups/`): the deferral covers every mob, `mobspritecheck` 19 cases and `playbackcheck` 29 refuse a step that bypasses `HeadlessScheduler`, full `scenarioview` 889 of 889 (318.2 s), `viewcheck` 37 of 37 (417.8 s), `paritycheck` 32 exact. `./gradlew test` is not a check in this project (`AGENTS.md`, Validation).

## 2. How the last two keys closed

`actors.mobs.YogFist$BurningFist` and `actors.mobs.YogFist$SoiledFist` diverged in the viewer because `HeadlessSprite` completed a fist's `attack` and `zap` inline, inside `act()`. The rendered game runs the completion as an animation callback after `act()` returns, and those two fists draw RNG after `super.act()`.

- `HeadlessScheduler.step()` wraps `Actor.headlessStep()` and runs the acting mob's parked `attack` or `zap` completion straight after its `act()`, for every mob (first fists only, generalised on 2026-11-18). `LevelPipeline.runToHeroReady`, `ReplayPlayer.driveToHeroReady` and `mobspritecheck` step through it. The state lives outside `HeadlessSprite.java` because that file is the text template for 21 generated stand-in sprites, so a static field there exists once per generated class.
- The zap alone was not enough: the melee slam had the same fault and showed up as an HP divergence at step 3 once the zap was fixed.
- Under the corrected draw order the default seed gave the level-1 hero no damaging hit in 20 swings, so the two scenarios are pinned to `SCN-0` and `SCN-2` (seed search with the family's `expect` temporarily a `fixture`).
- The other 887 scenario recordings are byte-identical before and after (`fist/replays-before.sha1`, `fist/replays-after.sha1`).
- The hero's own `attack` and `operate` completions still run inline; `ReplayPlayer.finishHeroAnimation` matches that on the viewer side. Generalising the deferral changed none of the 889 scenario recordings.
- Audited 2026-11-18 whether inline is safe for the hero: inside `Hero.act` every `sprite.attack`/`operate` is followed only by `return`, and `act()` returns straight after its action branch, so no draw or state read can fall between the call and its completion. Every two-argument call that passes a `Callback` (35 sites in 22 core files, hero abilities, weapons, Combo, SkeletonKey and mobs) was read for code after the callback: the only trailing statements are `hero.busy()`, `return`, sounds, a Geomancer boulder whose attack callback only idles, and `Combo`'s `detach()` of a `RiposteTracker` that nothing else reads. No order change is observable. Two-argument callbacks run inline in both the trainer and the viewer, so no viewer gate can see a future ordering fault there: re-run this audit after every upstream merge (the listing script and its output are in `out/evidence/followups/callback-trailers.*`).
- A parked completion is dropped only when `act()` throws; nothing in `env/` catches that, so the episode is already over, and `step()` clears the queue before it drains anything. Not a defect.
- The constant-pool scan matches the exact name `headlessStep`. Behaviour covers the rest: with `ReplayPlayer` bypassing the scheduler however it is spelled, `scenarioplayback` (in `verifyall`) fails on both fist recordings, and with `LevelPipeline` bypassing it the Rat, Ghoul and RedShaman order cases fail.

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

Nothing in this plan. Open items unrelated to it are in `TODO.md` and `issues.md`.

The 18 agent worktrees and 37 `worktree-agent-*` branches were removed on 2026-11-18. Every branch is in `out/evidence/followups/agent-branches.bundle` (`git fetch <bundle> <branch>:<branch>` restores one; names and SHAs in `agent-branches.txt`), and the three worktrees with uncommitted work left `agent-*.patch` files and `agent-*-untracked/` copies beside it. `out/` is gitignored, so the bundle is local to this machine. It holds nothing master lacks: checked 2026-11-18, every commit on the 37 branches is in master by patch, subject or content, except what was deliberately superseded (Task 15's quickslot shim, dropped for Task 16's at merge 6, and branch-local coverage counts in docs). Seven changelog entries those merges dropped (shop, items, artifacts, Tier-2 talents, subclasses, armour abilities, Imp statue) were restored, and the alchemy-spell, halls and vault families, which never had one, were written (`CHANGELOG.md`, Unreleased).
