# Learning measurement and viewer fidelity - plan

Written 2026-10-14, after three defects were found and fixed in one session and **no training run has been
measured since any of them**. This file is the ordered plan for finding out whether the fixes helped, and
for the one defect that is still open and still costs a green corpus.

Status: **not started.** Phase A is first because the user chose to fix `issues.md` 26 before swapping
the corpus fixture; Phases B and C follow, in that order, because B's three runs must be uncontended.

Companions: [`issues.md`](issues.md) sections 20, 22, 25 and 26; [`README.md`](README.md) "Nothing has
reached depth 2"; [`testing-guide.md`](testing-guide.md) for what a gate means here.

---

## Where we are

| | |
| --- | --- |
| Fixed and committed | run group ids, script migration, shared-`Network` update bug, `WAIT` charged as a free step, `:desktop:replay` not forwarding `spdWorldTrace`, `viewdiff` keying |
| `verifyall` | green - 24 gates plus `:desktop:playbackcheck` at 23 |
| `viewcheck` | **19 of 20**, red on the one fixture that was substituted |
| Post-fix training measurement | **none exists** |

Both fixed bugs are in the learning path and both were severe. `issues.md` 25 made every gradient wrong:
`ShardedUpdate` handed each shard the *master* `Network` rather than a copy, so six threads shared one
object's parameters, gradient accumulators and layer scratch. Every run in this project's history used
`--update-threads` above 1, so **every checkpoint on disk is void.**

---

## Four corrections to earlier claims in this session

Recorded because each was stated as fact and none survived measurement.

**The critic's terminal bootstrap is not a defect.** It was reported here as "proven" and the highest-value
unfixed finding. It is a **deliberate, gated decision**:
`GaeCheck.checkTerminalAndTruncatedDifferOnlyAtTheEnd` asserts that a stall bootstraps -
`if (Math.abs( asStall[n-1] - boot ) > 1e-4f ) fail(...)` - with the comment *"A stalled episode is a
truncation, and this is where that has to show up."* The supporting figure was also wrong: a fixed point of
**-300,000** came from iterating one isolated state, while the real critic is linear
(`Network.java:107` passes `tanh=false`), shares trunk gradients with the policy, and Adam normalises. That
number does not describe what the trainer does. Treat the question - *what does a charged terminal penalty
mean when the critic can bootstrap past it* - as open design, not as a proven bug.

**The viewer is not three engine turns behind.** `viewdiff`'s per-step world comparison appeared to show a
deficit that oscillated and never closed. It was snapshot timing: `ReplayPlayer` snapshots per frame and
`WorldTrace` per step, so the same step number is not the same moment on both sides. HP agrees at every
engine-clock value both sides recorded. `viewdiff` now compares the settled record of a step - the last one -
which is what removed the false reading.

**The fix was tried and does nothing.** `HeroSprite.read()` skipping its animation under a new
`GameScene.replayFidelity()` flag - set only by `ReplayLauncher`, the same shape as `headlessGameplay()` -
left the viewer verdict byte-identical: `DIVERGED at step 95 - hp is 6, recording says 7`. Reverted. It
touches `:core` for no gain, and `instructions.md` 12.3 keeps an engine logic change out of a commit unless
it is the fix.

**The viewer is not one engine turn behind either.** `issues.md` 26 carried
`at step:94: hero field turn / expected 92.0 / actual 91.0` and the reading "the viewer is one engine turn
behind after a scroll read". **That was the previous artefact one level up.** The viewer halts on the
divergence, so its last `hero` record was written mid-step at `turn=91.0`; comparing that against the
headless's *settled* record at `turn=92.0` reads as a turn deficit. With `-PspdContinueOnDiverge` the
viewer runs all 100 steps and `turn` and `pos` agree at **every** step from 86 to 99. The only field that
differs is `hp`, by exactly 1 at steps 94, 95, 97, 98 - and the totals match, so it is not extra damage,
it is the same damage landing one step later. See `issues.md` 26 for the per-step table.

---

## The blocker: there is no error bar on any comparison

`SeedPool.next()` returns `""` - a fresh random seed - with probability `RANDOM_FRACTION = 0.1f`, in both
the locked and the all-random phase. The design is deliberate and documented in the code: without the leak
the early single-seed phase produces a policy that has memorised one map. `research.md` asks the agent to
generalise, so this is correct.

The consequence is not. Two runs of the identical command disagreed:

| | `default-fresh-15` | `default-fresh-15-all` |
| --- | --- | --- |
| generation 0 meanScore | -3,088.84 | -3,090.29 |
| bestScore over the run | -1,276.0 | -3,048.0 |
| bestDepth | **2** | **1** |
| endings | death 4, stalled 76 | stalled 80 |

Generation 0 is collected before any update, from a policy initialised from that seed, so nothing in the
code under test can explain a difference there. **No comparison in this project currently has an error bar**,
which means the two fixes cannot be said to have helped.

---

## Phase A - `issues.md` 26: make step 94 observable

The recording is `superintelligence/test-replays/duelist-death-last.replay`, seed `DUELIST-LONG-16`, DUELIST,
100 steps, `DEATH` on the final step. It verifies 100 of 100 headlessly and halts in the viewer:

```
DIVERGED at step 95 - USE/0 in SLOT: hp is 6, recording says 7
```

Confirmed with `VIEWCHECK_JOBS=1` over 257s, so not the parallel-playback artifact. What is measured:

- `baseDraws` and `baseFingerprint` agree at every step both sides recorded, 0 through 93.
- Step 94 is the first step the headless spends draws on the scroll, and it is the step the viewer halts on.
- A tripwire on `SlotAction.use` shows both sides doing `ScrollOfRecharging action=READ` at hp 7.
- `viewdiff`, with its keying fixed, put it at a turn deficit. **That reading was wrong** - see the
  fourth correction above. What it actually is:

  ```
  [DIFF] at step:94: hero (settled) field hp
    expected: 7/20
    actual:   6/20
  ```

  Only `hp` differs. Same `pos`, same `turn`, same `ready`, same buffs, same inventory, and a
  **byte-identical `roster`** - every mob's hp, state, energy and buffs. Not the scroll read, not its
  animation, not the RNG, not the engine clock, not the playback speed (identical at `spd.fast=800`
  and at the gate's 40). Damage that leaves no trace in the roster is not dealt by a mob.

  From `spd.trace`, each `USE/* SLOT` costs exactly 1 HP and each `USE/* WORLD` costs none. The viewer
  applies step 94's and the headless applies it at step 96 instead; totals agree, so the same damage
  lands one step apart.

**Outcome: A1 done, A2 done, A3 not done.** The cause is localised to a damage source the roster does
not carry, and **cells are the one channel that cannot be compared** - `ReplayPlayer` builds its
`WorldSnapshot` with `.cells( false )` (frames would flood), so the viewer writes 0 cell records against
headless's 100. The next instrument is cells on `step` records only.

### Phase B and C - done, and they found more than the plan expected

Phase B ran: three repeats of one 15-generation config on a fixed seed. The band is **meanScore +/- 16
over 15 generations**, `bestScore` spans 1,595 points, and **`bestDepth` is 1 or 2 at chance**. Phase C
then ran and **died at generation 22** of 30 - `TrainerWorkers.pushWeights` cannot write its 14 MB blob
to a worker pipe - so the 30-generation measurement was never obtained. Both are `issues.md` 27 and 28.

The measurement the plan wanted turned out to be the wrong next step. What the noise floor was for was to
judge whether the fixes helped, and having it made a different number the important one: **98-99% of every
episode ends STALLED**, at `meanTurns` 122-140 against `stallLimit = 120`, already 99% at generation 0.
That is worth more than any before/after comparison, and it is now the next thing to establish.

### A1 - a continue-past-divergence affordance

`-Dspd.replayContinueOnDiverge=1` in `ReplayPlayer`, alongside the six `spd.*` diagnostics already
there (`spd.trace`, `spd.rngTrace`, `spd.worldTrace`, `spd.fast`, `spd.autoClose`, `spd.autoCloseOnDiverge`).
Off by default. Records the divergence and keeps applying and tracing steps.

Also: **flush the world and RNG traces on normal end, not only on `halt()`.** `flushWorldSnapshot()` is
called from `halt()` alone (`ReplayPlayer.java:1247`), which is why the rendered side of the trace stops one
step short of the fault every time.

Supervisor-side only - `desktop/src/main/java/.../desktop/replay/`. No engine change; the `HeroSprite.read()`
route is dead.

### A2 - read the answer

With the viewer running past the halt, produce the trace pair through the documented commands and diff it:

```
./gradlew :superintelligence:worldtrace -PworldArgs="<replay> <headless.txt>"
./gradlew :desktop:replay --args="--file <replay>" -PspdWorldTrace=<viewer.txt> \
          -PspdContinueOnDiverge -PspdFast=800 -PspdMute -PspdHidden
./gradlew :superintelligence:viewdiff -PviewDiffArgs="<headless.txt> <viewer.txt>"
```

Then compare the RNG traces from step 94 onward for the **first `baseDraws` or `baseFingerprint`
divergence**, which names the step the streams part rather than the step the symptom surfaces.

**Success:** a named step and field after 94.

**The result that would change the diagnosis:** no divergence at all past 94, which would mean the halt
itself is the fault and the fix belongs in `ReplayPlayer`'s settle rather than in the game.

### A3 - gate, then fix

A case asserting the two sides agree through step 94. If A2 does not yield a cheap fix, the alternative is a
**recorded case** marking the scroll-read path as known-divergent, so the corpus stops claiming coverage it
does not have. `playbackcheck` cannot cover this: `ReplayPlayer` headless agrees with `verify`, so only the
renderer sees it, and `viewcheck` is outside `gates` because it needs a display.

---

## Phase B - the noise floor

Three runs of one 15-generation `default` config, same seed, **one at a time**. Never concurrently: they
would contend, and each is the measurement.

```
./superintelligence/train.ps1 -Generations 15 -Name floor-<n>
```

Each writes `out/evidence/floor-<n>.csv` and `.log`. Report per run as it lands, not in a batch at the end.

Recorded per generation: meanScore, bestScore, bestDepth, endings, valueLoss. Aggregates banded across the
three.

**Success:** a stated band, in the form *"meanScore over 15 generations varies by +-X; bestDepth is 1 or 2
across repeats."* That sentence is the deliverable and belongs in `issues.md` as a finding about the method,
not in this file alone.

---

## Phase C - the measurement that matters

One 30-generation `default` run under a fresh group id, judged against Phase B's band. This is the first
honest read on whether the shared-`Network` fix and the `WAIT` fix moved anything, and it is the run the
milestone in `research.md:40` is measured against.

**Success:** aggregates outside the band, and an answer - with a number and an error bar - to *"did the fixes
help?"*

**Not expected:** depth 2 or better. `issues.md` 20 and 22 record that nothing pays under `depth_only` and
under `default` alike, and both were measured on the broken gradient. This plan makes the next measurement
interpretable; it does not make the policy better.

---

## Housekeeping

- **`ValueScale`'s header is stale.** It states the value head is `tanh`-bounded and cannot fit targets of
  250x its range. `Network.java:107` passes `tanh=false`; that was fixed. The comment describes a fault
  that no longer exists and is the first thing a reader of `valuescale` meets.
- One sweep for **withdrawn claims still standing**. The "23s -> 11s per generation, 2.1x faster" figure was
  retracted in `d6651ee2c`; the only controlled comparison is the 6-worker one in `issues.md` 25, **804 ->
  908 turns/s, +13%**.
- **`REST` is still charged as a free step** - 6 of 158 zero-time steps in a measured recording. It is not a
  one-liner: `Hero.act` charges `TIME_TO_REST` only when the scheduler re-picks the hero, and
  `LevelPipeline`'s rest backstop returns `STALLED` after eight drain steps first. Deciding the drain's rest
  handling is a prerequisite, not this change.

---

## What "done" looks like

- `viewcheck` 20 of 20, or a documented and gated case for the scroll-read path.
- A noise-floor number recorded in `issues.md` as a finding about the project's method.
- One post-fix 30-generation run, with an error bar, answering whether the fixes helped.

---

## Open, and not for the agent to decide

- **Phase A may end in "not fixable cheaply".** If so, stop and record it rather than spend the session on
  it. The choice then is between accepting the gap and a longer investigation.
- **Every checkpoint on disk is void.** Phase C starts clean and overwrites `out/run/weights.bin`. Nothing
  resumable survives, which is correct.
- **The `default` profile has never been measured on a correct gradient over a long enough run to show a
  trend.** Phase B says how long "long enough" is; Phase C is the first run that can answer the question
  this project exists to answer.