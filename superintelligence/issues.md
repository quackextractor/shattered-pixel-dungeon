# Open issues

Closed entries live in `CHANGELOG.md` with their measurements. Rejected and deferred proposals live in
`REJECTED.md`. What follows is what is still open.

| # | |
| --- | --- |
| 18 | `rolloutcheck` is the one flaky gate. Re-measured twice: 1 in 8 both times, and the signature now names the RNG stream rather than a cell |
| 20 | under `depth_only` the run converges on dying early rather than on descending |
| 22 | with the ping-pong closed, nothing the policy does pays |
| 26 | the viewer spends 1 HP on a step the recording does not |
| 27 | no comparison in this project has an error bar, and `bestDepth` is a coin flip |
| 31 | open items recovered from the viewer-fidelity and parity documents |
| 37 | one recording in two does not reproduce, and it is the trainer's half |
| 38 | a recording verifies in a fresh JVM and not in a process that has played an episode |

---

18. `rolloutcheck` fails about one run in thirty, and the recording it made verifies on the next

    **Re-measured 2026-10-16: the rate and the signature both moved.** Eight consecutive runs of
    `rolloutcheck` alone, `--rerun-tasks` each time:

    ```
    run 1 ok    run 2 ok    run 3 ok    run 4 FAILED    run 5 ok    run 6 ok    run 7 ok    run 8 ok

          a recording made from a random-seed episode does not verify: position: replayed 135, recorded 103 at step 54
          verification covered 55 of 60 steps; a short match is not a reproduction
    ```

    Three things are worth stating precisely.

    - **It is `checkRandomSeedRecordingVerifies`, and not one of the four cases added to this gate
      since.** `checkAnAbsentSeedFlagIsAccepted` also runs a random-seed episode but only asserts that
      steps came back, so it cannot fail on a divergence. The failing case is the original one.
    - **The divergence is on position, not on engine time.** `issues.md` 26 and 37 are both a
      one-turn or one-point deficit; this is the hero at a different **cell** from step 54 of 60, so
      the world itself differs rather than the accounting for it. That makes it a different fault from
      37 even though both are "a recording the trainer wrote does not replay".
    - **1 in 8 is not 1 in 30, and 8 runs cannot tell the two apart** - the 95% interval on one
      failure in eight runs runs from about 1 in 25 to 1 in 3. What can be said is that the original
      figure was measured on a gate that ran one random-seed episode and this one runs two, so the
      exposure is at least double.

    **Re-measured again once every step records its RNG draw count: the rate is unchanged and the
    signature now names the stream.** Eight consecutive runs of `rolloutcheck` alone, `--rerun-tasks`
    each time: **7 of 8 green**, the same 1-in-8 this entry recorded above, so the new comparison has
    not made the gate flakier. The one failure reports:

    ```
    a recording made from a random-seed episode does not verify: rng baseDraws diverged at step 57:
      replayed 290, recorded 298 (delta -8)
    ```

    and a second run gave `step 57: replayed 397, recorded 401 (delta -4)`. Both are the *same* case —
    `checkRandomSeedRecordingVerifies` — and both are reported before any position, health, clock or
    inventory comparison, because `ReplayIO.verify` now checks `baseDraws` first.

    Across three separate re-measurements after the draw count landed - eight consecutive runs, then
    two single runs from `verifyall` - the gate has failed three times in about twenty invocations,
    which is the same rate the two eight-run sweeps measured. The signature has been stable across
    those failures: a negative delta, at step 57 of 60, in the same case.

    **What that buys is a direction.** A negative delta means the replay consumed **fewer** draws
    than the recording did, by a multiple of four, at a step late in a sixty-step episode. The old
    signature — a different cell at step 54 — said the world differed and nothing about which side
    moved. This one says the two sides had already parted in the generator, and that the recording
    drew more.

    **What it does not do is diagnose it.** The count is cumulative and says nothing about which
    call site made the extra draws; `RandomTrace.attribute()` answers that and is a separate tool.
    The two deltas being multiples of four is suggestive and is not a conclusion — four and eight are
    also what a single mob's `attackDelay`-sized decision costs, and this entry records
    `issues.md` 33's own lesson that a turn-cost story is the shape of the last confident wrong answer.

    **This makes `gates` itself flaky**, which is a cost the fix has to weigh and this entry now
    records. A gate that goes red for a real reason beats one that hides it, and `rolloutcheck` was
    kept inside `gates` on that basis; but a red `verifyall` a ninth of the time trains people to
    re-run it, and a re-run that comes back green is the exact habit this repository's gates exist to
    break. **Until this is diagnosed a green `gates` is a claim about the run it just did, not about
    the next one.**

    The original entry follows, kept for the A/B against the base tree and for what was ruled out.
    Found while closing 17, and unrelated to it. `rolloutcheck` is in `gates`, and `gates` failed on one
    ```
    **It is not caused by the change that was in flight.** A/B'd against the base tree, the same
    | tree | runs | failures |
    Same signature, same rate, different random draws.
    **Why it is intermittent, and it is not a seed lock.** `RolloutCheck`'s first two cases record an
    **Not investigated, and stated as open rather than guessed at.** A recorded position and a replayed
    **What is not wrong:** `paritycheck` verifies 32 recordings across seeds in one process and passes,

## 20. Under `depth_only` the run converges on dying early rather than on descending - **OPEN, and it is a finding rather than a defect**
Measured on the same 30-generation run (30 generations, 16 workers x 4 episodes, `reward.profile=depth_only`,
| generation | meanScore | meanDepth | bestDepth | meanTurns | ended |
| --- | --- | --- | --- | --- | --- |
| 0 | -7616.8 | 1.00 | 1 | 795 | death 58, turn limit 6 |
| 6 | -3726.3 | 1.02 | 2 | 231 | death 64 |
| 11 | -3735.1 | 1.02 | 2 | 276 | death 64 |
| 20 | -3471.4 | 1.00 | 1 | 209 | death 64 |
| 29 | -4156.2 | 1.00 | 1 | 363 | death 64 |
**`bestDepth 2` in 5 of 31 generations, all of them before generation 12, and 100% of episodes dead from
**Why, and it is arithmetic rather than a mystery.** With nothing but the objective paying, the only
**So `depth_only` is a diagnostic profile and not a training default.** It answers the question the
**The next measurement is a curriculum rather than a switch**, and the machinery for it already exists and

## 22. With the ping-pong closed, nothing the policy does pays - **OPEN, and it is the honest measurement**
The third of the three runs, and the first one where the reward function means what it says. Same seed
| | run 1: nothing fixed | run 2: free-step bound | run 3: bound + ping-pong closed |
| --- | --- | --- | --- |
| `bestScore` | -2,579 | **+9,540** | -2,132 |
| generations with a positive best | 0 | **21 of 30** | **0 of 30** |
| `bestDepth 2` | 5 of 31 | 28 of 30 | **2 of 30** |
| last generation's endings | death 100% | death 49% / stalled 52% | death 2 / **stalled 62** |
**Run 2's numbers were the exploit, not progress, and closing it removed all of them.** A positive best
**What the policy does now, from its best recording** (`JEA-FKU-WZS`, CLERIC, 84 steps, reproduced exactly):
```
terms
  NO_PROGRESS              -264.00
  TURN_COST                -104.00
  DEATH                   -2500.00
```
15 moves north, 13 interacts, 9 searches, 4 inventory opens: real actions, no free-step flood, no descent to
**So the profile is not a training setting, and this is the proof rather than the suspicion.** Every route
**The next run is `default`, not `depth_only`,** with the bound and the depth payment in place. That is the

## 26. The viewer spends 1 HP on a step the recording does not - **OPEN, diagnosed, not fixed**
Found while replacing `duelist-death-last` after the `WAIT` fix. It is the ninth recording this
### What it is
The replacement recording - `duelist-death-last.replay`, seed `DUELIST-LONG-16`, DUELIST, 100 steps,
```
DIVERGED at step 95 - USE/0 in SLOT: hp is 6, recording says 7
```
Confirmed with `VIEWCHECK_JOBS=1` over 257s, so it is **not** the documented parallel-playback
### Where it is not
**The randomness is identical.** Compared per step on both sides, `baseDraws` and
```
step  headlessTurn  viewerTurn  deficit  h-hp  v-hp
 88          87.0        84.0      3.0  9/20  12/20
 89          87.0        87.0      0.0  9/20   9/20
 90          90.0        87.0      3.0  8/20   9/20
 91          90.0        90.0      0.0  8/20   8/20
```
**It looks like a three-turn deficit and is not one.** The readings oscillate - behind at 88, level
### Where it is, measured
```
[DIFF] at step:94: hero (settled) field hp
  expected: 7/20
  actual:   6/20
```
**Only `hp` differs.** The whole step-94 `hero` record is otherwise identical on the two sides - same
Producing that line needs the documented pair of commands and nothing else, which it did not before -
```
./gradlew :superintelligence:worldtrace -PworldArgs="<replay> <headless.txt>"
./gradlew :desktop:replay --args="--file <replay>" -PspdWorldTrace=<viewer.txt> ...
./gradlew :superintelligence:viewdiff -PviewDiffArgs="<headless.txt> <viewer.txt>"
```
Step 94 is the first step the headless spends draws on the scroll at all, and it is the step the
```
Char.attack:502=1   Char.attack:503=1   Char.drRoll:705=2   Char.hit:647=2   Char.hit:663=2
Hero.drRoll:648=2   Hero.drRoll:655=2   Snake.damageRoll:50=2
Scroll.readAnimation:206=1
HeadlessSprite$Inert.start:251=1
```
A tripwire on `SlotAction.use` shows both sides doing **the same thing on the same step**:
```
[SLOTUSE] item=ScrollOfRecharging action=READ hp=7      <- viewer
[SLOTUSE] item=ScrollOfRecharging action=READ hp=7      <- headless
```
So the item, the action and the health going in are identical, and the difference is inside the read.
### The animation hypothesis is WRONG, and was tested
**`CharSprite.read()` being empty while `HeroSprite.read()` plays an animation is where an earlier
1. A tripwire on `Scroll.readAnimation`, run through both the real viewer and the headless verifier,
   ```
   **Identical.** The read is entered and left at the same engine time on both sides, so it cannot be
2. `HeroSprite.read()`'s `animCallback` does `idle(); ch.onOperateComplete();`. The viewer fires
3. **The fix was implemented and made no difference.** `HeroSprite.read()` was made to skip its
   ```
   The change was reverted. It touches `:core` for no gain, and `instructions.md` 12.3 says an engine
**What is left is that the viewer applies 1 HP of damage the recording does not contain**, at step 94
### The turn deficit that was here earlier was not a fault
An earlier version of this entry read the divergence as **the viewer being one engine turn behind**,
The viewer's world trace is written on halt, and the viewer halted on step 94. Its last `hero` record
Once the viewer was made to keep playing past the divergence (see the last section), it ran all 100
```
step  headless hp/turn   viewer hp/turn
  92       7/20  91.0        7/20  91.0
  93       7/20  91.0        7/20  91.0
  94       7/20  92.0        6/20  92.0   <- hp only
  95       7/20  93.0        6/20  93.0   <- hp only
  96       5/20  94.0        5/20  94.0
  97       5/20  95.0        2/20  95.0   <- hp only
  98       4/20  96.0        2/20  96.0   <- hp only
  99       0/20   0.0        1/20  97.0   <- headless dead
```
The totals agree too - both sides go from 7 HP at step 92 to 5 HP at step 96 - so this is not the viewer
```
step  action      headless hp   viewer hp
  88  USE/0 SLOT      9/20        9/20
  89  USE/0 WORLD     9/20        9/20
  90  USE/0 SLOT      8/20        8/20
  91  USE/0 WORLD     8/20        8/20
  92  USE/0 SLOT      7/20        7/20
  93  USE/0 WORLD     7/20        7/20
  94  USE/0 SLOT      7/20        6/20   <- the fault
```
**Each `USE/* SLOT` costs exactly 1 HP, and each `USE/* WORLD` costs none.** The viewer applies the
**Not a speed artefact.** Measured identically at `spd.fast=800` (2600 frames) and at the gate's
### Where the damage comes from: cells are no longer the missing channel

`ReplayPlayer` built its `WorldSnapshot` with `.cells(false)`, so the rendered side carried no
terrain, trap, plant, heap, transition, fog, visited-set or line-of-sight record. It records them now,
and `replay.ViewerWorldSnapshotBuilder` owns the decision rather than the desktop module.

Measured on `duelist-death-last.replay` with cells on both sides for the first time, comparing every
cell record at each step around the divergence:

```
  step 93   headless 1003 cells, viewer 1003 cells, differing 0
  step 94   headless 1003 cells, viewer 1003 cells, differing 0
  step 95   headless 1003 cells, viewer 1003 cells, differing 0
  step 96   headless 1003 cells, viewer 1003 cells, differing 0

  roster at step 94 identical field for field: 3 Sleeping Rats, 2 Hunting Snakes
  hero at step 94 differs only in turn=92.0 vs 91.0, ready, Haste 18.0 vs 19.0,
                                Recharging 29.0 vs 30.0
```

So the extra 1 HP leaves **no trace in a cell and no trace in the roster**, and the hero's own buff
set is identical on both sides. What remains is narrower than this entry said it was: whatever spends
the hit is neither the terrain, a trap, a plant, a heap, a transition, the fog, the visited set nor
a mob. The channel is exhausted rather than absent, which is a different place to stop from.

Evidence: `out/evidence/issue-26/`, with the three commands to reproduce it in its `README.txt`.

### Why 20 of 20 recordings played clean until now
**The corpus had no scroll to read.** The previous `duelist-death-last` used `USE` on food, a
### What is not established
- **What deals the extra HP.** Not the scroll read, not its animation, not the RNG, not the engine
  clock, not the speed - all five measured. Not the roster, and now not the cells either: with
  `ReplayPlayer` recording them, all 1,003 differ by nothing at every step around the divergence, and
  the hero's own buff set is identical on both sides. Not established.
- **A gate.** This is the ninth recording in this shape and `playbackcheck` passed it, because
  `ReplayPlayer` headless agrees with `verify` - the divergence needs the **renderer**, so only
### The two tools that were broken, and what fixing them bought
Both halves of the instrument were wrong, which is why the fault above read as *unobservable* rather
- **`:desktop:replay` did not forward `-PspdWorldTrace`.** Only `viewcheck` did, so the invocation this
  file's "measured" block uses wrote nothing and said nothing. Fixed; the command above now produces a
- **`viewdiff` keyed its two inputs by a scheme neither side shares.** It reported
  `hero #1 exists here and not in the expected snapshot` on a run whose two sides agreed, because
  Before, on the same two traces: `at step:0: hero #1 exists here and not in the expected snapshot`.
### Three smaller things found alongside it
- **The viewer's world trace was written on halt but only carried the frame the halt landed on.** The
  viewer's last `hero` record before the divergence was step 93, and the step that diverges is step 94 -
- **`-Dspd.replayContinueOnDiverge`, added here.** `ReplayPlayer` halted on the first divergence, so the
  steps *after* the fault - the only ones worth looking at - were never recorded. It now keeps applying
- **`:desktop:replay` did not forward `-PspdHidden`.** `ReplayLauncher` reads `spd.hidden` and
  `ViewCheck` already passes it, so the setting existed and worked - but this task never bound it, so
With `-PspdContinueOnDiverge` the traced run now completes and the diff has 5 more steps to work with,

## 27. No comparison in this project has an error bar, and `bestDepth` is a coin flip - **OPEN, and it invalidates the headline metric**
Found while trying to answer "did the `Network` and `WAIT` fixes help?" and discovering there is no way to
Three runs of one identical config - `--reward-profile default --generations 15 --episodes 4 --seed
```
run       gen0       last mean   bestScore   bestDepth   valueLoss   advStd
floor-1   -3114.60   -3154.40      -1552         2         12432.7    112.4
floor-2   -3113.20   -3142.60      -3061         1         13363.2    112.4
floor-3   -3113.70   -3117.50      -1466         2         31323.3    174.1
```
**The start is nearly deterministic and the end is not.** `gen0` agrees to within 1.4 points across three
- **`bestScore` spans 1,595 points** on identical config and seed. Two of the three runs reached a best
  score twice as good as the third. Any before/after comparison of `bestScore` smaller than that is noise.
- **`bestDepth` is 1 or 2 and which one is chance.** Two runs hit 2, one hit 1. **This is the project's
  headline metric and it is a coin flip.** Every claim in this repository that a run "reached depth 2" -
- **`valueLoss` spans 2.5x** (12,433 to 31,323) with no trend in any run.
- `meanScore` over 15 generations is the stable one: -3134, -3166, -3139, so **+/- 16 points**.
### What this retires
`README.md` and `TODO.md` both record `bestDepth 2` as a result. With three repeats showing it at chance,
### Why nothing has learned, stated plainly
The same three runs, plus a 30-generation run, show what is actually happening at the episode level:
```
floor-1: episodes=1200 stalled=1188 (99.0%) meanTurns=123
floor-2: episodes=1200 stalled=1187 (98.9%) meanTurns=140
floor-3: episodes=1200 stalled=1171 (97.6%) meanTurns=122
```
**98-99% of every episode ends STALLED, and it is 99% at generation 0** - before the policy has learned
`EnvConfig`'s own comment on `freeStepBudget` says 120 "was binding: four of ten recordings from the run
That is the most likely reason nothing descends: with 99% of the data being STALLED terminations, the
This also contradicts `README.md`'s "Every episode runs the full 1500 turns with zero stalls". That line
Evidence: [`out/evidence/floor-1.csv`](out/evidence/floor-1.csv), [`floor-2.csv`](out/evidence/floor-2.csv),

## 31. Open items recovered from the viewer-fidelity and parity documents - **OPEN**

Recorded before `PLAN-replay-parity.md` and `FINDINGS-viewer-fidelity.md` were removed. Both carried
live content that was in neither `issues.md` nor `TODO.md`, and deleting them would have lost it. The
refutation record those two held - eleven hypotheses, ten refuted by running them - is the reason the
viewer fault took as long as it did, and it is worth keeping that such a record exists even where the
individual results live on in the changelog.

### From the parity plan, never resolved

**A run that ends on the soft stall guard replays as a stall.** `SPDEnv` terminates when the hero's
position and health have not changed for `stallLimit` turns. The recording is complete and the hero is
alive, but the viewer has nothing to ask, so after the final step it waits for a hero that will never
become ready and halts with *"stalled - hero did not become ready within 400 turns"*. A viewer fault,
not a trainer one, and it was never gated.

**`Main.rollout` is not gate-covered.** `collectcheck` guards the collector path that training uses.
`Main.rollout` is a separate path and is not guarded - which is exactly where a real regression landed
before: an empty-seed guard that rejected both the flag *and its absence*, making random seeds
unreachable, and a recording that stored the seed it was asked for rather than the one the environment
resolved.

**Three decisions that were never formally settled.** Whether the corpus is checked in or generated on
demand (in practice: checked in, 20 recordings), whether to split the gate budget into a fast gate and a
larger periodic job (no), and whether to adopt event-keyed RNG (correct in principle, not adopted - see
`issues.md` 25 for the bug that made the case for it).

### From the findings record, never resolved

**The animation clock cannot be pinned from the viewer.** It needs an engine change at `Game.java`.
Closing it would make animation timing deterministic for diagnosis, and on its own would not have fixed
any of the divergence - it changes *when* an animation completes, not *what happens* when it does.

**A new public draw path would not be caught.** `observecheck` enumerates the draw paths it knows and
checks each is counted; a path added later does not appear in that list. The invariant is about the
paths it knows about, not about future ones - which is precisely how the four presentation draws in the
original fault got through.

**Two divergence shapes were never separately explained.** `duelist-short` failing on position by one
cell, and `mage-mid` reporting two points of healing the recording did not, were both recorded as
distinct signatures. Both were present before the fix and both are gone after it, so they were
downstream of the same cause - but that was never shown. It is asserted from their absence.


## 37. One recording in two does not reproduce, and it is the trainer's half - **OPEN, not investigated**

Found by running the 30-generation measurement `TODO.md` 1 asks for, and reading what it wrote rather
than what it printed. `issues.md` 15 and 26 both record a recording that will not replay; this one is
neither of those, and the distinction is the reason it is filed separately.

### What it is

A 30-generation run, 8 workers, 4 update threads, seed 20261016, `train.random_seed_rate=0`, wrote two
recordings - best per seed and the run's worst, which is what `ReplayKeeper` keeps. Same generation,
same seed, same hero class, same header:

```
trainer-replays/noise-floor/LZJ-LNO-HLE.dat      generation 29  depth 2  598 turns  599 steps
trainer-replays/noise-floor/LZJ-LNO-HLE-r2.dat   generation 29  depth 1 1184 turns 1186 steps
```

The longer one reproduces exactly:

```
verify: seed=LZJ-LNO-HLE hero=WARRIOR steps=1186 recorded score=-5402.804771922529
[OK]     replay reproduced in 1186 steps, 556 ms
score    recorded -5402.804771922529, reproduced -5402.804771922529
```

The shorter one does not, and it does not in the same place every time:

```
verify: seed=LZJ-LNO-HLE hero=WARRIOR steps=599 recorded score=-1649.690040986985
[ERROR] replay diverged at step 534 of 599
        turn: replayed 13.0, recorded 12.0
```

Run again, identical output. **Deterministic, not a flaky seed** - which is the one thing this rules
out.

### Why it is not `issues.md` 26 and not `issues.md` 15

- Not 15. That was four of nine step fields dropped by the wire, recognised by the four-field
  signature. These recordings carry health, engine time and inventory on every step.
- Not 26. That is the *rendered viewer* spending 1 HP the recording does not contain, and it needs a
  display to see. This is `verify` - headless, in one process - refusing a recording that a **worker
  in another process** wrote. Nothing about the renderer is involved, so the whole viewer half of 26
  is irrelevant to it.

### What it looks like

Only `turn` differs, by one, at step 534 of 599. Position and health agree at that step, and agree at
every step before it - `verify` compares position first and would have reported a position
divergence had one occurred.

```
 528  MOVE_SW 1 WORLD 1044  1.04  11  11.0  Longsword:1,Seed:1
 529  MOVE_NE 1 WORLD  993 -2.96  11  12.0  Longsword:1,Seed:1
 ...
 533  MOVE_SW 1 WORLD 1044  1.04  11  11.0  Longsword:1,Seed:1
 534  MOVE_S  0 WORLD 1094 -0.96  11  12.0  Longsword:1,Seed:1
 535  MOVE_E  0 WORLD 1095 -2.94  11  13.0  Longsword:1,Seed:1
```

The clock reads 11.0 at both 528 and 533 and 12.0 at 534, so the step at 534 cost one turn in the
recording and two in the replay, and the two stayed one apart for the remaining 65 steps without the
positions ever separating. The floor is 50 wide - `MOVE_SE` at 531 is `994 -> 1045`, which is
`+50 +1` - so nothing crossed a floor boundary and the clock was never restarted under it.

### What is not established

- **Which action.** It is a move on step 534, but it is not the first move of its kind in the
  recording, so "a move costs two turns sometimes" is not yet a description of anything.
- **Which process is right.** The recording was made by a worker; the replay ran in the verifier's
  process. Both are headless and share every line of the environment, so "one of them is wrong" does
  not yet have an answer.
- **Whether it is the real policy's doing.** `paritycheck`, `collectcheck` and `rolloutcheck` all
  drive `ScriptedPolicy`, and all three are green. This recording was made by the trained policy,
  which walks into things the scripted one does not. That is a difference in *coverage*, not
  evidence about the cause.

Recorded rather than guessed at, for the reason `issues.md` 18 gives: a hypothesis here would be a
story about a turn cost, and `issues.md` 33's own measurement - 189 searches taking half a
940-step run's engine time - is the shape of the last confident answer to that question.

### Reproduced again, and now localised - **a fresh 2-generation run, after the draw count landed**

A two-worker, two-episode, two-generation run (`--max-turns 40 --update-threads 1
--replay-group smoke-rl`, `train.record_every=1`, the default `random_seed_rate`) wrote two
recordings. One reproduces, twice, identically:

```
ZVI-KMW-HKC.dat   [OK]  replay reproduced in 634 steps, 470 ms   (and again, 477 ms)
```

The other does not, and it does not in the same place every time it is run:

```
NRF-TFY-RXG.dat   [ERROR] replay diverged at step 1421 of 1422
                 rng baseDraws diverged at step 1421: replayed 745, recorded 747 (delta -2)
                 action SEARCH slot 0 mode WORLD
```

Both runs of it give the same step, the same delta and the same action, so this is deterministic —
the one thing the original entry already ruled out, re-confirmed on a different seed and a
different network shape.

**What the new field adds.** Before it, this recording reported a position, health or clock
divergence and nothing about the stream. Now it says that at the last step the *replay consumed two
fewer draws than the recording did*, on a `SEARCH`. That is three things the entry above could not
state:

- the divergence is in the **generator**, not only in the accounting for it;
- its **direction** — the recording drew **more**, so the worker is the side that made the extra
  draws rather than the replay;
- and it is reached by a **`SEARCH`**, which `issues.md` 33 has already measured as the most
  engine-time-expensive action in the game.

**What it does not do is say which call site.** `baseDraws` is cumulative. `rngtrace` with
`-Dspd.rngTrace` records the draws-by-site tally over the same window, and that is the next
measurement; it is not taken here because this entry has already recorded once what happens when a
turn-cost story is asserted ahead of the measurement.

Evidence: `out/evidence/issue-37-smoke/`, holding both recordings and both `verify` outputs.

Evidence: `out/evidence/gen29-divergence.txt`, `out/evidence/gen29-LZJ-LNO-HLE.dat`,
`out/evidence/gen29-LZJ-LNO-HLE-r2.dat`, `out/evidence/noise-floor-30.csv`,
`out/evidence/train-noise-floor-30.log`.

## 38. A recording verifies in a fresh JVM and not in a process that has played an episode - **OPEN, and it reproduces in one command**

Found while implementing the `INTERACT`-underfoot fix `TODO.md` 2.2 asks for. It is not filed as a
variation of 37 or 18 because it is a **reproduction** rather than a signature: one command, every
time, on this tree.

### What it is

```
./gradlew :superintelligence:paritycheck -PparityArgs="--seeds 1 --heroes 1 --turns 400" --rerun-tasks

  PARITY-ALPHA  WARRIOR  357  303  -315.340  DIVERGED at 256: position: replayed 500, recorded 1301
```

One seed, one hero, record then verify twice in one process. The same file, in two fresh JVMs,
**reproduces in 357 steps both times** with the score to the last decimal. The recording is sound
and the process is not, which is what `paritycheck` exists to catch and what 37 and 18 both describe
without a reproduction this cheap.

### What makes it reachable

`ActionMapper.pickInteractCell()` scans offsets 1..8 only and `handleCell()` refuses a cell the hero is
not adjacent to, so an item on the hero's own cell is unreachable - and `GOLD_GAIN` and `ITEM_PICKUP`
have therefore never been seen paying in a run. Teaching `INTERACT` to take `hero.pos` when there is
a heap there is the fix `TODO.md` 2.2 names, and **it is what exposes this**. A/B on one tree:

| variant | `paritycheck` |
| --- | --- |
| `pickInteractCell` checks the hero's own cell, `handleCell` still requires adjacency | green, 32 of 32 |
| both changed | red at step 256 |
| both changed, own cell admitted only when a heap is there | red, same step |

**The pickup completing is the cause, not the cell the action resolves to.**

### Where the streams part

```
  254  INTERACT 0 WORLD 1222 1801.80  23  0.0  <- the descent; the reward is 1800 and the clock restarts
  255  MOVE_S   0 WORLD 1261  -2.63   23  1.0
  256  INTERACT 0 WORLD 1301  -3.91   23  3.0  <- recording 1301, replay 500
```

Both sides agree through 255 **including the per-step RNG draw count**, which `ReplayIO.verify`
compares before position, so the generator was still level at the divergence. **A position 761 cells
from the one before it is not a move**, so one side changed depth or floor without the recording
showing it.

### What is not concluded

Not a named static. `Dungeon.init()` already clears `Statistics`, `Notes`, `gold`,
`generatedLevels`, `LimitedDrops`, the four quest states and `Badges`, and `RunState` already clears
the armed aim, the parked dialog, the pending item and a dead hero's remains. Picking one of those as
the culprit without a measurement is what `issues.md` 33 records as the reason these entries are
written the way they are.

The measurement that would locate it is `RandomTrace.attribute()` over the window between the two
verifies. `TODO.md` 1.2's `rngtrace` work is that measurement, and this is the deterministic fixture
to run it on.

### Not shipped

The `ActionMapper` change is not in the commit this sits beside: `paritycheck` is red without it and
green with it reverted. The reward cases it asked for now drop the heap on an adjacent cell, which
proves both terms fire without the own-cell path - so the terms are proven live and the
underfoot-priority work stays open with a reproduction attached.

Evidence: `out/evidence/issue-38-underfoot-pickup/`.