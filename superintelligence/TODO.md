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

- [ ] Close `issues.md` 37 and 18 together. Both are "a recording the trainer wrote does not replay",
      both are in the recording path, and `18` is the one that makes `gates` flaky. Nothing else in
      this file can be judged until a green `gates` means the same thing twice.
- [ ] Confirm the free-step budget is now the binding constraint rather than `stallLimit`. It was
      chosen from a 10-generation run and the guard behind it has never been re-measured.
- [ ] Once 18 and 37 are closed, re-measure. The run above is the first one long enough to say
      anything, and it says the objective is not moving.

## 2. Reward signals

- [ ] **`CRAFTED`, `CRAFTED_MEAT_PIE` and `MENU_NOOP` have no emission site**, for measured reasons:
      brewing is `AlchemyScene.craftItem`, which needs a renderer, and `rewardcheck` measures that
      every sub-mode action either advances the clock or leaves the mode. The gate on the second is a
      gate on the *absence*, so it goes red the day a no-op exists.
- [ ] **Three reward terms have weights that are Java literals** - `CURSE_REMOVED` 1.50,
      `EQUIP_TOO_STRONG` -2.00 and the rest of the family - where every other weight is a key in
      `superintelligence.properties`. `configcheck` would hold them the moment they are.
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

- [ ] **`issues.md` 26**, the viewer spending 1 HP the recording does not contain. Cells are the one
      channel the viewer does not record, so the damage source is still unlocated.
- [ ] **`Mob.holdAllies` uses an unseeded `Collections.shuffle`.** One line, applied and measured,
      and **uncommitted** because `instructions.md` §12.3 keeps engine logic changes out of a commit
      until they are proposed. What it changes in a game is the gameplay RNG stream on any floor that
      holds an ally.
- [ ] **Sound pitch still draws from `Random`** at 11 sites. Symmetric across both environments, so
      nothing separates them today. Worth moving on its own merits.
- [ ] **A native or ONNX runtime for the learner.** Not attempted, and the numbers say the learner
      is not yet the binding constraint. See `REJECTED.md`.

## 6. Stale references in the source documents

`research.md` and `docs.md` name classes that moved packages in v4.0.1, including `FogOfWar.java` and
`ShadowCaster.java`, which exist but not where the documents say.