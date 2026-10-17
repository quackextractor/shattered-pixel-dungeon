# Superintelligence TODO

Open work only. Completed items are in git history and `CHANGELOG.md`, with their measurements;
findings are in `issues.md`; proposals that were declined or deferred are in `REJECTED.md`.

**Order is by what unblocks learning first.**

## 1. Teach it to descend

`meanScore` moved **+134.49 +/- 52.4** over the 30-generation re-measurement - about 2.6 sigma, and the
first run in this project's history to clear its own error bar. **It is not a descent.** `depth>=2` is
**0 of 960 episodes**, `bestDepth` is 1 in 30 of 30 generations, and the objective-only score moved
+65.69 +/- 57.9, which is not significant. The policy got better at what the shaping pays for and did
not get closer to the stairs. Evidence: `out/evidence/remeasure-30.csv`.

- [ ] **The next measurement is not another reward weight.** The objective terms are working and the
      shaping is what moved. The candidate is the policy's inability to survive the walk to the
      stairs on floor 1, and `issues.md` 20's arithmetic - under a profile that pays only for depth,
      a policy that cannot yet descend learns to die cheaply - applies to any further change to that
      balance.
- [ ] Close `issues.md` 37 and 18 together. Both are "a recording the trainer wrote does not replay",
      and both carry the evidence that would locate one. 37 is localised and not diagnosed: one of two
      worker recordings diverges at step 1421 of 1422 with `rng baseDraws: replayed 745, recorded 747
      (delta -2)` on a `SEARCH`, identically on both runs, so the streams parted in the generator and
      the recording drew *more*. 18 is still 1 in 8. Both need `rngtrace` with `-Dspd.rngTrace`, whose
      tally names the call site; neither is guessed at. Evidence in `out/evidence/issue-37-smoke/`.

## 2. Reward signals

- [ ] **Coverage of the terms that fire.** `KILL`, `GOLD_GAIN` and `ITEM_PICKUP` are all proven to
      fire and a trainer run reports 6-9 kills and 100-188 pickups per generation. What is left is
      whether an untrained policy gets there on its own, and `issues.md` 38 says why it cannot: the
      hero cannot pick an item up off the cell it stands on.
- [ ] **Exploration is not separable from descent.** `reward.profile=depth_only` exists for this and
      has not been run since the free-step budget changed. `issues.md` 20 and 22 are the last two
      measurements of it, and both say it is a diagnostic rather than a training setting.
- [ ] **The shaping fade has never faded anything.** Wired and measured - 0.6064 at depth 6 - but it
      starts at depth 3 and no run has passed floor 2. The fix is an agent that descends, not a
      retune.

## 3. Missing subsystems

| # | Task | Document | Size |
| --- | --- | --- | --- |
| 3.1 | libGDX trainer UI in the `desktop` module | research.md:16, docs.md:10 | L |
| 3.3 | Live play with real-time reward/term monitoring | docs.md:10 | M |

      turns collected, no OOM.** Evidence in `out/evidence/scale-1000-sims-streamed.log`.

## 4. Open engine and harness faults

- [ ] **`issues.md` 26**, the viewer spending 1 HP the recording does not contain. Narrowed, not
      closed. `ReplayPlayer` records cells now, and all 1,003 of them are identical on both sides at
      every step around the divergence, so it is neither the terrain, a trap, a plant, a heap, a
      transition, the fog, the visited set nor a mob. Evidence in `out/evidence/issue-26/`.
