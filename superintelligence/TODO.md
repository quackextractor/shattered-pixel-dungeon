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
`out/evidence/descend-30.csv`.

- [ ] Close `issues.md` 37. **`issues.md` 18 closed** on the guide-page fix and 8 green runs out of
      8; 37 did not close with it and reproduced on a new seed with the identical signature
      (`rng baseDraws diverged at step 1124 of 1125, delta -2`). What is narrowed: two verifications
      of one file agree exactly, and `paritycheck` is green across 32 episode boundaries in one
      process, so the leak is reached by the **trained** policy and lives in the process that made
      the recording. The next measurement is `RandomTrace.siteLog()` inside the worker, per episode,
      diffed against a verification of the same file. Evidence: `out/evidence/issue-37-recheck.txt`.

## 2. Reward signals

- [ ] **Coverage of the terms that fire.** `KILL`, `GOLD_GAIN` and `ITEM_PICKUP` are all proven to
      fire and a trainer run reports 6-9 kills and 100-188 pickups per generation. What is left is
      whether an untrained policy gets there on its own. **The blocker is no longer `issues.md` 38**,
      which is closed: the cause was a guidebook page read out of the process-wide `Document`, and
      `Document.resetPages()` is now called on every reset. The blocker is now the **committed
      corpus** - `viewcheck` reports 7 of 20 recordings diverging under the own-cell `INTERACT`
      semantics, and regenerating it would destroy the fixture `issues.md` 26 is diagnosed against.
      Measured, with the numbers, in `REJECTED.md`.
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
