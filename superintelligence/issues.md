# Viewer / recordings

1. When using replay-viewer.bat, after a recording finishes and the user presses 'R', after reset the recording still says finished. When the user then tries to press spacebar to continue, the game thinks it's the waiting keybind and the recording doesn't play, instead diverges due to wait action. (closed)

2. When using replay-viewer.bat, the viewer doesn't actually play out the last step where the hero dies. Instead it finishes just before that. (closed)

3. Turns and turn limit is a bit misleading, since it's actions, as turns in the engine are often not whole numbers and certain items and conditions can affect the amount of turns an action takes. (closed)

4. The recordings are missing information about how the score changes with each action. Very important to see in the viewer as a live info. Additionally there should be a way to see total score gained and lost as well, not just their sum. (closed)

5. Recordings show coordinates (such as during diverted) as a single number. It'd be much easier for a human to troubleshoot if they were in x,y. With x increasing to the right of the screen and y increasing with height, but staying true to engine. So highest y value is at the bottom. (closed)

# Env

> **Found while closing 6, and left open rather than silently absorbed into it.** `env.grid_height`
> is documented in `superintelligence.properties` and read by `EnvConfigBinder.java:233`, but the
> encoder is the only thing that uses it. `ObservationEncoder.java:57` allocates
> `spatialCount * gridWidth * gridHeight`; `Network.java:93,129`, `Conv2D`, `TransitionCodec.java:47`,
> `PPO.java:156`, `EpisodeCollector.java:138`, `GradientCheck`, `ParallelFixtures` and `GaeCheck` all
> use `gridWidth * gridWidth`, and `Conv2D` is square by construction. Setting `env.grid_height=64`
> at the shipped `grid_width=48` therefore allocates a 48x64 encoder feeding a network that expects
> 48x48, and the mismatch surfaces as a shape error far from the key that caused it. Harmless at
> 48x48, which is why nothing has reported it.
>
> None of `env.max_slots`, `env.grid_width` or `env.grid_height` has a range check either — `EnvConfig`
> has no `validate()` and `PpoHyperparameters.validate()` covers only `rl.*` — so `env.grid_width=0`
> is accepted by the binder and fails later as a negative array or a degenerate convolution.

6. I am unsure if the env.gid dimensions are accurate. I read online that the max is 32x32. This number might've changed but a code sweep might be in order. (closed)

    The quoted text is wrong about this codebase on the part that matters. Regular floors are not 32x32
    and are not hardcoded at all: `RegularPainter.java:113` ends level generation with
    `level.setSize(rightMost + 1, bottomMost + 1)`, where `rightMost`/`bottomMost` are the extent of the
    rooms that just generated plus padding. The floor is exactly as big as its own layout. What *is*
    fixed-size is the hand-built boss and end floors, and those are the 32s the quote is probably
    remembering: `HallsBossLevel` and `PrisonBossLevel` are 32x32, `CavesBossLevel` 33x42,
    `CityBossLevel` 15x48, `LastLevel` 16x64, `DeadEndLevel` 7x7.

    Measured rather than read off: 1040 floors over 40 seeds at depths 1-26.

    ```
    max width   = 67      max height  = 81      max single side = 81
    samples with a side > 48 : 419 / 1040
    samples with a side > 64 :  17 / 1040
    samples with a side > 96 :   0 / 1040
    ```

    **So 48 does under-cover 40% of floors by extent — and that does not make the agent unprepared
    for them.** This is the part the report's framing misses. The agent never sees the whole floor.
    `ObservationEncoder.buildSpatial` samples a hero-centred window and slides it:
    `originX = hero.pos % width - MARGIN`, `spanX = gridWidth + MARGIN * 2`, so on a 67-wide floor
    `stepX = 52/48 = 1.083` and the window subsamples at ~92% scale. The hero is always in the middle
    of a fixed-size window, the window always covers his 8-tile view radius several times over, and the
    per-pixel clamp at `ObservationEncoder.java:126-132` means no floor shape can read it out of
    bounds. A floor being 81 tall is not a floor the encoder cannot represent; it is a floor it
    represents as a 48-cell window rather than as a map, which is the design and was never in question.

    What is actually wrong is narrower, and is left open rather than folded into this closure:
    `EnvConfig.java:16` says 48 "covers the widest floors", and the measurement above says it does not.
    `env.grid_height` is also a documented key that nothing implements — see the note under §Env.

7. The inventory slots / inventory system seems wrong. (investigated - see `TODO.md` 0.8)

    Confirmed, and the quoted ceiling of 96 is the right one. Bags are obtainable and already present in
    every run - `HeroClass.java:111` gives every hero a `VelvetPouch` at start and `ShopRoom.java:361`
    stocks the other three - so this is not a wiki-only claim. `Backpack.capacity()` is 20 plus one per
    bag, each bag is 19, and `Bag`'s iterator recurses, so 96 items are reachable and
    `env.max_slots=32` addresses 32 of them. The truncation is silent: `ActionMapper.java:91` drops the
    overflow with no report and the mask claims every slot is legitimately full.

    Not closed, because the fix is not the obvious one and `TODO.md` D7 turns out to claim a
    capacity-independence the implementation does not have. Costs, alternatives and the reason for not
    re-architecting before anything has trained are all in `TODO.md` 0.8.

    "
What is the max inventory space available with all of the expansions?

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
   items one way: `items > prevItemCount` paid out and a decrease paid nothing, so `INTERACT, DROP,
   INTERACT, DROP` raised the score on every pickup while the world did not change. It is now a net
   diff of what the inventory is worth, in both directions, with a new `ITEM_DROPPED` term for the loss.
   Gated by a `rewardcheck` case that drives the real environment; mutation-tested by removing the
   refund. See TODO.md 0.2.

   Note that `duelist-mid` no longer contains a `DROP` step at all — the corpus has been regenerated
   twice since this was filed, so the evidence this report cites is gone. The fault was in the reward
   function rather than in that file, which is why the case drives the environment and not a recording.

9. Critical: The same duelist-mid recording shows him descending to a new floor, with full health too. But immediately as he descends, the recording ends as stalled. Is this a bug with the STALLED state detection? Also stalled should punish as much as death to restore balance between these two endings. (closed)

   It was a bug in stall detection, and there were four faults behind it. `Game.switchScene` raises a
   request and a real game turns it into "a scene wants to take over" in `Game.step()`, reached from
   `Game.render()` — which never runs headlessly, so the acknowledgement was never raised, the pipeline
   never saw the hand-off, `Actor.headlessStep()` had already stopped dead, and the drain ran out its
   budget and reported `STALLED`. Fixed, and it explains why `bestDepth` had been 1 in every generation
   of every run: the one action that would have moved the agent ended the episode. Three faults it was
   hiding: `DEPTH_ADVANCE` could not fire at all, the environment drained where the game does not, and
   `Actor.fixTime()` was missing from the pipeline. Gated by `transitioncheck`, 7 cases, mutation-tested.

   `STALLED` is now `-deathPenalty`, through the same constant `DEATH` uses. That reverses
   `PLAN-reward-signals.md` §3.2, and the reason it is defensible now when it was not then is recorded
   there and in TODO.md 0.3.

   Descending now works. **Climbing back up does not** — see 10, which was found on the way.

10. A run that descends and then walks back up the stairs diverges from its recording, and the viewer
    stops on screen with "Cannot read save file". Found while closing 9; `duelist-mid` is the only
    recording in the corpus that descends, and it does this on its second floor change. (closed)

    Both environments now agree. The "Cannot read save file" half was already closed before this was
    filed — `ReplayPlayer` clears `Dungeon.generatedLevels` on the frame a transition is pending,
    because `InterlevelScene.ascend()` reads a floor off disk whenever the depth is in that set and
    descending is what puts it there — and that fix removed the error window and the hang without
    touching the divergence.

    The divergence was neither `Mob.holdAllies` nor `Dungeon.saveAll()`, both of which were the prime
    suspects and neither of which the pipeline ever needed: the regenerated floor came back identical
    in both environments, roster for roster.

    **The fault is that the environment never gave the hero a turn on the new floor.** The hero arrives
    where everything on the floor is at time 0, so the game's own scheduler picks him for exactly one
    act before the player can act at all, and `Hero.act()` is where `Hero.checkVisibleMobs()` runs. A
    mob seeing the hero for the first time calls `Hero.interrupt()`, which throws away whatever action
    is pending. Injecting the agent's next action first therefore has it eaten by a rat noticing the
    hero, and the recording records a turn that moves nobody:

    ```
    step 159  INTERACT  pos 282  engine time 0.0   <- the ascent
    step 160  MOVE_SE   pos 282  engine time 0.0   <- the trainer: interrupted, no time spent
    step 161  MOVE_SE   pos 317  engine time 1.0   <- the viewer, one action later
    ```

    `SPDEnv.settle` now gives the hero that act after servicing a transition, and it is one act rather
    than a drain because `Hero.act()` with no action pending calls `ready()` and returns without
    spending anything — which is also what stops the loop on its first iteration. This is the sixth
    instance of the class `PLAN-viewer-fidelity.md` §6 names.

    Gated by two new `transitioncheck` cases, 7 -> 9. The first drives a real descent *and* ascent and
    then asserts the agent's first action on the floor it comes back to actually moves the hero; the
    second asserts the ascent is serviced at all, which no gate had ever exercised — every one of the
    other seven descends. Both are mutation-tested: removing the landing act fails the first with "the
    agent's first action on depth 1 moved nobody", and refusing to service an ascent fails the second.

11. `duelist-mid` played by hand at speed 1 in a fullscreen window halted at step 25 with `engine time is
    21.0, recording says 22.0` — one turn short, position and health identical. Not reproduced: not on a
    re-run, and not in ~50 attempts including 20 concurrent visible children. Recorded as open in
    `TODO.md` §0.7 with the hypothesis and with what a green `viewcheck` does not cover. (closed, not reproducable after many replays of the same recording)

12. The viewer creates `bones.dat` in the player's own profile. (closed)

    A recording that ends in death kills the hero for real, so `Bones.leave()` writes the remains file
    exactly as it would for a playthrough — and that file is read during level generation, so the next
    real run opens on a heap belonging to a hero who only existed inside a recording. Three of the
    seventeen recordings end in death, so this is the normal case.

    `Bones.clear()` is in-memory only and says so; the caller that removed the file,
    `RunState.forgetPreviousRemains`, guarded its delete on `instanceof HeadlessFiles`, which is false
    under the viewer — so it deleted nothing, silently. The delete now resolves through
    `FileUtils.getFileHandle`, the same resolver `Bones` writes with, because the game's root is
    `External` on a normal launch and `Absolute` under `-Dspd.fileRoot`, so `Gdx.files.local` is wrong
    for both.

    The viewer half is separate: the cleanup has to be where playback *ends*, which is `halt()` and not
    the frame after it — a headless driver stops calling `update` the moment playback stops, so a
    next-frame cleanup would pass every rendered run and never run in a gate. Gated by `playbackcheck`
    case 21, mutation-tested, and the case states that it cannot reach the real-backend branch.

13. Duelist-mid ends with stalled without really being stalled? What's causing this? It didn't seem like the hero was idling for this many actions. Investigate. Worst case: Remove stalled and regen the recording? thx. (investigated - not fixed, `TODO.md` 0.10)

    The instinct is right and the conclusion "remove stalled" would be wrong. Replaying `duelist-mid`
    through the real environment, step by step, with the engine clock sampled on each one:

    ```
    step 286 USE/0  mode=SLOT   pos=317  hp=9  eng=12.0  dEng=0.0
    step 287 USE/0  mode=WORLD  pos=317  hp=9  eng=12.0  dEng=0.0
    ...
    step 295 USE/0  mode=WORLD  pos=317  hp=9  eng=12.0  dEng=0.0
    120 consecutive steps spent ZERO engine time (first at step 176)
    ```

    **The hero was not idling, and it was not doing anything either.** It was oscillating `USE` on a
    Rapier, alternating SLOT and WORLD, for 120 steps. Slot 0 held a Rapier - an `EquipableItem`, not a
    targeting item - so `SlotAction.use` routes to `toggleEquip` (`SlotAction.java:77`), which equips, then
    next step unequips, then equips. `hero.next()` releases the hero, the drain returns READY, and the
    turn resolves without the clock moving. `checkFloorLimits` (`SPDEnv.java:504`) sees an unchanged
    position and unchanged HP, counts 120, and terminates.

    So `STALLED` is a **true** verdict on a run that made no progress. The guard is not broken. What is
    wrong is that the environment let a **zero-cost action** repeat 120 times before noticing — and
    equip/unequip costs zero time in the real game too: `WndUseItem.onClick` calls `item.execute` and
    nothing else, spending no turn. The policy found a genuinely free infinite action.

    **Therefore removing STALLED, as the report suggests, would not help and would hide this.** Without
    the guard the run continues for `turnLimitTotal=40000` more zero-cost steps. The fix that does help is
    to make the stall guard count engine time rather than only position and HP, so any zero-cost loop
    terminates and - more usefully - is *labelled* as what it is. Not done; it changes termination
    behaviour for every recording and needs its own gate.

    ### `cleric-mid` is a different fault, and shares no cause with the above

    Also `termination=STALLED`, 49 turns against a 150 cap, ending `resting=true`. But it is spent real
    engine time (`dEng` 1.0-3.0 per step) and it moves: position 427 → 463 → 427, HP 12 → 11 → 12. It
    never accumulates 120 same-cell steps, so `checkFloorLimits` cannot be what ended it. It is the
    resting backstop at `LevelPipeline.java:259` — `!wantsMore && steps > 8 && Dungeon.hero.resting` —
    firing on a hero who was resting legitimately and was about to heal.

    Two `STALLED`s, two producers, one header field. `Replay.termination` (`Replay.java:168`) is a bare
    name with no distinguishing detail, so the corpus cannot be triaged from it, and the viewer cannot tell
    a free-loop stall from a resting backstop from a `STEP_LIMIT` drain exhaustion. **Worth recording
    before either fix**: the recorded reason should say which guard fired.

    ### What this means for the recommendation to regenerate the recordings

    Regenerating would work and would fix nothing. The policy that produced `duelist-mid` will produce it
    again, because the free action is still there — `duelist-mid` has already been regenerated twice during
    earlier work, which is why `issues.md` 8's evidence no longer exists in the file. The recording is a
    symptom; the loop is the fault.

    ### Partly acted on: the loop is priced, the guard is unchanged

    `reward.no_progress_penalty` charges every step that advanced no engine time, so those 120 free steps
    now cost 0.05 each rather than 0.002 — and the policy has a reason to prefer something that costs
    engine time. See `TODO.md` §1.9.

    **The stall guard itself is deliberately unchanged**, and that is the part worth being explicit about
    because the report above correctly identifies it as the real fix. Making `checkFloorLimits` count
    `Actor.now()` instead of only position and HP changes *when recordings end*, which invalidates the
    committed corpus and needs its own gate. Pricing the step achieves the objective — the loop stops
    being free — without moving any boundary, so `duelist-mid` still plays clean and `viewcheck` is
    still 17 of 17. The guard change stays open at `TODO.md` §0.10.

14. Nothing has ever left floor 1, and the reward function is not asking for it. (closed as a cause;
    the milestone itself is `TODO.md` 1.4)

    Three faults, and each is a property rather than a size — which is why none of them is visible in a
    loss curve.

    1. **Exploration outbid a descent.** `reward.explore_reward` 0.05 against `reward.depth_reward` 10,
       on a floor holding several hundred walkable tiles (measured: the largest of 1040 sampled floors is
       81 tall, and 419 exceed 48 on at least one side). Mapping a floor without touching the stairs paid
       more than descending did. The agent was paid to wander and it learned to wander; this is what
       `bestDepth` being 1 in every generation of every run was measuring. Now 0.01 against 25.0.
    2. **Killing paid nothing and being hit cost something.** `RewardLedger.countKill()` was written and
       never called, and `prevKills` was snapshotted and never compared — so walking past everything was
       optimal, which is the shape every recorded death has. Now emitted from `Statistics.enemiesSlain`,
       with `SNEAK_ATTACK` above it because a surprise attack is the one combat outcome that costs no
       health.
    3. **Two of the four terminal outcomes were cheaper than the run they ended.** `TURN_LIMIT` was
       `-turnCost * 100` — a fifth of a point against a +10 descent — so idling out a floor while banking
       exploration was a positive-value strategy. And `STALLED` cost exactly what dying cost, which makes
       quitting and losing one decision with two labels.

    **Measured: survival improved, depth did not.** Deaths 100% → 19% and mean turns 377 → 1335 over 12
    generations; `bestDepth 2` once in a smoke test. Depth does not trend yet, and saying so is more
    useful than a run long enough to produce a number that could be read either way. `TODO.md` 1.4.

15. Recordings written by the trainer cannot be played in the viewer at all, and `verify` reports them
    as perfect.

    Asked whether the depth 2 run could be watched now that the replay wire was fixed. It cannot:

    ```
    [replay] installed, driving BMS-WQQ-LQH (655 steps)
    [replay] halted: step 1 names slot 3 but records no quickslot bindings; it was recorded
    before bindings were stored, so the index cannot be resolved
    [replay] 1 frames, 1 applied a step, 0 did not
    ```

    Two separate faults, found in that order, and the first one is the reason the second was invisible.

> **`verify` and the viewer disagree about the same file.** Run minutes apart, on the same recording:
>
> ```
> verify:      [OK]  replay reproduced in 655 steps, 494 ms
> viewer:      halted at step 1, 1 frame played
> ```
>
> `ReplayIO.verify` guards each comparison with the sentinel that field declares for *not recorded*,
> and empty quickslots is such a sentinel, so it skips the check. The viewer treats missing bindings as
> fatal the moment a step names a slot index, because there is nothing to resolve the index against.
>
> **A passing `verify` is therefore not evidence that a recording is watchable**, and `verifyall` never
> asserted it — the gate covers recordings the corpus built through `ReplayRecorder` directly, which
> have always had their bindings. This is the same sentinel weakness as `TODO.md` 1.12, one level up:
> a guard written to let an unrecorded field pass cannot distinguish *not applicable* from *lost*, and
> it cannot be the only thing standing between a recording and a viewer.

> **Scope, measured rather than assumed: trainer output only, and all 9 of it.** The committed corpus is
> 17 recordings, all complete —
> `USE 0 SLOT 1272 -0.502 Waterskin:1 20 3.0 VelvetPouch():1,Waterskin:1` carries quickslots, hp, turn
> and inventory — because `replay-viewer --record` goes `rollout --save` → `ReplayRecorder` and never
> crosses the worker pipe. `Worker.writeReplay` sent four of the nine step fields `Replay.Step` declares,
> so `quickslots` was among the five dropped, for every step of every recording, unconditionally.
> Fixed in `018abbd65`, where both sides go through `train.ReplayCodec`.
>
> Every trainer recording on this machine, and where each one stops. Not recoverable by regenerating
> the wire — the data was never recorded, and only a new run records it:
>
> | recording | steps | first slot-bearing step | quickslot bindings |
> | --- | --- | --- | --- |
> | `BMG-HZS-GGF` | 1005 | 1 | 0 |
> | `BMS-WQQ-LQH` | 655 | 1 | 0 |
> | `GFW-GHG-BTT` | 1260 | 5 | 0 |
> | `JLD-WRQ-GXJ` | 70 | 10 | 0 |
> | `MDC-JVK-QVB` | 1373 | 10 | 0 |
> | `NNN-NYP-PNH` | 389 | 1 | 0 |
> | `QVT-MFY-JTM` | 1458 | 10 | 0 |
> | `XIM-LFA-MNY` | 986 | 2 | 0 |
> | `ZFW-GJP-VQC` | 1205 | 10 | 0 |
>
> `%TEMP%\spd-run1\replays\`, all nine written before the fix. A recording with no slot-bearing step
> would be unaffected, but none of these is one — so all nine stop inside the first ten steps, and
> "halts at step 1" is a property of `BMS-WQQ-LQH` and `BMG-HZS-GGF` specifically, not of the fault.
> The fault halts a recording at its **first** slot-bearing step.
>
> **These are `%TEMP%` paths and will not survive a reboot.** Copy any that are worth keeping into
> `replays/` before the machine is restarted; they are the only evidence of what the trainer was
> actually writing, and the corpus cannot reproduce them because it never went through the wire.

> **And the fix exposed a third fault rather than closing this one.** The one post-fix recording,
> `MSU-OTL-OAT` (660 steps, `%TEMP%\spd-wire\replays\`), clears the bindings problem — 18 steps in,
> where the old ones stopped at 1 or 2 — and then diverges:
>
> ```
> [replay] halted: DIVERGED at step 18 - DROP/1 in SLOT: inventory is
> [ThrowingSpike:1,VelvetPouch():1,Waterskin:1], recording says [Food:1,VelvetPouch():1,Waterskin:1]
> ```
>
> Not a near-miss; different items. This was never a passing check either — the inventory comparison
> was being skipped against an empty sentinel, so trainer recordings have been making an unverifiable
> claim about inventory throughout. `ReplayRecorder.record()` captures quickslots *before* the step
> (documented at `ReplayRecorder.java:64-65`, because an equip in that step rebinds a slot) while
> `afterStep()` captures inventory *after* it; whether playback compares the two at the same instant is
> the open question, and `DROP` in `SLOT` is exactly the shape that would expose a mismatch.
>
> Consequence for the milestone: no recording the trainer has produced is watchable end to end yet,
> which means the viewer cannot yet be used to diagnose training behaviour the way the corpus is used
> to diagnose recorded behaviour. `TODO.md` 1.12 covers the wire; this is the two things that were
> hidden behind it.
