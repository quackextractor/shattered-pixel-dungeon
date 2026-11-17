# Handoff: full game-mechanic coverage by scenarios

State as of 2026-11-17, end of the long session. Written so an agent with no prior context can resume.

## 1. Where this stands

- **Master** `90c1cdcac`: clean.
- **Coverage:** `coveragecheck` reports **962 of 966 covered, 4 pending, 21 excluded**.
- **Gates:** full `:desktop:viewcheck` passed (6m58s), `verifyall` passed, `paritycheck` 32 recordings exact.
- Merged: every plan task from 1 to 34 except Task 35, plus F1, the `Document.readPage` fix, the `ShopRoom.ChooseBag` fix (issue 51 closed), the `GradientCheck` step fix and Task S.

## 2. The five pending keys, and why

| Key | Why it is pending | What would close it |
| --- | --- | --- |
| `actors.mobs.YogFist$BurningFist` | Its deferred zap completes after the rest of `act()` in the rendered path, so its recording diverges (`hp 966 vs 969`) | A replay-stage deferral in `HeadlessSprite`/`ReplayPlayer` so the zap lands at the same point; or accept as a documented divergence |
| `actors.mobs.YogFist$SoiledFist` | Same mechanism, `rng baseFingerprint` at step 1 | Same |
| `items.artifacts.TalismanOfForesight` | Its scry maps cells; the action space can only aim at adjacent cells, which `Dungeon.observe` already maps, so its unique effect is unreachable | A far-target seat in the action space (a Task 19 follow-up), or its passive warning as an observable |
| `items.quest.ImpStatue` | Ruled pending: it has no action and every property it exposes is a constant | Vault-score coverage in Task 30's style |

Task 35 cannot run to completion (it deletes `pending.txt`) until those five are resolved or formally excluded. Do not delete `pending.txt` before then.

## 3. Rulings that shaped the tree

- `WardSentry` is **excluded** as dead code: `Ward.toSentry()` is never called.
- `LastShopLevel` is **excluded**: `Dungeon.newLevel` has no case for it.
- `GradientCheck` uses `eps = 1e-2`: at `1e-3` most parameters sat in the noise band and a near-zero bias failed on measurement error.
- Engine logic committed with the user's approval: `ShopRoom.ChooseBag` `LinkedHashMap`, `Document.readPage` guard, plus observation-only getters (`Dungeon.observationDistance`, `MagesStaff.wand`, `CharSprite.isJumping`).

## 4. Process notes learned the hard way

- Subagent model order (from the global rules): `opencode-go/deepseek-v4.1-flash`, then `opencode-go/glm-5.3-flash-free`, then tier-2 models. **Probe before use**; a dead endpoint auto-retries for hours. Beam free tier exhausts and stalls silently.
- Agents sometimes write stray files into the main checkout (`ItemScenarios.java`, `SubclassScenarios.java`, `WandOfWarding.java` were found there). Check `git status` before every merge.
- A killed pre-commit run leaves `.git/worktrees/<wt>/pre-commit-unstaged.patch` and `pre-commit-unstaged`; delete both before committing again.
- Merge conflicts cluster in `ScenarioCatalog.java`, `coverage/pending.txt`, `CHANGELOG.md`, `TODO.md`. Resolution rule: keep every registration, remove exactly the task's keys, re-measure coverage after the merge, never trust a branch's figure.
- Coverage figures are branch-local. Re-run `coveragecheck` on the merged tree and use its number in TODO and CHANGELOG.

## 5. Reading order for a new agent

1. `CLAUDE.md` and `superintelligence/AGENTS.md` (binding).
2. `superintelligence/plans/2026-11-15-mechanic-coverage.md` (the plan; Task 35 is the close-out).
3. `superintelligence/testing-guide.md` (gates).
4. `.superpowers/sdd/2026-11-15-mechanic-coverage/progress.md` (the ledger, every ruling).
5. This file.

## 6. If asked to finish

The shortest path to 966: the blast-radius-style far-target seat for the talisman, then the replay-stage zap deferral for the two fists. Each is a small, self-contained change with an obvious red mutation. Then Task 35: delete `pending.txt`, drop the ratchet from `Coverage`/`CoverageCheck`, run the full `scenarioview`, `viewcheck` and `verifyall`.
