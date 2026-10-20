# Superintelligence TODO

Open work only. Completed items are in git history and `CHANGELOG.md`, with their measurements;
findings are in `issues.md`; proposals that were declined or deferred are in `REJECTED.md`.

**Order is by what unblocks learning first.**

## 1. Teach it to descend

**Every measurement in this section predates 0.11.0 and is void.** It was taken with the policy
gradient descending the wrong sign, tanh-squashed logits, a clip that stopped both sides, BPTT through
`h` cut at one step, no terminal flag on death, the critic's baseline in the wrong units, and
`StairPotential` and `EXIT_BEACON` pointing at the **upstairs**. "The reward is not the lever" and "the
observation is the thing" are conclusions about that trainer, not this one. The observation window is now
centred on the hero, which was one of the two levers named below.

- [ ] **Re-measure on 0.11.0 before anything else.** One 30-generation run of the shipped
      configuration from a fresh network, same method as `CHANGELOG.md` 0.10.0's, evidence under
      `out/evidence/`. No pre-0.11.0 checkpoint is comparable.
- [ ] **Depth 2's entrance room is behind a hidden door** on all 4 depth-2 floors sampled, so the
      stair potential is unreachable from the entrance until the door is found (`Phi` is 0 on an
      unreachable cell). Measure how often that holds across seeds before reading a depth-2 shaping
      curve.

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

`issues.md` 26 closed on the ordered draw log, and `:desktop:viewcheck` is **20 of 20**. The 0.11.0
review batch closed every must-fix and most of the rest of `out/evidence/ponytail-review-2026-10-19.md`;
still open:

- [ ] **The observation draws invisible mobs.** `ObservationEncoder` writes `ENEMY`/`ALLY`/`NEUTRAL`
      for any char on a visible cell, `invisible > 0` included; only `THREATENING` checks it. The game
      hides that sprite. Review finding 12, last bullet.

- [ ] **Headless sprite casts on Eye, DM300, Goo and Ghoul.** Their logic casts `sprite` to a concrete
      sprite class that the headless stand-in is not. Being fixed separately; `mobspritecheck` should
      gain a case per mob when it lands.
- [ ] **The viewer still plays an unknown hero class as a Warrior.** `ReplayLauncher` and
      `ReplayPlayback.heroClass()` fall back to `HeroClass.WARRIOR`; headless `verify` refuses it since
      0.11.0. Refuse it in `:desktop` too.
- [ ] **`restartcheck` copies the viewer's restart sequence rather than calling it.**
      `ReplayController.restart` lives in `:desktop` and needs a live scene, so the gate replays its
      engine calls in order. A change to the real sequence will not fail the gate.
- [ ] **Files over 500 lines:** `diag/RewardShapingCheck.java` (546),
      `diag/VerifyCheck.java` (504).
