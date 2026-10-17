# Open issues

Closed entries live in `CHANGELOG.md` with their measurements. Rejected and deferred proposals live in
`REJECTED.md`. What follows is what is still open.

| # | |
| --- | --- |
| 18 | `rolloutcheck` is the one flaky gate, 1 in 8. Undiagnosed: the tally names the stream, not the call site |
| 20 | under `depth_only` the run converges on dying early rather than on descending |
| 26 | the viewer spends 1 HP on a step the recording does not |
| 37 | one recording in two does not reproduce, and it is the trainer's half |
| 38 | a recording verifies in a fresh JVM and not in a process that has played an episode |

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


### Re-measured 2026-10-17 with the sub-mode mask on - **the same conclusion, and the first depth signal**

15 generations, 8 workers x 4 episodes, 4 update threads, seed 20261016, `env.sub_mode_progress_only=true`
throughout, every other default unchanged. `out/evidence/depth-only-postmask-15.log`.

```
windows   first 7 generations against the last 8
score     -2941.16 sd=109.8 sem=41.5  ->  -2783.94 sd=84.0 sem=29.7   delta=+157.22 +/- 51.0
depth>=2  0.4% -> 2.3%   (share of episodes reaching floor 2 or deeper)
```

- **7 of 480 episodes reached floor 2**, against **0 of 960** in the `default` 30-generation run.
- **`bestDepth` 2 in 5 of 15 generations**, against 5 of 31 in the run this entry originally measured.
- **`depth>=2` rises 0.4% -> 2.3%** between the first and last windows. That is the first depth signal
  any run in this project has produced, and it is on the axis this entry says nothing pays.
- **`DEATH` 479 of 480 (99.8%)**, `STALLED` 1, `TURN_LIMIT` 0.

**The conclusion is unchanged and the entry stays open.** Under `depth_only` the run converges on dying
early rather than on descending: 99.8% of episodes end in death, and the mean score is **-2,857**,
which is a little over the `-2,500` death penalty plus a little over the per-turn cost. The arithmetic
is the same one the entry gives - with nothing but the objective paying, a policy that cannot yet
descend learns to die cheaply - and the sub-mode mask moved the *rate* of descending by a factor the
project had never seen without moving that.

**What did change is that descending is no longer zero.** That is `issues.md` 22, which this run
closes: with the profile paying only for the objective, `meanScore` moved **+157.22 +/- 51.0**, about
3 sigma, and the movement is not exploration because under `depth_only` exploration pays zero.
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
### The clock is now pinnable, and the fault survives it - **timing refuted, and four steps the viewer refuses**

`Game.fixedFrameDelta` makes `Game.elapsed` and `Game.timeTotal` deterministic for a run, which is
what this entry said would be needed before the animation hypothesis could be tested. It has been
tested:

```
[replay] spd.fixedDelta pins Game.fixedFrameDelta at 0.05s, so the animation clock is deterministic
[replay] DIVERGED at step 95 - USE/0 in SLOT: hp is 6, recording says 7
[DIFF]   at step:94: hero (settled) field hp   expected 7/20, actual 6/20
```

**Bit-for-bit the same with the clock pinned as without it, so animation timing is not the cause.**

**And the viewer's own world snapshot names something this entry had not looked at.** It records
acceptance per step: 100 applied, 11 refused, 96 frames advancing engine time, with refusals at steps
0, 81, 84 and 87-94. Steps **88, 90, 92 and 94 are `USE 0 SLOT` in the recording, and every one of
them is refused** - while the trainer applied all four and the hero's health fell 9 -> 8 -> 7 across
the first three:

```
 88  USE 0 SLOT  Food:1,PotionOfHaste:1,ScrollOfRecharging:1,... -> PotionOfHaste:1,...
 90  USE 0 SLOT  PotionOfHaste:1,ScrollOfRecharging:1,...        -> ScrollOfRecharging:1,...
 92  USE 0 SLOT  ScrollOfRecharging:1,...                        -> VelvetPouch:1,...
```

**The bound item at slot 0 is not the item the trainer consumed**, and from step 88 the two sides are
playing different actions. That is a sufficient explanation for one point of health at step 94 and a
better one than all 1,003 cells being identical. `SlotAction.use` returns false on exactly three
things - the item resolved to null, a bare equip toggle, or `resolveAction` returning null - and
**which of the three fires is not established.** The next measurement is to print what slot 0 resolves
to on both sides at step 88.

Evidence: `out/evidence/issue-26/pinned-clock-and-refusals.txt`.


### The refusals are not an asymmetry - **the recording does not record acceptance**

`spd.trace` now prints what a refused step resolved to. On `duelist-death-last.replay`:

```
[trace] refused 88 USE/0 in SLOT: slot 0 = Food, quickslot -1,
                backpack 0=Food 1=PotionOfHaste 2=Waterskin 3=ScrollOfRecharging 4=VelvetPouch
[trace] refused 89 USE/0 in WORLD
[trace] refused 91 USE/0 in WORLD: slot 0 = PotionOfHaste
[trace] refused 93 USE/0 in WORLD: slot 0 = ScrollOfRecharging
```

**Slot 0 resolves to exactly the item the trainer consumed**, the quickslot binding is right, and the
refusals alternate `SLOT` / `WORLD` - the pair `SlotAction` produces when an item takes more than one
step.

**So the refusals are not the fault.** `Replay.Step` does not carry whether the action was accepted,
so a refusal in the viewer is indistinguishable from the same refusal in the trainer: the recording's
own inventory column shows the `Food` still carried at step 88 and gone at 89, which is what a refusal
followed by a refusal looks like from either side. An earlier version of this entry read the 11
refusals as the viewer rejecting steps the trainer applied, and **that reading is wrong** - the
recording cannot answer the question.

**What this costs and what it would buy.** `ReplayRecorder.afterStep` is given the hero's position and
the reward and nothing else, so the one bit that separates "the engine did this" from "the engine
refused this" is never written. Adding it is a format change: a field on `Replay.Step`, a codec
version, a sentinel so recordings written before it still verify, and a comparison in `ReplayIO.verify`
that reports a step the two sides disagree about rather than a step one side skipped. That is the
next measurement for this entry, and it is not taken here.


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

### Re-measured on a 30-generation run, and it did not recur - **still open**

The re-measurement `TODO.md` 1 asked for wrote two recordings and **both verify exactly**:

```
[OK]  replay reproduced in 207 steps, 406 ms   score recorded -3094.229994300753, reproduced -3094.229994300753
[OK]  replay reproduced in 1283 steps, 442 ms  score recorded -5518.715346027166, reproduced -5518.715346027166
```

**That is one run, and it does not close this entry.** `issues.md` 18 put the same fault at 1 in 8
runs days earlier, so two clean recordings out of two is the expected outcome even if the rate is
unchanged. What has not changed is that nothing diagnoses it: the measurement this entry asks for -
`rngtrace` with `-Dspd.rngTrace` over the failing recording, whose tally names the call site - has
still not been taken, and `issues.md` 38 has since produced a cheaper reproduction of a neighbouring
fault to run it on.

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

### Localised: it is floor 2's generator, not the base stream

`./gradlew :superintelligence:reprocheck` records the run, writes the recording and the per-site
draw tally to `out/evidence/`, and verifies it three times in one process. **The three verifications
agree with each other exactly** - same step, same delta, same `baseDraws` 544, same 175 sites - so
verification is deterministic and the recording is not the variable. Verify the written file in a
fresh JVM and it reproduces in 357 steps.

Diffing the poisoned process's tally against a fresh-JVM `rngtrace` of the same file:

```
site                                      fresh   poisoned   delta
Patch.generate:56                          14078      8098    +5980
Maze.generate:84                             3912         0    +3912
Maze.generate:85                             3912         0    +3912
Patch.generate:129                           9856      6168    +3688
Maze.decideDirection:125                      2502         0    +2502
```

**`Maze.generate` appears only in the fresh run.** `MazeConnectionRoom` is what `RegularBuilder`
puts wherever a `SecretRoom` connects, so the fresh run built a secret room on floor 2 and the
poisoned one did not. That is not the same floor at a different offset; it is a different number of
secret rooms, which changes the floor's whole layout and therefore where the hero stands at step 256.

**Why the per-step count cannot see it.** `baseDraws` is identical at 544 on both sides through the
divergence and `ReplayIO.verify` compares it before position, so by that measure the gameplay stream
is level. It is not level in fact. A draw is classified as base by the generator being the *sole* one
on the stack, so the only draws invisible to `baseDraws` are the ones on a pushed generator - and
`Dungeon.seedForDepth` with `Random.pushGenerator` is exactly that: floor 2 is built on its own
generator seeded from the run seed, and its starting state depends on draws made on the base stack
before the push.

**That is the localisation, and it is not the diagnosis.** Established: the divergence is in floor 2's
generation, it is on a per-floor generator rather than the base stream, and it changes how many secret
rooms the floor has. Not established: which draw on the base stack, before the push, differs between
a process that has run an episode and one that has not.

Evidence: `out/evidence/issue-38-underfoot-pickup/localised.txt`.

### Not shipped

The ActionMapper change is declined for now and the reasoning is in REJECTED.md; this
entry carries the reproduction. paritycheck is red without it and green with it reverted,
and a committed tree has to be green. The reward cases it asked for now drop the heap on an
adjacent cell, which proves both terms fire without the own-cell path.

Evidence: `out/evidence/issue-38-underfoot-pickup/`.
