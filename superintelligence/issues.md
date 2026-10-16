# Viewer / recordings
1. When using replay-viewer.bat, after a recording finishes and the user presses 'R', after reset the recording still says finished. When the user then tries to press spacebar to continue, the game thinks it's the waiting keybind and the recording doesn't play, instead diverges due to wait action. (closed)
2. When using replay-viewer.bat, the viewer doesn't actually play out the last step where the hero dies. Instead it finishes just before that. (closed)
3. Turns and turn limit is a bit misleading, since it's actions, as turns in the engine are often not whole numbers and certain items and conditions can affect the amount of turns an action takes. (closed)
4. The recordings are missing information about how the score changes with each action. Very important to see in the viewer as a live info. Additionally there should be a way to see total score gained and lost as well, not just their sum. (closed)
5. Recordings show coordinates (such as during diverted) as a single number. It'd be much easier for a human to troubleshoot if they were in x,y. With x increasing to the right of the screen and y increasing with height, but staying true to engine. So highest y value is at the bottom. (closed)
# Env
> **Found while closing 6, and left open rather than silently absorbed into it.** `env.grid_height`
6. I am unsure if the env.gid dimensions are accurate. I read online that the max is 32x32. This number might've changed but a code sweep might be in order. (closed)
    The quoted text is wrong about this codebase on the part that matters. Regular floors are not 32x32
    Measured rather than read off: 1040 floors over 40 seeds at depths 1-26.
    ```
    **So 48 does under-cover 40% of floors by extent — and that does not make the agent unprepared
    What is actually wrong is narrower, and is left open rather than folded into this closure:
7. The inventory slots / inventory system seems wrong. (investigated - see `TODO.md` 0.8)
    Confirmed, and the quoted ceiling of 96 is the right one. Bags are obtainable and already present in
    Not closed, because the fix is not the obvious one and `TODO.md` D7 turns out to claim a
    "
The maximum inventory space available in Shattered Pixel Dungeon is 96 item slots when you have acquired all of the expansion containers.
Your starting Backpack provides 20 item slots for general items. The game features four specialized expansion bags, each providing an additional 19 slots for specific item categories:
    Velvet Pouch: 19 slots for seeds, runestones, goo blobs, and cursed shards.
Scroll Holder: 19 slots for scrolls, exotic scrolls, spells, and arcane catalysts.
Potion Bandolier: 19 slots for potions, exotic potions, brews, elixirs, and alchemical catalysts.
Magical Holster: 19 slots for thrown weapons, wands, and bombs.
Certain items do not take up inventory space. Gold coins, keys, energy crystals, the adventurer's journal, and the containers themselves can be held in unlimited quantities without occupying any of your 96 main slots."
# Training
8. I saw a hero pick up and drop an item in a recording, presumably cheating score. This has not yet been confirmed though. I don't see configuration for such things in the properties file. Duelist-mid (closed)
   Confirmed, and it was not a configuration problem. `RewardModel` compared a **count** of carried
   Note that `duelist-mid` no longer contains a `DROP` step at all — the corpus has been regenerated
9. Critical: The same duelist-mid recording shows him descending to a new floor, with full health too. But immediately as he descends, the recording ends as stalled. Is this a bug with the STALLED state detection? Also stalled should punish as much as death to restore balance between these two endings. (closed)
   It was a bug in stall detection, and there were four faults behind it. `Game.switchScene` raises a
   `STALLED` is now `-deathPenalty`, through the same constant `DEATH` uses. That reverses
   Descending now works. **Climbing back up does not** — see 10, which was found on the way.
10. A run that descends and then walks back up the stairs diverges from its recording, and the viewer
    Both environments now agree. The "Cannot read save file" half was already closed before this was
    The divergence was neither `Mob.holdAllies` nor `Dungeon.saveAll()`, both of which were the prime
    **The fault is that the environment never gave the hero a turn on the new floor.** The hero arrives
    ```
    `SPDEnv.settle` now gives the hero that act after servicing a transition, and it is one act rather
    Gated by two new `transitioncheck` cases, 7 -> 9. The first drives a real descent *and* ascent and
11. `duelist-mid` played by hand at speed 1 in a fullscreen window halted at step 25 with `engine time is
12. The viewer creates `bones.dat` in the player's own profile. (closed)
    A recording that ends in death kills the hero for real, so `Bones.leave()` writes the remains file
    `Bones.clear()` is in-memory only and says so; the caller that removed the file,
    The viewer half is separate: the cleanup has to be where playback *ends*, which is `halt()` and not
13. Duelist-mid ends with stalled without really being stalled? What's causing this? It didn't seem like the hero was idling for this many actions. Investigate. Worst case: Remove stalled and regen the recording? thx. (investigated - not fixed, `TODO.md` 0.10)
    The instinct is right and the conclusion "remove stalled" would be wrong. Replaying `duelist-mid`
    ```
    **The hero was not idling, and it was not doing anything either.** It was oscillating `USE` on a
    So `STALLED` is a **true** verdict on a run that made no progress. The guard is not broken. What is
    **Therefore removing STALLED, as the report suggests, would not help and would hide this.** Without
    ### `cleric-mid` is a different fault, and shares no cause with the above
    Also `termination=STALLED`, 49 turns against a 150 cap, ending `resting=true`. But it is spent real
    Two `STALLED`s, two producers, one header field. `Replay.termination` (`Replay.java:168`) is a bare
    ### What this means for the recommendation to regenerate the recordings
    Regenerating would work and would fix nothing. The policy that produced `duelist-mid` will produce it
    ### Partly acted on: the loop is priced, the guard is unchanged
    `reward.no_progress_penalty` charges every step that advanced no engine time, so those 120 free steps
    **The stall guard itself is deliberately unchanged**, and that is the part worth being explicit about
14. Nothing has ever left floor 1, and the reward function is not asking for it. (closed as a cause;
    Three faults, and each is a property rather than a size — which is why none of them is visible in a
    1. **Exploration outbid a descent.** `reward.explore_reward` 0.05 against `reward.depth_reward` 10,
    **Measured: survival improved, depth did not.** Deaths 100% → 19% and mean turns 377 → 1335 over 12
15. Recordings written by the trainer cannot be played in the viewer at all, and `verify` reports them
    Asked whether the depth 2 run could be watched now that the replay wire was fixed. It could not:
    ```
    Four faults, found in the order they had to be found, and the second is why the first was invisible.
> **1. `verify` was not checking what the viewer checks.** It never applied the recorded bindings at all
> **All ten are gone from `trainer-replays/`, and that was the right call.** The directory's job is
---
16. Two trainer recordings diverge on a chasm fall, and floor 2 is generated differently — **OPEN**,
    Found while reading the recordings of a 150-generation run. Two of the 106 fail `verify`, and they
    ```
    The other 104 verify. As with `issues.md` 15, **the two failures are the two runs that walk
    ### The measurement
    `LNL-PDP-CJK`, around the divergence. Both sides replayed; the world agrees cell for cell right up to
    ```
    `verify` says: `position: replayed 1203, recorded 937`. **Same seed, same floor number, `branch=0`
    ### What it is not, each refuted by changing the code and re-running
    | Candidate | Verdict |
    The third is the one the two recordings were being blamed on, and it is now a gate:
    ### The control, which is the useful part
    A recording made by one worker in one episode, with **both** sides traced:
    ```
    A run that descends **383 times** reproduces perfectly, with the special-room pools identical at every
    That is the whole of what is known. Everything in the table above is refuted; nothing in it explains
    ### Getting to a clean failing pair
    `WorldSnapshot` now records `runSpecials`/`floorSpecials` per step, and
    - The trace path was shared, so six episodes landed in one file — 1071 records for a 190-step
    One counter issued by the trainer rather than per worker is the last thing standing between this
### Also wrong along the way, and worth recording because it cost time
I read `tier=Floor` in the world trace as "not a chasm". **It is not evidence of anything**:
I also counted 8522 "chasm cells" in a replay trace, which was records across steps rather than
---
## 16. Two trainer recordings diverge on a chasm fall, and floor 2 is generated differently - **CLOSED**
`GRB-YWQ-ZJH` and `LNL-PDP-CJK` are gone: the trainer section was regenerated and neither file was
**The chasm fall was never serviced at all.** A fall is the only floor transition that carries no
```java
if (transition == null){ clearSwitchRequest(); return Transition.NONE; }
if (mode == Mode.FALL)          { clearSwitchRequest(); return Transition.FALL; }
```
On a fall `curTransition` is null *by construction*, so the first test fired every time.
```
depth 1 -> 1, switch request acknowledged, hero still standing on the pit
```
So the two recordings could not have diverged *on a chasm fall* in the way the trace above describes —
**`InterlevelScene.mode` is a process static, and nothing headless ever resets it.** A real game gets a
**Three more things on the same path, found while fixing it and none of them in the table above.**
| | what it was |
| --- | --- |
| the landing | `handleFall` called `level.fallCell(true)` and ignored `InterlevelScene.fallIntoPit`, hardcoding the `WeakFloorRoom` case. They agree on a floor with no `PitRoom`, which is why changing it looked like it changed nothing; where the destination floor has one, the trainer landed the hero inside it and the game would not — a different cell and a different number of draws. |
| the landing effect | `InterlevelScene.fall` opens with `Buff.affect(hero, Chasm.Falling.class)`, whose act is `Chasm.heroLand` — fall damage, cripple, bleeding, and a press of the landing cell. The headless path skipped all of it, so **a fall was free**, and strictly better than the stairs. |
| the approach | walking a hero *at* a chasm threw `NullPointerException` out of `Chasm.heroJump`, from `new Image(Dungeon.level.tilesTex(), ...)`: `tilesTex` names a texture, `TextureCache` returns nothing for it with no renderer, and `Image`'s five-argument constructor derived its frame rectangle from the texture without the guard `frame(RectF)` had carried since the bypass landed. It is thrown from inside `Actor.headlessStep` and nothing between there and `Worker.serve` catches it, so it ends a worker's episode. |
`Image`'s guard is the one change in `:SPD-classes`, and it is the same category as the rest of
**`Mob.holdAllies` is deliberately still not called.** `InterlevelScene.fall` opens with it, and it
**And the second half, which made the chasm unreachable rather than merely broken.** Walking *at* a
- `WindowBridge.age()` advances the parked dialog once per environment step — once per frame, the
  approximation `ReplayPlayer` already makes about a recorded step, so the gate is the game's own 0.2 s.
- a parked window lives in a one-slot `Group` rather than a bare field, so the game's own
  `Window.hide()` closes the slot. `GameScene.answerableWindow()` had to read the accessor rather than
- `settle` reports a dialog opened *during* the drain rather than a turn later. The prompt is raised from
  inside `Hero.act()`, so the agent was being handed the world for one more turn with a modal question
Measured end to end: **the prompt opens, the agent answers, the game accepts, the dialog closes itself,
`Levitation.detach()` still does not re-check the landing cell headlessly, because the game guards that
```
hero placed on pit 197 while flying: onPit=true flying=true depth=1
after levitation.detach():           onPit=true flying=false depth=1 alive=true
then walked MOVE_SW off it:          pos 197 -> 232, depth=1 (still no fall)
```
So the hero stands *on* the chasm and walking off it does not drop him either —
**Fixed on the supervisor side, without touching `:core`.** The agent is exactly the party that can
```
without the check:  depth 1, pos 391, 20 health, still on the chasm
with the check:     depth 2, pos 759, 15 health
```
It runs at the top of the drain rather than after a scheduler step, because the buff ends *inside*
There was a sixth fault on this path, a thrown item never triggering what it landed on, which is **not**
### What is gated, and what it refuses
`gradle :superintelligence:fallcheck`, six cases, each reverted and refused in turn:
| case | fails when |
| --- | --- |
| a fall is a floor | the `mode == FALL` test goes back behind the null test |
| landing hurts | `Chasm.Falling` is dropped from `handleFall` |
| a fall leaves no hand-off behind | `InterlevelScene.mode = null` is dropped from `clearSwitchRequest` |
| walking into a chasm does not take the process down | the null guard is dropped from `Image` |
| the same fall twice is the same fall | the fall does not happen at all — so the case refuses to compare a fall with itself |
| levitation ending over a chasm drops the hero | the pipeline's standing-on-a-chasm check is removed, leaving the hero on the chasm cell at full health |
and `gradle :superintelligence:dialogcheck`, three more, for the question rather than the fall:
| case | fails when |
| --- | --- |
| the agent answers the game's own dialog | `WindowBridge.age()` is neutered (2 of 3), or `selectOption` clears the slot again (2), or `GameScene.headlessWindow()` ignores the game's own `hide()` (3) |
| a dialog closes when the game closes it | `selectOption` clears the slot (1), or `headlessWindow()` ignores `hide()` (2) |
| a dialog opened during a turn is reported in that turn | `settle` does not report it (1), or `headlessWindow()` ignores `hide()` (2) |
The second of those uses `Chasm`'s *shape* as its fixture rather than the chasm itself — a dialog whose
The fifth fall case is `issues.md` 16's question asked directly: the same seed, the same fall, twice in one
**What is left, and it is small.** The agent can cross a chasm. It cannot cross one silently:
---
# Chasm
17. A thrown or dropped item never triggers what it lands on — **CLOSED**, measured.
    Found while closing `issues.md` 16. The chasm path has two halves — the engine asking whether the
    **`Level.drop` presses the landing cell**, which is how a thrown stone sets off a trap, opens a door
    ```
    Headlessly that second half is false, so the press never happened and a dropped item was inert.
    ```
    ### The fix, and why the scene half survives
    `Level.drop` is the one chokepoint for the whole item tree — `Item.onThrow` and `Item.doDrop` both
    ```java
    **`GameScene.headlessGameplay` is a new static, and only a renderer-bypassed process sets it.**
    The earlier attempt dropped the scene half outright. It built, every gate passed, and it was wrong:
    | call site | scene | cell that would start pressing |
    Both of those scenes need a renderer, so neither can exist in a headless process — which is exactly
    The `Dungeon.level != null` half is untouched, and it is still doing the work it was doing: level
    Measured after the fix, same seeds, same trap:
    ```
    ### What is gated, and what it refuses
    `gradle :superintelligence:presscheck`, **three cases**, and it is now **in `gates`** — it was left
    | case | fails when |
    The second case covers the branch a trap does not reach, and it is the reason `AlchemyScene` is a
    The third case's mutation is worth recording honestly: removing that half does not make the case
    ```
    — because the press now runs against a level that is still being built. The gate goes red either
    ### And a recording, because a gate that cannot reach a fault is not a gate
    `presscheck` proves a press happens. It cannot prove the two environments *agree* about one, which
    `test-replays/duelist-throw.replay`, recorded by `gradle :superintelligence:pressfixture`, which
    ```
    Terrain 15 is `HIGH_GRASS` and 2 is `GRASS`: the spike flattened it. The throw is driven through
    **Mutation-tested, and the gate that refuses it is not the one you would guess.** Reverting the
    ```
    **Not fixed by any of this, and it is the right answer:** a thrown item does not *trigger* a trap the
    **What is not broken, and is worth being precise about:** traps still fire when a hero *walks* onto
---
18. `rolloutcheck` fails about one run in thirty, and the recording it made verifies on the next
    Found while closing 17, and unrelated to it. `rolloutcheck` is in `gates`, and `gates` failed on one
    ```
    **It is not caused by the change that was in flight.** A/B'd against the base tree, the same
    | tree | runs | failures |
    Same signature, same rate, different random draws.
    **Why it is intermittent, and it is not a seed lock.** `RolloutCheck`'s first two cases record an
    **Not investigated, and stated as open rather than guessed at.** A recorded position and a replayed
    **What is not wrong:** `paritycheck` verifies 32 recordings across seeds in one process and passes,
## 19. An item that creates a mob leaves it with no sprite, and a mirror image then casts its sprite - **CLOSED, in `:core` and gated**
Found by a 30-generation training run under `reward.profile=depth_only` (16 workers, seed 20261013): two
`GameScene.add( Mob, float )` gives a mob its sprite and its place in `Actor.chars` only when a scene
```
reading a Scroll of Mirror Image threw java.lang.NullPointerException at Char.move:1334
reading a Scroll of Mirror Image threw java.lang.ClassCastException at MirrorImage.act:86
2 mirror image(s) created by a scroll, every one with a sprite
every mirror image is in level.mobs and in Actor.chars, so it acts and the hero can walk into it
with the headless flag off, GameScene.add(mob) never consults the stand-in factory
```
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
## 21. Descending pays every time, so the hero ping-pongs between two floors and banks it - **CLOSED**
Found by the run that came after the free-step bound, and it is the same shape as everything else in this
`JEA-FKU-WZS` (DUELIST, generation 22, 395 steps, score **+2747**), read with `verify`:
```
floor   turns  kills  gold        score
*1      263    6      0        -1413.00 cleared
 2      2      0      0        +1798.00 cleared
*1      31     1      0         -133.00 cleared
 2      31     0      0        +1630.00 cleared
*1      3      0      0          -15.00 cleared
 2      2      0      0        +1798.00 cleared
*1      41     2      0         -177.00 cleared
 2      6      0      0        +1770.00 cleared
 1      10     2      0        -2510.00 cleared

terms
  DEPTH_ADVANCE           +7200.00
  NO_PROGRESS             -1488.00
  TURN_COST                -465.00
  DEATH                   -2500.00
```
**Nine floor rows, `deepest=2`, and +7200 of descent paid.** Four descents, every one of them to floor 2,
**The cause is one comparison.** `RewardModel.step` pays `DEPTH_ADVANCE` when `depth > prevDepth`, and
**This is worse under `depth_only` than under `default`, and that is the uncomfortable part.** The profile's
**The fix is one condition, and it took the first of the two readings.** `51f369f9f` pays when the descent
Gated by `checkOnlyANewFloorIsPaidFor`, which drives **ten descents with ten ascents** through the real
```
10 descents and 10 ascents reached depth 2 and paid 1800.00 - one reward per new floor, not per stair
```
Reverting the new term fails it with the measurement it is about - `paid 16200.0 where 1800.0 was
**What it cost, and what it bought, is in `issues.md` 22.** Closing this removed every positive score from
`ReplayIO.verify`'s `[WARN] score recorded ... reproduced` now prints on the committed corpus, because
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
## 24. Pressing `r` at the viewer prompt paid a Gradle startup to repaint a list - **CLOSED**
**Measured, on this machine, over the 64 recordings the catalogue lists:**
| | cost |
| --- | --- |
| `:superintelligence:replays` through gradle, daemon warm | **3.4s** |
| the same through gradle with `--no-daemon` | **8.7s** |
| the same code as a plain `java -cp` launch | **0.5s** |
| `replay-viewer --list` now | **0.62 - 0.76s** |
Gradle's entire contribution to that call is resolving a classpath that is **already written to disk** -
**Why it matters is the `r` key and nothing else.** A refresh is what a person reaches for in the one
Gradle is still what **builds**. It is called exactly twice from the viewer's script now: once to build
**Two things got wrong on the way and both are worth keeping.**
1. **The staleness check was not a comparison.** `for %%I in ("%SUPJAR%") do if %%~tI GTR "%RUNBAT%"`
Verified: `replay-viewer --list` output is **byte-identical** to gradle's for the same 64 recordings, and
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
### Where the damage comes from is still open, and cells are the missing channel
The damage leaves **no trace in the roster** - every mob's hp, state, energy and buffs is identical -
**The viewer writes zero `cells` records.** `ReplayPlayer` constructs its `WorldSnapshot` with
### Why 20 of 20 recordings played clean until now
**The corpus had no scroll to read.** The previous `duelist-death-last` used `USE` on food, a
### What is not established
- **What deals the extra HP.** Not the scroll read, not its animation, not the RNG, not the engine
  clock, not the speed - all five measured. The roster is identical, so it is not a mob. Cells are the
- **A fix.** The one that looked obvious - the read animation - was implemented and makes no
  difference, and was reverted.
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
## 25. Every update thread shares one Network, so a gradient is accumulated on whatever survived the last clear - **CLOSED, gated**
Found while reading the metrics of a `default`-profile run (see below), and it is the most consequential
**`ShardedUpdate` never makes the per-thread networks its own design calls for.**
```java
// ShardedUpdate.java:119
while (shards.size() < n) shards.add( new Shard( ppo.network ) );

// ShardedUpdate.java:214-223
Shard( Network network ){
    this.network = network;          // the master, stored - not a copy
    this.grid = new float[ network.gridLength() ];
    ...
}
```
All N update threads therefore run `PPO.oneSample` on **one** `Network`: its parameters, its gradient
### The chain, each step measured rather than read
1. **A tripwire in `Policy.entropy`** printed the offending buffer: `n=32` (the slot head), `out[0]=1.128`,
   ```
   One legal action, mask unmoved, and the single legal logit changed value *inside the function* with no
   ```
   `net` is identical on every one. `probs` differs per sample, because that scratch **is** per-shard -
All instrumentation was reverted; the committed tree carries none of it.
### Why `parallelcheck` did not catch it
`ParallelCheck.reduce` never runs `ShardedUpdate`. It builds its own
```java
Network[] parts = new Network[ shards ];
```
and calls `ParallelFixtures.oneSample` on each in a plain serial loop - its own networks, its own loop,
`PLAN-data-flow.md` §8 lists *"Parallel update silently races on shared state"* as a **high** risk that
### What follows, from reading the source rather than measuring it
- Every shard calls `clearGradients()` on the master, wiping other shards' partial sums.
- `network().addGradientsTo(network())` runs once per shard, all on the same object, so the applied
  gradient is whatever survived the last clear, not the sum over the batch.
- `copyParametersFrom(ppo.network)` is a self-copy.
- Layer scratch - `Dense.preAct`, `Conv2D`'s im2col, `LSTM.gates/h/c` - is shared, so forwards are torn
  between threads. `entropy`, `kl` and `clipFraction` are all read off those torn buffers, which is why
- **Training does not crash**, and that is why nothing looked wrong: PPO tolerates a noisy, badly-scaled
  gradient. The visible consequence is a policy that does not learn.
### What it plausibly explains
Every training run in this project's history used `--update-threads` above 1, so every one of them
| | |
| --- | --- |
| `bestDepth` | **1 in all 77 generations** |
| mean score, first third -> last third | -3,149.81 -> -3,182.23 |
| endings, generation 24 and generation 76 alike | stalled 80 (100%), death 0 |
| generations reporting impossible entropy | 6 (35, 43, 47, 48, 73, 76) |
`kl` of 5.15 and 5.61 on generations 73 and 76, against 0.017-0.04 everywhere else, is the same fault
### The fix, and what it cost
One line in spirit: `Shard` constructs its own `Network` from the config and a seed, exactly as
**It invalidates every checkpoint and every measurement in this file that used more than one update
`parallelcheck` is **5 cases**. The new one asserts the property deterministically - no two shards hold
**Mutation-tested**, by reinstating `new Shard( ppo.network )`:
```
[ERROR]  parallel update: 1 of 5 checks failed
        4 shards hold 1 distinct networks, so at least two update threads shared one Network - its
        parameters, its gradient accumulators and its forward scratch. A shard's clearGradients() then
        wipes the others' partial sums, and addGradientsTo adds the master's own gradient once per
        shard. (issues.md 25)
```
### The entropy half was measured to be unable to fail, and is reported rather than asserted
The obvious second assertion is `ppo.lastEntropy >= 0` - the symptom the whole thing was found by.
So that assertion would have been a check that cannot fail, which is the same failure as no check. It is
### Measured, A/B on the same machine in the same minute
6 workers x 3 episodes, 6 update threads, seed 777, 6 generations each:
| | with the fault | fixed |
| --- | --- | --- |
| turns/s, mean | 804 | **908** |
| wall, all generations | 13.1s | **11.6s** |
| ppo update, all generations | 15.5s | **14.0s** |
| entropy range | 1.775 .. 2.084 | 1.686 .. 2.161 |
| kl, worst generation | 0.0474 | **0.0271** |
| valueLoss, first -> last | 17 -> 2,378 | 21 -> **409** |
**It is faster, and that was not expected.** Six threads writing 3.5M parameters and every layer's
**The memory is real and is not reported anywhere.** Six shard networks is 6 x 14.3 MB = ~86 MB of
**What this does not settle.** The 77 generations in `default-60` were trained on the broken path, and
Evidence, with the full measurement chain: [`out/evidence/entropy-shard-diagnosis.txt`](out/evidence/entropy-shard-diagnosis.txt).
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
## 28. The trainer dies at generation ~22 pushing weights to a worker pipe - **OPEN, blocks runs over ~21 generations**
Found by the first 30-generation run this project has attempted. It gets to generation 22 of 30 and then:
```
[ERROR] training failed: Insufficient system resources exist to complete the requested service
java.io.IOException: Insufficient system resources exist to complete the requested service
	at java.base/java.io.FileOutputStream.writeBytes(Native Method)
	at java.base/java.io.FileOutputStream.write(FileOutputStream.java:360)
	at java.io.BufferedOutputStream.write(BufferedOutputStream.java:183)
	at java.io.DataOutputStream.write(DataOutputStream.java:126)
	at com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainerWorkers.lambda$pushWeights$0(TrainerWorkers.java:258)
	at java.base/java.lang.Thread.run(Thread.java:1447)
```
`pushWeights` writes a **~14 MB** policy blob to each of 20 workers' stdin, one thread per worker, then
**No fix is proposed here.** The obvious one - chunk the write into pieces smaller than the pipe buffer -
**Workaround meanwhile:** segment long runs at 15-20 generations, which is where nothing has failed.
Evidence: [`out/evidence/measure-30.log`](out/evidence/measure-30.log), [`out/evidence/measure-30.csv`](out/evidence/measure-30.csv).
## 29. Which stall guard fires is 100% free-step budget, and the reward says stalling is cheaper than playing - **OPEN, and it is why nothing learns**
`issues.md` 27 established that 98-99% of every episode ends STALLED and left which guard open. There are
Added `StallCause` and `SPDEnv.stallCause()`, set at each of the four sites, plus
```
guards, at the default thresholds:
  stallLimit 120   freeStepBudget 40   actorStepLimit 20000   turnLimitPerFloor 1500

40 episodes, uniform policy, fixed seed per episode
  meanTurns 120.575   deepestDepth 1   STALLED 40

which guard ended them:
  ACTOR_STEP_LIMIT      0    0.0%
  PIPELINE              0    0.0%
  POSITION              0    0.0%
  FREE_STEP            40  100.0%

steps that advanced no engine time, by action (of all steps taken):
  DROP             280 free /   560 used  = 50.0%
  CANCEL           332 free /   332 used  = 100.0%
  INTERACT           3 free /   173 used  =   1.7%
  USE              401 free /   456 used  =  87.9%
  MOVE_S             2 free /   272 used  =   0.7%
  MOVE_E             1 free /   246 used  =   0.4%
  OPEN_INVENTORY   320 free /   320 used  = 100.0%
  REST             323 free /   326 used  =  99.1%
```
**It is the free-step budget, 100% of the time** - not the `stallLimit` position guard, which the
**Half the action set is free.** `CANCEL`, `OPEN_INVENTORY` and `REST` are free essentially always, `USE`
### The chain - corrected, because the middle of it was wrong
The reward is not the problem and was checked first because it looked like it. `stallPenalty = 3000` is
**An earlier version of this entry claimed the -3000 never reaches the policy, because `STALLED` is a
```
delta = rewards[i] + gamma * nextValue * nonTerminal - values[i]
```
so the reward is in the delta on **both** paths. For a final step charged -3000 with `V(s) = 0` and
```
truncated   advantage -2990.1
terminal    advantage -3000
```
Truncation adds the `gamma * V(s_T)` correction term. **It does not remove the penalty, and no critic
This is the same claim, retracted for the third time on this topic, and the fact that it took three goes
**What is actually wrong is the size of the dataset, not the gradient.**
```
floor-1     episodes=1200 meanDepth=1.001  bestDepth>=2 in 1/15 generations
floor-2     episodes=1200 meanDepth=1.000  bestDepth>=2 in 0/15 generations
floor-3     episodes=1200 meanDepth=1.001  bestDepth>=2 in 1/15 generations
measure-30  episodes=1760 meanDepth=1.000  bestDepth>=2 in 0/22 generations
```
**Across 5,360 episodes `meanDepth` is 1.001.** `depthReward` is +1800, the largest single term in the
So the chain is:
1. The free-step budget ends **100%** of episodes at turn ~120 of 1500 granted.
Fixing (2) is what makes (3) and (4) possible. Nothing downstream of it can be judged until the episodes
### Which steps are actually free, by the mode they were issued from
Splitting the same tally by mode says where the free steps go, and most of them are mode bookkeeping
```
  OPEN_INVENTORY from WORLD    320
  REST from WORLD              323
  USE from WORLD               212      <- enters TARGETING, which costs nothing
  CANCEL from INVENTORY        160
  CANCEL from SLOT             156
  USE from SLOT                134
  USE from INVENTORY            55
  CANCEL from TARGETING         16
  DROP from WORLD              280
```
Across ~4,828 steps in 40 episodes, **about 570 advanced the clock: ~10%**. The rest is entering and
**So the constraint is arithmetic, not behavioural.** Moves are the only action that reliably advances
### What is not settled
- **Why `REST` is free 100% of the time.** Already known that it advances no engine time and deferred
  when it broke `checkRestIsNotAOneWayDoor`; this puts the number on it. Resting costs the engine
- **Why `USE` is free 88% of the time.** 212 of those are the `WORLD` -> `TARGETING` hand-off and are
  legitimate. The remaining `SLOT` and `INVENTORY` cases are unexplained and suggest `USE` is offered by
- **Whether the fix is the guard, the truncation, or the mask.** Four candidates, all behaviour changes,
  none attempted here:
  Each changes what the numbers mean. **The measurement above picks (1) on its own**: the episode is too
The instrumentation and the diagnostic are gated by `stallcausecheck`, which drives each of the four
### The four guards are now reachable, and that is what makes this entry checkable
`issues.md` 29 ended with "the instrumentation has no gate". It has one now, and adding it found that
Under the default thresholds a uniform policy ends every episode on `FREE_STEP` and the other three sit
`gradle :superintelligence:stallcausecheck` drives each guard to the only one that can answer:
```
  config      stallLimit       freeStepBudget   actorStepLimit    cause
  defaults    120              40               20000             FREE_STEP (6/6)  expected FREE_STEP  [OK]
  position    1                1000000          20000             POSITION (6/6)  expected POSITION  [OK]
  freeStep    120              40               0                 ACTOR_STEP_LIMIT (6/6)  expected ACTOR_STEP_LIMIT  [OK]
  actorStep   120              40               1                 PIPELINE (6/6)  expected PIPELINE  [OK]
```
`actorStepLimit` of 0 reaches the guard in `settle` and 1 reaches `settleLanding`'s, which is why the
**Mutation-tested twice.** Relabelling the `POSITION` site as `PIPELINE` and then as `FREE_STEP` each
A first version of this check only failed when a row reported *no* guard, which does not catch a
### `REST` and `USE` are not the same kind of problem
Both are free far more often than they should be, and they are **not** both defects.
**`REST` is a known, documented gap.** `ActionMapper.java:244` already says it: *"Resting costs
**`USE` is structural, not a bug.** `USE` is a two-step action: it always moves to `EnvMode#SLOT` first
```
  USE from WORLD      212   <- the mandatory WORLD -> SLOT transition; free by construction
  USE from SLOT       134   <- entering TARGETING for a targeting item
  USE from INVENTORY   55
  advanced the clock   55   (of 456: 88% free)
```
212 of the 401 free steps are the mandated first half of the action. That is not a fault, but it is not
### Test status of the two viewer flags, stated precisely

**Both are now gated**, by `viewcheck` rather than by `gates`: they need a display, and `viewcheck`
is the only harness that has one. Measured: `spd.replayContinueOnDiverge` applies **100** steps to
`duelist-death-last.replay` against **95** without it, and `spd.hidden` reads as `false` without the
property and `true` with it - checked without ever constructing a window. What follows is the record
of how they were established, kept because it is what the gate now asserts.
Both were tested by running them, and **neither is in `gates`**, for a reason worth naming rather than
**`-Dspd.replayContinueOnDiverge`.** Measured on `duelist-death-last.replay`:
```
flag off   (viewcheck)          halts at step 95, 95 steps applied, 2600 frames
flag on    (spd.fast=800)      100 steps applied, 23053 world records
flag on    (spd.fast=40)       100 steps applied, 23060 world records
```
The default path is also evidenced: `viewcheck` still reports the same fixture diverging at the same step
**`-PspdHidden`.** Observed `hidden=false` on the first traced run, which is when a window appeared
Both are honest one-line checks that could be added to `viewcheck`, which is the only harness that can
### Fixed in code, and still not gated - because a gate cannot reach it
`pushWeights` wrote a whole ~14MB policy to each worker's stdin in a single `write`. That is what died at
```
java.io.IOException: Insufficient system resources exist to complete the requested service
	at java.base/java.io.FileOutputStream.writeBytes(Native Method)
	at ...TrainerWorkers.lambda$pushWeights$0(TrainerWorkers.java:258)
```
**Now chunked and retried.** 32KB per write, under a Windows anonymous pipe's 4KB system buffer with
**This is still not gated, and the reason is worth recording because it was expensive to find out.** A
That is the same conclusion this entry reached before the fix, and it was right: **the mechanism cannot be
## 30. Twenty of thirty EnvConfig fields never reached the worker processes - **CLOSED, gated**
The most consequential bug found in this project, and it was invisible in the most literal way: it
`Trainer.writeParams` and `Worker.readParams` were two hand-maintained lists of the same ten fields. They
The worker then built its own `EnvConfig` and kept its own defaults:
```java
private void readParams() throws IOException {
    if (config == null){
        config = new EnvConfig();
    }
    config.maxSlots = in.readInt();
    // ...ten fields...
```
So every `SPD_*` and `superintelligence.properties` override was applied to the trainer, printed as
```
actorStepLimit      allowAlchemy         allowEquipping      allowTrading
curriculumProgress  damagePenalty        exploreReward       freeStepBudget
freeStepCostFactor  goldReward           honourVisionBuffs   killReward
maxHpReward         maxViewDistance      mimicsVisibleOutOfFog persistSaves
sneakAttackReward   stallPenalty         talentReward        turnLimitPenalty
```
**Every reward term except four.** A `.env` that halved `damagePenalty` or set `killReward` would have
### How it was found, and why the experiment could not run without fixing it
The experiment this session set out to run was "raise the free-step budget and see whether descent
That is not a wrong value. That is a knob doing nothing. After the fix, the same override gives
**The experiment that would have shown 40 was fatal could not be run at all until this was fixed**,
### The fix, and why it cannot recur
`EnvConfig` now owns the wire format:
- `writeTo(DataOutputStream)` - every field `copy()` copies
- `readFrom(DataInputStream)` - the mirror
`Trainer.writeParams` and `Worker.readParams` are each three lines that call them. **One list, two callers,
`ConfigCheck` gains `checkEveryConfigFieldTravelsToAWorker`, which asserts three things because they fail
### What it was costing
`free_step_budget` was 40 in the trainer and 40 in the worker **by coincidence** - the compiled default
See `issues.md` 29 for what the budget does, and the `EnvConfig` javadoc for the measurement that moved it
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

## 32. A free action could be repeated for ever, and a talent point could never be spent - **CLOSED, gated**
Found by reading a code review against the code, and confirmed by measurement before anything was
changed.

`issues.md` 29 established that `FREE_STEP` ends 100% of episodes and that `REST` advances no engine
time. Two distinct faults sat behind that, and only one of them is a reward-arithmetic question.

### `REST` spent nothing, and the reason is not the one it looks like

`ActionMapper.apply` set `hero.resting = true` and called `hero.next()`. The rest turn is taken by the
rest branch of `Hero.act`, and `LevelPipeline.runToHeroReady` returns as soon as
`curAction == null && hero.ready && !paralysed` - which is true immediately, because neither
`spendConstant` nor `next()` clears readiness. So the hero is **never picked**, the rest branch never
runs, and the turn is paid by whichever later step happens to pull `Actor.now` forward. Measured:
**323 of 326 rests advanced zero engine time.**

The obvious fix - `hero.spendConstant( Actor.TICK )` - is wrong, and measuring it is what showed why:

```
world before REST      40 used, 40 free (100.0% free)
world before WAIT      79 used,  0 free (  0.0% free)
```

`Actor.now()` is set by `Actor.headlessStep()` from whichever actor it picks, so advancing the hero's
own clock is not enough: the drain has to keep stepping until the hero has acted, and that needs
readiness cleared. `Hero.spendAndNext` clears it (`busy()`), and `WAIT` already used it. So `REST` now
does the same, and does not leave the hero resting: `Hero.act`'s rest branch never calls `ready()`, so a
hero left resting is the one-way door `PLAN-reward-signals.md` 7 recorded, and it is the *drain*, not
`REST`, that has to end it.

### The menu loop, and why a mask rather than a price

`OPEN_INVENTORY`, `USE` and `DROP` are the only entries into a sub-mode and all three cost nothing;
`CANCEL` is the only way out of two of them and also costs nothing. The pair is a loop that advances no
engine time at all, and **every step of it is charged against `env.free_step_budget`** - so an
exploring policy spends its whole budget without the world moving once. This is the arithmetic
`issues.md` 29 measured as "about 570 of 4,828 steps advanced the clock: ~10%".

After a step that leaves a sub-mode without moving the clock, the next `WORLD` mask refuses all three
entries **for exactly one step**. The actions stay legal, the agent just cannot chain them without
spending a turn in between, which is what a player does too. Priced instead of masked was rejected:
`OPEN_INVENTORY` is free in the real game as well, and `freeStepCostFactor` already charges it.

### Measured, same config and seeds, defaults unchanged

| | before | after |
| --- | --- | --- |
| `meanTurns` | 626.8 | **800.7** |
| ended `STALLED` | 21 of 40 | **15 of 40** |
| `REST` advancing no engine time | 323 of 326 | **1** |
| longest run of consecutive free steps in the menu loop | unbounded | **2** |

### `freeactioncheck`, seven cases, each mutation-tested

| case | fails when |
| --- | --- |
| `REST` advances the engine clock | `spendAndNext( Actor.TICK )` is reverted to `next()` |
| opening a menu costs no turn | `OPEN_INVENTORY` is made to spend time, which would hide a second defect behind the mask |
| a zero-time menu exit masks the three entries | the mask block is disabled (2 of 7 go red) |
| the refusal lasts exactly one step | the flag is never cleared |
| the menu loop cannot run on free steps alone | the mask is disabled (2 of 7 go red, 0 of 240 steps advancing the clock) |
| `TALENT` is offered only with a point to spend | the mask and `TalentPicker` disagree |
| a talent point is spent and pays | any of the mode, the head mask, the choice or the reward emission is broken |

The last case drives `SPDEnv.step` rather than `TalentPicker`, because "the picker is callable" and "the
agent can reach it" are different claims: the mode, the action mask, the slot-head mask and the
secondary choice all have to line up as well.

### The talent point

`RewardTerm.TALENT_UPGRADE` is wired, gated and was unearnable: spending is `TalentButton` ->
`TalentsPane` -> `WndHero`, all of which need a renderer. `Action.TALENT` opens `EnvMode.TALENT`, where
the slot head enumerates `env.TalentPicker.available(hero)` - the game's own order, tiers ascending and
`LinkedHashMap` insertion order inside a tier, so a recorded index names the same talent in the trainer
and in the viewer. The first point arrives at **level 2**: two rats on floor 1.

## 33. The recurrent gradient never crossed a timestep, and the critic was asked for thousands - **CLOSED, gated**

Both were found by reading a code review against the code, and both had been hiding behind numbers that
looked healthy: `policy=`, `value=`, `kl=` and `clip=` were all reported every generation, and none of
them says anything about either.

### The gradient was truncated at length one

`PPO.update` shuffled the buffer and backpropagated each stored sample in isolation, with `dPrev` and
`dCell` cleared between them. Two consequences, and the first is the one that matters:

- Nothing crossed a timestep, so `LSTM.W_hh` received **no gradient at all** for retaining anything
  forward. In a partially observed grid game that is the entire reason the recurrent layer is there:
  the agent is asked to remember where the stairs were across dozens of turns it cannot see.
- The samples were also coupled, sample *i* depending on sample *i-1*, which is why the parallel update
  had once been blocked.

The buffer now groups into contiguous runs and backpropagates each in reverse without clearing between
steps. Contiguity cannot be recovered from a shuffled buffer, so the worker stamps an episode id and a
step index onto every retained step and both travel the wire; a gap - the sampling rate dropping a step,
a floor transition restarting the clock - starts a new run, because carrying a gradient through a state
the agent never saw is worse than not carrying one.

`bpttcheck` is the gate, 7 cases. Its first case is built so the answer cannot be argued with: the loss
sits on the second step only, so the sum of the isolated steps is exactly that step's isolated gradient,
and a length-one truncation reproduces the sum exactly. Measured **63.7% apart** with the fix and
**0.00000** with the accumulation reverted.

The first version of that case compared two gradient *norms* instead, and was measured to pass the
mutation - two different steps have two different local gradients, so the norms differ under a
truncation too. Recorded because it is the fifth time this repository has had a case that could not
fail, and the fourth time the replacement was a construction rather than a comparison.

### The critic's targets were three orders of magnitude out of range

The reward function is priced for the agent, not the optimiser: a stall costs 3000, a death 2500, a
descent pays 1800. The critic's output is linear so it can represent those, but the trunk is shared with
the actor, and a squared error of 3000^2 produces a gradient two orders of magnitude above the policy
gradient's. Global clipping then squashed the actor's update towards zero - which is why the policy was
not moving and nothing said so.

Value targets are normalised by a running estimate of the return's scale, the residual is clipped and
the loss is Huber. A batch of -3000 and +1800 now normalises to at most 1.0. A three-generation run
reports `value=0.62`, `1.06`, `0.56` where the old figures were 12,432 to 31,323.

`gradcheck` caught a fault of its own on the way, which is the fourth counter-example in `testing-guide`
and the same shape as the rest: it reported finite-difference noise as a wrong gradient, because the
relative error was measured against a denominator that can be arbitrarily small. The noise is now
measured - the same central difference at two step sizes, whose disagreement *is* the noise - and an
entry passes when the analytic gradient agrees to within it. Removing a `tanh` derivative still fails
five entries at relative errors of 0.23 to 0.50 against noise of 0.001 to 0.02.

## 34. A descent was unreachable rather than unrewarded - **CLOSED, gated**

`meanDepth` was 1.001 across 5,360 episodes, and `depth_reward` of +1800 is the largest single payout in
the function, so it was paid in roughly five of them. Two things were wrong, and neither was the reward
arithmetic.

**Nothing pointed at the stairs.** A regular floor holds several hundred walkable tiles; a random walk
onto the exit succeeds about 0.1% of the time. `RewardTerm.STAIR_POTENTIAL` now pays
`gamma*Phi(s') - Phi(s)` with `Phi` the negative breadth-first distance to this floor's exit, so the
objective has a gradient without becoming farmable: the term telescopes over an episode, and the only way
to earn is to arrive. Measured by `rewardcheck`, which is 26 cases: 4 steps closing the gap pay
**+8.12**, 4 steps opening it cost **-5.82**, and a closed round trip nets **2.30** where a per-distance
reward would net the gross. `reward.stair_potential_weight` = 0 turns it off.

**And nothing remembered them.** `GridChannel.TRANSITION` lights only while the stairs are in view, so a
hero that walked past and away carried nothing about where they were - which is exactly what the agent
needs and least often has in front of it, and exactly what the recurrent gradient now has to carry.
`GridChannel.EXIT_BEACON` lights the cell the hero has already stood where the stairs could be seen. It
is not lit from generation: that would hand over the objective for free and quietly change what a run
measures.

**The inventory, too.** `env.max_slots` was 32 against the game's ceiling of 96 - a 20-slot backpack plus
four 19-slot bags. The truncation was invisible rather than refused, because the head is a fixed-width
window and everything past it simply was not in the observation. `issues.md` 7 recorded the figure and
left the fix open on the grounds that the obvious one was wrong; the obvious one was right, and what the
gate needed was for the gap to be measured. It widens the network's input, so it invalidates
checkpoints trained at 32.

## 35. Twenty-six settings were Java literals, and `.env` was read by nothing - **CLOSED, gated**

A code review listed them as hardcoded values that should have been settings: worker JVM heap and GC
pause, the pipe and weight-push chunk sizes, the episode tail length, the gradient clip and Adam's three
constants, the value loss weight and residual ceiling, the random-seed leak rate, the checkpoint and
record intervals, and the dialog aging step. All are now settings, and `configcheck` is 10 cases over
63 keys.

Two of them were worth more than the setting. **`train.random_seed_rate` at 0** is what makes two runs of
one configuration comparable, which they otherwise are not - three repeats of one config and one fixed
seed put `bestScore` 1,595 points apart and `bestDepth` on 1 or 2 at chance. And **`env.max_slots`** was
the only field `EnvConfig` was truncating without saying so.

`.env` now sits between the properties file and the process environment, which is where the
documentation has always said it sits and where it never sat at all: the JVM does not read one, so the
documented workflow was a shell one-liner, and a developer who skipped it ran with settings they did not
believe they were using - silently, because nothing in the report said the file had been ignored.

`.env.example` had drifted to the pre-rescaling values - `depth_reward` 25 against the compiled 1800,
`turn_cost` 0.002 against 1.0, `max_slots` 32 against 96 - so copying it configured a run the module does
not run. `configcheck` already compared the shipped properties file with the compiled defaults in both
directions; the same check is what says every documented key changes the setting it names.


## 36. The first depth-2 run that verifies - **measured, and it is one run, not a trend**

Twenty generations, 8 workers, 2 episodes each, 4 update threads, seed 20261016, every default unchanged.

| | generations 0-4 | generations 5-19 |
| --- | --- | --- |
| `bestDepth` | 1 in 5 | **2 in 8 of 15** |
| `meanDepth` | 1.000 | 1.057 |
| `valueLoss` | 0.86 to 1.24 | 0.43 to 0.86 |
| `kl` | 0.005 to 0.09 | 0.003 to 0.025 |

**Eight of twenty generations reached depth 2**, where `issues.md` 27 recorded `meanDepth` 1.001 across
5,360 episodes and `bestDepth` landing on 1 or 2 at chance with three repeats of one config disagreeing.
`meanDepth` over the run is 1.05 against 1.001.

The run also completes, which it did not used to: `issues.md` 28's policy push killed runs at generation
~22, and 20 generations is inside that window.

**A depth-2 recording verifies exactly.** `trainer-replays/bptt-stairs/CLL-ZXD-MTZ.dat`, ROGUE,
generation 19, 438 steps, depth 2, score -2025.74, reproduced to the last decimal. So the descent is a
real trajectory and not a reporting artefact - which is the difference between this and the earlier
"bestDepth 2" claims that `issues.md` 27 retired.

### What this does not say

It is one run, one seed, twenty generations, and `TODO.md` 1 is explicit that **the noise floor is
plus or minus 16 points of `meanScore` over fifteen generations**, so nothing here clears it.
`meanDepth` at 1.05 is a move in the right direction and not a measurement of learning. `bestDepth` on 2
is what four runs in five would produce by chance on a run that sometimes descends.

What *is* measured, and is not a matter of luck:

- the free-step budget is no longer what ends every episode - `stallbreakdown` puts `FREE_STEP` at 37.5%
  of uniform-policy episodes against 100%, and `meanTurns` 626.8 to 800.7;
- the critic's loss is O(1) rather than O(10,000), so the shared trunk is no longer clipped flat;
- the recurrent gradient crosses timesteps, which `bpttcheck` forces rather than infers;
- a descent leaves a recording that reproduces.

The next measurement is the one `TODO.md` 1 has always asked for: a run long enough to clear the band.
Evidence: `out/evidence/train-20gen.log`, `out/evidence/depth2-run.dat`,
`out/evidence/stallbreakdown-after-fix1.txt`, `out/evidence/viewcheck-final.txt`.

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

Evidence: `out/evidence/gen29-divergence.txt`, `out/evidence/gen29-LZJ-LNO-HLE.dat`,
`out/evidence/gen29-LZJ-LNO-HLE-r2.dat`, `out/evidence/noise-floor-30.csv`,
`out/evidence/train-noise-floor-30.log`.