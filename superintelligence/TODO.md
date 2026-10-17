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
      and both now carry the evidence that would locate one. **37 is localised and not diagnosed**:
      a fresh 2-generation run reproduced it - one of two worker recordings diverges at step 1421 of
      1422 with `rng baseDraws: replayed 745, recorded 747 (delta -2)` on a `SEARCH`, identically on
      both runs, so the streams parted in the generator and the recording drew *more*. `18` is still
      1 in 8 and now reports the same way. Both need `rngtrace` with `-Dspd.rngTrace`, whose tally
      names the call site; neither is guessed at. Evidence in `out/evidence/issue-37-smoke/`.
- [ ] Confirm the free-step budget is now the binding constraint rather than `stallLimit`. It was
      chosen from a 10-generation run, and the mask underneath it has changed.

## 2. Reward signals

- [x] ~~**`MENU_NOOP` has no emission site**~~ **It has had one all along, and now has a gate.**
      `SPDEnv.menuNoop()` - entered a sub-mode, stayed in it, advanced no engine time - feeds
      `RewardModel.step(..., menuNoop)`, which is where `ledger.add( MENU_NOOP, menuNoopPenalty )`
      lives. What was missing was the check, and the entry read as though the wiring were in doubt.
      `rewardcheck` drives the real model against a real world both ways and measures **MENU_NOOP
      firing once and paying -1.00**; reverting the emission fails it. `checkAMenuNoOpIsUnreachable`
      still measures that play cannot reach the state, which is the honest half.
- [ ] **Terms that never fire in a run.** `KILL`, `GOLD_GAIN` and `ITEM_PICKUP` have never been
      observed paying in a run. **All three are now proven to fire**: `rewardcheck` drives
      `Action.INTERACT` over a heap beside the hero and measures `ITEM_PICKUP` at 0.50 and
      `GOLD_GAIN` at 1.25, and a 2-generation trainer run reported 6-9 kills and 100-188 pickups
      per generation. What is left is the *coverage* question - does an untrained policy get there on
      its own - and the reason it cannot is `issues.md` 38: the hero cannot pick an item up off the
      cell it is standing on, because `ActionMapper.pickInteractCell` scans offsets 1..8 and
      `handleCell` refuses a non-adjacent cell. Fixing that turns `paritycheck` red
      deterministically, which is why it is filed as 38 rather than done.
- [ ] **Exploration is not separable from descent.** `reward.profile=depth_only` exists for this and
      has not been run since the free-step budget changed. `issues.md` 20 and 22 are the last two
      measurements of it, and both say it is a diagnostic rather than a training setting.
- [ ] **The shaping fade has never actually faded anything.** It is wired and measured - **0.6064 at
      depth 6** - but it starts at depth 3 and no run has passed floor 2, so every episode has
      priced every shaping term at 1.0. The fix is an agent that descends, not a retune.


Declined proposals are in `REJECTED.md` and appear nowhere else.
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
| 4.1 | Garbage collection and memory pooling | **Done, and measured.** The pooling `research.md:60` describes was already there - pooled observation buffers, action masks, network scratch, an 8,192-strong `Transition` pool - and `TODO.md` had it recorded as unstarted. `./gradlew :superintelligence:poolingaudit` is the measurement: **27.9 KB allocated per environment step, 1 GC in 4,000 steps, 2 ms pause.** What is left is the engine's own per-action churn, which is a `:core` change. |
| 4.2 | 1,000 simultaneous simulations | **Run, and it does not fit.** `out/evidence/scale-1000-sims.txt`: 10 workers x 100 episodes dies with `OutOfMemoryError` in a worker *and* the trainer. The binding constraint is **per-worker sample residency** - `max_sampled_per_episode` 2,048 held for all 100 episodes of a worker, 1.30 GB at the measured packed-grid size - not the trainer's capped buffer. The largest that runs is 10 x 20 = 200 episodes at **1,136-1,152 turns/s**, 32-38% of the machine, barrier 55-68% of wall. The fix is applying the cap as episodes arrive rather than after the generation completes. |
| 4.3 | IMPALA as the alternative learner | Declined on architecture; see `REJECTED.md`. |

## 5. Open engine and harness faults

- [ ] **`issues.md` 26**, the viewer spending 1 HP the recording does not contain. **Narrowed, not
      closed.** `ReplayPlayer` records cells now, and all 1,003 of them are identical on both sides at
      every step around the divergence, so it is neither the terrain, a trap, a plant, a heap, a
      transition, the fog, the visited set nor a mob. Evidence in `out/evidence/issue-26/`.
## 6. Stale references in the source documents

**Closed.** `research.md` and `docs.md` named classes that moved packages in v4.0.1 - `FogOfWar.java`
and `ShadowCaster.java` live in `tiles/` and `mechanics/` now - and one that never existed here at all,
`SPDAction.java`. Every reference is package-relative or fully qualified, and `doccheck` resolves all
45 of them against the four source roots, so the next upstream merge that moves a class turns a
document red rather than stale.
