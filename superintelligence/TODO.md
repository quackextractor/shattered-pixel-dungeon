# Superintelligence TODO

Open work only. Completed items are in git history and `CHANGELOG.md`, with their measurements;
findings are in `issues.md`; proposals that were declined or deferred are in `REJECTED.md`.

**Order is by what unblocks learning first.**

## 1. Teach it to descend

`meanScore` moved **-182.32 +/- 56.4** over the 30-generation re-measurement — about 3 sigma in the
**wrong** direction — and `depth>=2` is **0 of 960 episodes** again. `meanTurns` rose 542.79 →
**758.65** with deaths flat, so the policy is learning to live longer rather than to descend. The
shaped terms improved by about +72 an episode and the 216 extra turns consumed all of it.
`STAIR_POTENTIAL` telescopes, so a rising net means the hero finishes closer to the exit than it
starts: **the policy does not fail to want the stairs, it fails to walk to them.** The earlier
`+134.49 +/- 52.4` did not reproduce, so a 30-generation run on this configuration does not clear its
own error bar in either direction. Numbers and method: `CHANGELOG.md`; evidence
`out/evidence/descend-30.csv`. **A third run since then is flat at `+2.39 +/- 44.6`** and five times
the stair potential is also flat, so the entry below has been rewritten around what the measurement
actually rules out.

- [ ] **Teach it to walk to the stairs.** `issues.md` 37 is closed and the divergence entries are
      gone, so nothing stands between a run and a measurement any more - and two measurements
      have been taken. The shipped configuration is **flat**: `meanScore` **+2.39 +/- 44.6** over
      30 generations, so the `-182.32 +/- 56.4` this file quoted did not reproduce either, and
      `depth>=2` is **0 of 960 episodes**. `reward.stair_potential_weight` at **10.0** - five times
      the shipped weight, the only dense term that pays for walking towards the exit - is also
      **0 of 960**, with the objective window **5 sigma worse**. Numbers and method:
      `CHANGELOG.md` and `REJECTED.md`; evidence `out/evidence/descend-30-ab.txt`.

      So the reward is not the lever, and the next question is the observation: `StairPotential` is
      the breadth-first distance to stairs the hero has usually never seen, and `EXIT_BEACON` lights
      only once they have been in view. The term pays for progress towards something the observation
      does not carry, which is why five times more of it moves nothing. The lever is an ego-centred
      grid, or a beacon that survives the window edge, and both are declined in `REJECTED.md` with
      their own triggers - so the next step is a measurement of **how often the policy's episodes
      end nearer the stairs than they started**, against a random walk over the same floors. If the
      policy is already better than random at closing the gap, the reward is being read and the
      problem is elsewhere; if it is not, the observation is the thing.

## 2. Reward signals

- [x] ~~**Coverage of the terms that fire.**~~ **Closed by measurement.** `KILL`, `GOLD_GAIN` and
      `ITEM_PICKUP` all fire under an **untrained** policy - 87 and 30 kills, 0 and 3 gold, 1441 and
      1743 pickups across two 32-episode generations - so the item and combat shaping is reachable
      without training and the blockers were never wiring. `TALENT_UPGRADE` and `CRAFTED` never fire
      because the first talent point arrives at level 2 and no episode stood beside an alchemy pot;
      both are driven and paid in `rewardcheck` and `freeactioncheck`, so they are unreachable rather
      than dead, and both become live the moment the policy descends. Numbers and method:
      `CHANGELOG.md`; evidence `out/evidence/untrained-coverage.txt`.
- [ ] **The shaping fade has never faded anything.** Wired and measured - 0.6064 at depth 6 - but it
      starts at depth 3 and no run has passed floor 2. The fix is an agent that descends, not a
      retune.

## 3. Missing subsystems

None. The two that were listed here - the libGDX trainer dashboard and the live reward monitor -
are **declined**, with their reasoning and the trigger that would change it, in `REJECTED.md`.
`TODO.md` carries open work only, and an entry that appears in both files is read twice and closed
once.

## 4. Open engine and harness faults

None. `issues.md` 26 was the last, and it closed on the ordered draw log: the headless stand-in for
a particle emitter was compensating on the wrong stream, so the trainer ran one value ahead of the
rendered game from the first scroll read. `:desktop:viewcheck` is **20 of 20**.
