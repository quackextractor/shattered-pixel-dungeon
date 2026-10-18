# Open issues

Closed entries live in `CHANGELOG.md` with their measurements. Rejected and deferred proposals live in
`REJECTED.md`. What follows is what is still open.

| # | |
| --- | --- |
| 20 | under `depth_only` the run converges on dying early rather than on descending |
| 26 | one snake attack lands on both sides and rolls a different number |
| 37 | one recording in two does not reproduce, and it is the trainer's half |

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


## 26. One snake attack lands on both sides and rolls a different number - **OPEN, diagnosed, not fixed**
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


### Narrowed to a single frame, and the reading inverts - **superseded by the hit trace below**

`reprocheck --args="deathfixture"` records `DUELIST-LONG-16` - 100 steps, verified headlessly, with
`accepted` on every step - and the viewer plays it to the same fault:

```
[replay] DIVERGED at step 95 - USE/0 in SLOT: hp is 6, recording says 7
```

**The two traces, at step 94, settled.** Headless records one hero line; the viewer records two, one
per settle. At `turn=92.0`, `ready=true`:

```
headless  pos=552 hp=7/20  Haste:18.0 Recharging:29.0
viewer    pos=552 hp=6/20  Haste:18.0 Recharging:29.0
```

**Identical position, depth, gold, level, experience, strength, paralysis, invisibility, rooting,
flight, resting, starving, all five buffs, the whole inventory, a byte-identical roster and 1,003
identical cell records.** One hit point is the entire difference.

**And the viewer's per-frame trace names the frame it happens on:**

```
frame 5640  driver  elapsed=0.006975 drain=- applied=false
frame 5640  gate    ready=false current=Snake@5 animating=2
frame 5640  turns   now=91.0 delta=0.0 taker=Hero@1 spent=91
frame 5641  hero    pos=552 hp=6/20
```

`hp` is 7 on frame 5640 and 6 on 5641, and nothing else on that frame's record differs. The gate line
names **`Snake@5` holding the turn with an animation in flight** one frame earlier. That snake is at
cell 579 and the hero at 552, and its roster entry - `Hunting`, `hp=4/4`, `cd=0.0` - is identical on
both sides.

**This inverts the reading the entry has carried.** It is not established that the viewer *spends* a
hit point the recording does not contain; it may equally be that **the headless path skips a hit the
rendered path lands.** `Actor.headlessStep` drops frames that the render loop would resolve, and the
trainer keeps no per-frame trace at all, so this window has never been compared frame-for-frame on
both sides - `worldtrace` records per step and the viewer records per frame. **That asymmetry is the
next measurement.**

Not established: which of the two readings is right. Established: the loss is on rendered frame 5641,
one frame after a `Snake@5` turn with `animating=2`, with the entire recorded world identical.

Evidence: `out/evidence/issue-26/frame-5641.md`.

### Both sides take the hit. The number differs.

A tripwire in `Char.damage`, enabled by `SPD_HERO_DAMAGE_FILE`, records every hit the hero takes
with the engine time, the health going in, the number, the source, and the first frame outside
`Char` and `Actor`. Run on `duelist-death-last.replay` through the headless verifier and through the
real viewer, the two traces are **identical for 24 lines and then**:

```
headless  [HERODMG] turn=91.0 hp=7 dmg=0 src=Snake via=Hero.damage:1683
viewer    [HERODMG] turn=91.0 hp=7 dmg=1 src=Snake via=Hero.damage:1683
```

**This refutes the entry's reading, and its own alternative.** For twenty-odd lines both sides took
the same hits, in the same order, from the same sources, at the same engine times, with the same
health. Then one snake attack lands on both sides and arrives at `Char.damage` with a different
number. `Hero.damage:1683` is `super.damage( dmg, src )`, so the difference is **upstream** of
`Char.damage` entirely: nothing is spent, nothing is skipped, and no hit exists on one side that the
other does not have.

**And it is why every instrument this entry reached for came back empty.** `baseDraws` and
`baseFingerprint` are compared per step and were already measured equal on both sides at every
step; all 1,003 cells were identical at steps 93 to 96; the roster was byte-identical. **A
divergence inside a step is invisible to a comparison taken at step boundaries.** That is this
project's eighth instance of one shape, arrived at from the other end: *a green is a claim about
what was checked*, and "equal at every step" is a claim about step boundaries.

**What is left, and it is much narrower than the entry was.** One attack, at engine time 91.0, from
a `Snake`, landing on a hero at 7 HP, resolving to 0 headless and 1 in the viewer. The fault is in a
computation that consumes randomness, on one roll.

**The next measurement** is to take `RandomTrace.siteLog()` - the ordered draw log this release adds,
which a per-site tally cannot replace because a tally has no order - on **both** sides around that
window, and read the first draw at which they part.

Evidence: `out/evidence/issue-26/herodmg-diff.txt`, with both raw traces beside it.

### Ruled out: the viewer and the trainer do not disagree about acceptance

`reprocheck --args="deathfixture"` records a DUELIST run on `DUELIST-LONG-16` and refuses to write
one that does not end in DEATH, reproduce, or refuse at least one step. It wrote:

```
recorded 100 steps, ends DEATH, depth 1, score -2994.8800207301974, accepted 93, refused 7
verified headlessly in 100 steps
```

The same recording through the real viewer:

```
[replay] halted: DIVERGED at step 95 - USE/0 in SLOT: hp is 6, recording says 7
```

**The acceptance comparison never fired. All 100 steps agreed, including all 7 refusals** - so the
viewer and the trainer accept and refuse the same steps, and the hypothesis this fixture was built to
test is refuted.

**Four explanations are now ruled out with the instrument that can see them**, which is more than this
entry had:

| ruled out | by |
| --- | --- |
| animation timing | `Game.fixedFrameDelta` pinned, fault bit-for-bit identical |
| action acceptance | this fixture - 7 refusals, zero disagreements |
| terrain, trap, plant, heap, transition, fog, visited set, mob | `ReplayPlayer` records cells, all 1,003 identical |
| presentation randomness on the gameplay stream | `rngtrace`, 0 of 94 and 0 of 104 attributed sites |

**What is left is narrow:** same actions, same acceptances, same cells, same roster, same buffs, same
draw count, deterministic clock - and **one point of health at step 95 on a `USE/0 in SLOT`.** The
remaining candidates are inside the scroll's own read path (`Scroll.readAnimation`, the `Recharging`
effect, the time it costs), and the next measurement is the hero's effect state at step 94 on both
sides, which nothing currently records.

Evidence: `out/evidence/issue-26/acceptance-fixture.md`.
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

## 37. One recording in two does not reproduce, and it is the trainer's half - **OPEN, re-measured after the fix**

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
still not been taken. `issues.md` 38 has since produced a cheaper reproduction of a neighbouring
fault, and it has closed: the cause was a guidebook page, and `Document`'s page states are now cleared on
every reset.

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

### Re-measured after the guide-page fix - **did not recur in its old form, and reproduced in its own**

`issues.md` 38 was closed by `Document.resetPages()`, and that fix was a per-process state leak of
exactly this family, so the obvious prediction is that this entry closes with it. **It does not.**

A two-generation run, 2 workers x 2 episodes, `--record-every 1`, wrote three recordings and **two
of the three verify exactly**:

```
ZVI-KMW-HKC-r2.dat  OK
ZVI-KMW-HKC-r3.dat  OK
LBS-VPS-LZG.dat     [ERROR] replay diverged at step 1124 of 1125
                    rng baseDraws diverged at step 1124: replayed 637, recorded 639 (delta -2)
```

**That is this entry's signature exactly** - a negative delta at the last step of the recording, so
the recording drew more - on a different seed and a different network shape. The historical
`NRF-TFY-RXG.dat` still fails identically, which says only that it was written before the fix.

**What the fix did narrow, and it is a real narrowing.**

- Two verifications of one file **agree exactly**, so the verification perturbs nothing.
- `paritycheck` runs **32 recordings across 32 episode boundaries in one process** and is green, so
  an episode-to-episode leak that the **scripted** policy reaches is excluded. The worker's policy is
  the trained network, and `issues.md` 37 already recorded that this is a difference in *coverage*
  rather than evidence about the cause.
- The difference therefore lives in the process that **made** the recording, and no instrument in
  this repository reads across a process boundary yet.

**The next measurement is `review.md` Plan 1.1 steps 3 and 4**, and it is wiring rather than new
instrumentation, because `RandomTrace.siteLog()` and `RandomTrace.generatorLog()` now exist: enable
attribution inside the worker, dump the per-episode site and generator logs, and diff them against a
verification of the same file. The ordered log is what makes it possible - a per-site tally said two
processes were level at every site while their order differed.

Evidence: `out/evidence/issue-37-recheck.txt`.
