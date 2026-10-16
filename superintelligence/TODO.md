# Superintelligence TODO

Open work only. Completed items are in git history and `CHANGELOG.md`, with their measurements;
findings are in `issues.md`; proposals that were declined or deferred are in `REJECTED.md`.

**Order is by what unblocks learning first**, which for a long time has not been the harness.

## 1. Nothing has learned anything

The goal is to descend several floors, or win. Neither has happened, and **the 30-generation run
says why**: `meanScore` moved **+1.6 points** between the first and last fifteen generations against a
per-generation standard deviation of **98 to 138**. That is noise, and the movement is two orders of
magnitude smaller than the noise.

**The band this repository had been quoting - +/-16 over fifteen generations - was measured on a
different configuration and is 6 to 8 times too tight for this one.** Any change has to clear ~100
points over fifteen generations to mean anything. `bestDepth` on its own is not evidence either way.

- [ ] **Re-measure, and this is the first run that can.** Four things the 30-generation run lacked
      are now in: the recurrent chunk is actually reached (`samplercheck` measures 1,113 retained
      steps in 70 contiguous runs of up to 16 rather than 70 singletons), the trunk is 2.55x smaller,
      sub-mode entry is masked unless the hero has something to use (`STALLED` 14 of 40 -> 2 of 40),
      and the alchemy pot is reachable with two of `docs.md`'s terms paying. **None of them has been
      measured on a training run, and the claim that the objective now moves is untested.**
- [ ] Close `issues.md` 37 and 18 together. Both are "a recording the trainer wrote does not replay",
      and both now carry the evidence that would locate one: every step records `baseDraws` and
      `baseFingerprint`, and `verify` names the step and the delta before it compares anything else.
      Neither is diagnosed, and `18` is still the one that makes `gates` flaky.
- [ ] Confirm the free-step budget is now the binding constraint rather than `stallLimit`. It was
      chosen from a 10-generation run, and the mask underneath it has changed.

## 2. Reward signals

- [x] **`CRAFTED` and `CRAFTED_MEAT_PIE` have emission sites.** `env.AlchemyAction` brews over the
      game's own `Recipe.findRecipes` and `Action.ALCHEMY` reaches it, so `AlchemyScene` and its
      renderer are not on the path. Measured: a meat pie is brewed with energy 20 -> 14,
      `CRAFTED` 25.0 and `CRAFTED_MEAT_PIE` 100.0.
- [x] **The weights that were Java literals are settings.** Forty-seven of them, including
      `CURSE_REMOVED` 1.50, `EQUIP_TOO_STRONG` -2.00 and the curriculum fade depths.
      `configcheck` is 14 cases and 87 keys, and its new case reads `.env.example` as well.
- [ ] **`MENU_NOOP` has no emission site**, for the reason already measured rather than a new one:
      every sub-mode action either advances the clock or leaves the sub-mode, and `modecheck` now
      covers all seven modes and the claim still holds.
- [ ] **Terms that never fire in a run.** `KILL`, `GOLD_GAIN` and `ITEM_PICKUP` have never been
      observed paying in a run. `KILL` is wired and gated, so the question is whether an untrained
      policy ever gets there, not whether the term can move.
- [ ] **Exploration is not separable from descent.** `reward.profile=depth_only` exists for this and
      has not been run since the free-step budget changed. `issues.md` 20 and 22 are the last two
      measurements of it, and both say it is a diagnostic rather than a training setting.
- [ ] **The shaping fade has never actually faded anything.** It is wired and measured - **0.6064 at
      depth 6** - but it starts at depth 3 and no run has passed floor 2, so every episode has
      priced every shaping term at 1.0. The fix is an agent that descends, not a retune.

## 3. Missing subsystems

| # | Task | Document | Size |
| --- | --- | --- | --- |
| 3.1 | libGDX trainer UI in the `desktop` module | research.md:16, docs.md:10 | L |
| 3.3 | Live play with real-time reward/term monitoring | docs.md:10 | M |
| 3.4 | Type totals in the dashboard | docs.md:41 | S |

3.2, the desktop replay viewer, is done. Why 3.1 and 3.3 have not been started is in `REJECTED.md`.

## 4. research.md sections not addressed

| # | Section | Status |
| --- | --- | --- |
| 4.1 | Garbage collection and memory pooling | Unaddressed. Not started. |
| 4.2 | 1,000 simultaneous simulations | Never run at scale. The pool sizes to the machine. |
| 4.3 | IMPALA as the alternative learner | Declined on architecture; see `REJECTED.md`. |

## 5. Open engine and harness faults

- [ ] **`issues.md` 26**, the viewer spending 1 HP the recording does not contain. **Narrowed, not
      closed.** `ReplayPlayer` records cells now, and all 1,003 of them are identical on both sides at
      every step around the divergence, so it is neither the terrain, a trap, a plant, a heap, a
      transition, the fog, the visited set nor a mob. Evidence in `out/evidence/issue-26/`.
- [ ] **A native or ONNX runtime for the learner.** Not attempted, and the numbers say the learner
      is not yet the binding constraint. See `REJECTED.md`.
- [ ] **The sound-pitch draws.** **Declined, not deferred** - ten sites, and moving them invalidates
      every committed recording including the one `issues.md` 26 is diagnosed against. The reason and
      the trigger are in `REJECTED.md`.

`Mob.holdAllies` is closed: `Random.shuffle(candidatePositions)` is what the tree has and it landed
in commit `b4f717992`. `ENGINE-CHANGES.md` §7 and `CHANGELOG.md` both recorded it as uncommitted and
are corrected.

## 6. Stale references in the source documents

`research.md` and `docs.md` name classes that moved packages in v4.0.1, including `FogOfWar.java` and
`ShadowCaster.java`, which exist but not where the documents say.