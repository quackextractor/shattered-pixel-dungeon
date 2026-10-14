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
    as perfect. (closed)

    Asked whether the depth 2 run could be watched now that the replay wire was fixed. It could not:

    ```
    [replay] installed, driving BMS-WQQ-LQH (655 steps)
    [replay] halted: step 1 names slot 3 but records no quickslot bindings; it was recorded
    before bindings were stored, so the index cannot be resolved
    [replay] 1 frames, 1 applied a step, 0 did not
    ```

    Four faults, found in the order they had to be found, and the second is why the first was invisible.
    `TODO.md` §1.13 has the full record and `CHANGELOG.md` 0.7.0 has it as a release. The measurements:

> **1. `verify` was not checking what the viewer checks.** It never applied the recorded bindings at all
> — it re-executed each slot index against whatever bindings the process happened to hold — and every
> comparison it does make is guarded by a sentinel meaning *not recorded*, which an empty quickslots
> field satisfies. So a recording the viewer refused to play at step 1 verified on position alone:
>
> ```
> before    verify:      [OK]  replay reproduced in 660 steps, 494 ms
>           viewer:      halted at step 18, 1 frame played
> after     verify:      diverged at step 17 - DROP/1 in SLOT
>           viewer:      halted at step 18 - the same step
> ```
>
> `Verification` now carries `unwatchableAt` and `passed()` beside `diverged`, and both paths go through
> one `Quickslots.prepare`, so the verifier and the thing it verifies cannot drift apart again.
>
> **2. The trainer recorded a step's bindings _after_ the step.** `ReplayRecorder.record` captures them
> because that is the instant the slot index is chosen, and says so in its own comment; `Worker`'s
> listener called it from `EpisodeCollector.Listener.onStep`, which is after the step by definition, and
> one callback could not express "before and after". `DROP` is where it shows because it is the one
> action that unbinds its own item — the item leaves the backpack, stops being reachable from the
> iterator the capture walks, and its binding is gone from the file before anyone reads it. Measured,
> on the same file, same step, same index:
>
> ```
> trainer   slot 1 -> ThrowingSpike   qs = ThrowingSpike->1 Food->-1 Waterskin->2 VelvetPouch->-1
> viewer    slot 1 -> Food            qs = ThrowingSpike->-1 Food->-1 Waterskin->2 VelvetPouch->-1
> ```
>
> **The hypothesis recorded below this entry — that drop resolution may be non-deterministic, or depend
> on which item happened to be bound to slot 1 — is refuted.** It is neither. `EpisodeCollector.Listener`
> now has `onDecision` before the step and `onStep` after it.
>
> **3. An empty binding set is a state, not a missing field — and getting *that* wrong was a second false
> positive, caught by `rolloutcheck` failing intermittently.** Asked per step, the two cannot be told
> apart; asked that way it refused a real recording. A fresh trainer run carries 211 SLOT and INVENTORY
> steps, one with bindings and 210 without, because the policy dropped the Waterskin at step 0. Then the
> whole-recording version was wrong too: "no bindings anywhere" is equally true of a run in which the
> hero had **nothing bound at every slot step**, which resolves from backpack order and plays. It failed
> only on the runs where a random episode happened to carry nothing, which is why it read as a flake.
>
> **The discriminator is the signature of the broken wire, not the absence of one field.** That wire sent
> four of nine step fields and left the rest at their sentinels, so health, turn **and** inventory are
> absent too, on every step. Nine trainer recordings carry `heroHp = -1, turn = -1.0, inventory = ""`
> throughout. A recording with real health and real engine time was written by a wire that carried
> everything, so an empty binding on it means the hero had none. Presence of evidence rather than absence
> of it — the lesson this entry already exists to record, reached a second time in the same change.
>
> Two other fixtures made the mistake from the other direction and were caught the same way: the
> `playbackcheck` "no resolvable slots" fixture blanked only bindings, so it became healthy and stopped
> being unwatchable; and `replaycheck`'s `write()` helper wrote every field at its sentinel, so a
> "healthy" fixture carried the four-field signature.
>
> **4. `viewcheck` passed anything that did not diverge.** A player that halts for any other reason — an
> unresolvable slot index, a stall, a refusal — leaves `diverged()` false. That same fresh recording was
> reported `[OK] rendered playback: 1 recordings played clean` after **3.7 seconds**, having played
> almost none of 1499. The gate now requires the cursor to reach the end.
>
> **And the corpus could not ask any of it.** 17 recordings, 310 SLOT-mode steps, and the entire SLOT
> vocabulary was `USE/SLOT`: `ScriptedPolicy` never chooses `DROP`, and an untrained policy only drops
> by accident. `viewcheck` was green on all seventeen *because the thing it would have caught was
> absent*, which is the shape this file already warned about in `TODO.md` 1.12 and which now has a
> measured instance. `duelist-drop.replay` and `gradle :superintelligence:dropfixture` fix that in a
> directory both viewer gates already read, and the command **verifies the recording before writing
> it**, so a fixture that no longer plays cannot be committed in place of one that does.
> Mutation-tested by removing the `ThrowingSpike` entry from its `DROP` step, which fails `viewcheck`
> with `DIVERGED at step 2 - DROP/1 in SLOT`.
>
> **Measured end to end, on real training runs** — 2 generations, 2 workers, 2 episodes,
> `--max-turns 120`:
>
> ```
> SGD-CKH-JUO.dat, 1499 steps   [OK] verify reproduced 1499   [OK] viewcheck played clean
> APR-SLJ-DXM.dat, 1514 steps   [OK] verify reproduced 1514   [OK] viewcheck played clean
>                               245 slot-bearing steps, of which 49 are DROP
> ```
>
> `APR-SLJ-DXM` is checked into `trainer-replays/`. Before that the section was **10 of 10 unplayable**
> — every file in it predates the fix, so picking anything ended in a halt — and the evidence was sitting
> in a temp directory a reboot empties.
>
> **The catalog now says per recording whether it can be watched, rather than once for the section.** It
> read *"NOT known to play end to end"*, which was the only thing between a reader and a playback that
> halts at step 1, and which became wrong the moment a fixed recording existed. The label comes from the
> body via `ReplayIO.readHeader`, so it costs no parsing, and `replaycheck` asserts the cheap scan and
> the full parse agree in both directions.
>
> ```
> GFW-GHG-BTT               -116.2  d1   1,260   not watchable, gen 3
> APR-SLJ-DXM                -80.6  d1   1,500   gen 1
> ```
>
> `verifyall` green at 20 gates plus `playbackcheck` at 22 — and **five consecutive runs**, because four
> of the faults above were intermittent by construction and one green would not have been evidence of
> anything. `viewcheck` **18 of 18**, 36.2 s at 8 children.
>
> **What is not recoverable, and what is.** The nine pre-fix recordings carry no bindings and never
> will: the data was never written and only a new run records it, so regenerating the wire cannot help.
> Measured across all ten, nine carry `hp=-1 turn=-1.0 inventory=""` on **every** step — the four-field
> wire bug, where the writer never sent them. They stay in `trainer-replays/` as evidence of what it
> dropped, not as fixtures. The tenth, `MSU-OTL-OAT`, carries real fields but was written by the old
> `onStep` listener, so its bindings were captured after the step and it is **listed as watchable and is
> not** — the one file the listing overstates, because nothing in a recording says *when* its bindings
> were captured. `verify` and `viewcheck` catch it; correcting the listing would need a version bump on
> the format, which is more than the problem warrants for a file kept as history.

> **All ten are gone from `trainer-replays/`, and that was the right call.** The directory's job is
> "recordings you can watch", and keeping unrecoverable files in it meant the picker offered **9 dead ends
> out of 10** — every one of them ending in a halt with nothing on screen to explain it beyond the line
> I had added. That is a worse outcome than losing the evidence, because the evidence was already written
> down and in git history. What replaced them is what the fixed trainer actually produces:
>
> ```
> IPW-QMA-AFY   1,508 steps   termination=TURN_LIMIT
> MFS-CDV-GCN     235 steps   termination=DEATH
> NKV-DZY-TYG   1,100 steps   termination=DEATH
> TPB-MLL-WYJ     534 steps   termination=DEATH
> ZQN-FGD-BPS     701 steps   termination=DEATH
> ```
>
> All five verify and play clean, and at the time **every recording in `trainer-replays/` was
> watchable** — which is the property the section header claims and, until now, did not have.
>
> ### The call above was reversed, then reversed again, and this time for a third reason
>
> **Reverted once, 0.9.0.** The trainer had written 185 recordings into temp directories across earlier
> runs — `%TEMP%/opencode/spd-*/replays` and a dozen siblings, reachable only through `ReplayIndex`,
> which is append-only and had accumulated 241 directory entries of which **220 no longer existed**. They
> were moved into `trainer-replays/`, all 185, and the nine four-field-wire recordings came with them.
>
> The argument for deleting them holds only while the choice is binary. Deleting them meant the
> recordings this project has actually produced were in a temp directory, which a reboot empties.
>
> **Reversed again, same release.** `trainer-replays/` is now **empty and untracked**, and every one of
> those 185 files is deleted. Three things settled it, and none of them is "the evidence is worthless":
>
> — **They are reproducible.** A trainer run writes them again. Keeping 5.9 MB of recordings in a
>   repository protects nothing that a second run does not, and the repo's `.gitignore` already said so
>   by ignoring the directory.
> — **The evidence was never only the files.** What the wire dropped is recorded here, in this table, in
>   `CHANGELOG.md` 0.7.0, and in git history — and it is still *observable* through the catalog the moment
>   any run writes there, because `ReplayIndex` and `defaultDirectories` both find it.
> — **Nine dead ends is still nine dead ends.** The label is honest, and a label does not stop a person
>   picking the row. The picker is for finding a run to watch, and a directory whose job is that is
>   wrong 9 times out of 185.
>
> So: the trainer writes to `trainer-replays/` by default, the directory is gitignored, and the
> repository carries none of them. The recordings a run produces are yours to watch and yours to delete;
> nothing in the project depends on them being committed.
>
> The removed files, for the record (in git history at `5356a8abb`, `5356a8abb^`, and `689770273`):
>
> | recording | steps | what was wrong with it |
> | --- | --- | --- |
>
> | recording | steps | what was wrong with it |
> | --- | --- | --- |
> | `GFW-GHG-BTT` | 1,260 | four-field wire |
> | `BMS-WQQ-LQH` | 655 | four-field wire |
> | `BMG-HZS-GGF` | 1,005 | four-field wire |
> | `XIM-LFA-MNY` | 986 | four-field wire |
> | `NNN-NYP-PNH` | 389 | four-field wire |
> | `QVT-MFY-JTM` | 1,458 | four-field wire |
> | `JLD-WRQ-GXJ` | 70 | four-field wire |
> | `MDC-JVK-QVB` | 1,373 | four-field wire |
> | `ZFW-GJP-VQC` | 1,205 | four-field wire |
> | `MSU-OTL-OAT` | 660 | bindings captured after the step |
>
> **And `viewcheck`'s own child budget was wrong while that was being found.** 120 s, against a
> full-length 1508-step recording that takes ~4 minutes at gate speed. The gate reported *"the viewer
> never stopped"* for runs that had stopped having applied **every** step — a false failure whose message
> names the opposite of what happened. Now 300 s, with the measurement recorded.

---

16. Two trainer recordings diverge on a chasm fall, and floor 2 is generated differently — **OPEN**,
    measured, cause not yet identified. **[CLOSED below — the chasm fall was never serviced at all, and
    the two recordings cannot be re-run because the trainer section was regenerated.]**

    Found while reading the recordings of a 150-generation run. Two of the 106 fail `verify`, and they
    are the two best-scoring ones:

    ```
    GRB-YWQ-ZJH   score  +844   floors 3   diverges at step 153 of 189
    LNL-PDP-CJK   score +1646   floors 2   diverges at step  61 of  88
    ```

    The other 104 verify. As with `issues.md` 15, **the two failures are the two runs that walk
    furthest** — the pattern this project keeps hitting, where a fault is only reachable once something
    works.

    ### The measurement

    `LNL-PDP-CJK`, around the divergence. Both sides replayed; the world agrees cell for cell right up to
    step 60:

    ```
    recording  step 59  MOVE_E    1235 -> 1236     onto a chasm
               step 60  WAIT      1236 -> 937      the fall lands him on floor 3
               step 61  INTERACT           937
    replay     step 59  MOVE_E    1235 -> 1236
               step 60  WAIT      1236 -> 1236     nothing happens
               step 61  INTERACT          1203
    ```

    `verify` says: `position: replayed 1203, recorded 937`. **Same seed, same floor number, `branch=0`
    on both, same cell, same clock — and a different floor.**

    ### What it is not, each refuted by changing the code and re-running

    | Candidate | Verdict |
    | --- | --- |
    | `LevelPipeline.handleFall` hardcodes `fallCell(true)`, ignoring `InterlevelScene.fallIntoPit` | **wrong** — changed nothing |
    | `clearSwitchRequest` leaves `InterlevelScene.mode` at `FALL`, so the next descent is misread | **wrong** — changed nothing |
    | `SpecialRoom.runSpecials` / `pitNeededDepth` depend on run history | **wrong** — gated, and green |
    | Reseeding the base generator before `initForRun` | **wrong** — broke all 19 corpus recordings, so the old order is self-consistent |

    The third is the one the two recordings were being blamed on, and it is now a gate:
    `checkSpecialRoomPoolsDoNotDepindOnOrder` runs each seed twice with the others in between and compares
    `runSpecials`, `floorSpecials` **and** `pitNeededDepth`. It passes. Those are a function of the seed.

    ### The control, which is the useful part

    A recording made by one worker in one episode, with **both** sides traced:

    ```
    recording steps        368
    record-side records    368
    replay-side  records   368
    differing records        0
    ```

    A run that descends **383 times** reproduces perfectly, with the special-room pools identical at every
    step on both sides. So the mechanism works, the pools are deterministic, and **the divergence is not
    seed-determined** — it needs a worker that has already run other episodes.

    That is the whole of what is known. Everything in the table above is refuted; nothing in it explains
    the two failures.

    ### Getting to a clean failing pair

    `WorldSnapshot` now records `runSpecials`/`floorSpecials` per step, and
    `--trace-world <file>` makes every worker trace the **recording** path, so a recording and its replay
    can be diffed rather than merely compared. Two obstacles were found and fixed on the way, both of
    which had produced wrong readings first:

    - The trace path was shared, so six episodes landed in one file — 1071 records for a 190-step
      recording, and record 0 was a *different episode* from the one being compared.
    - It is now keyed by an episode counter, which is **per worker**, so two workers tracing the same
      seed still collide: a 125-step recording produced 188 records.

    One counter issued by the trainer rather than per worker is the last thing standing between this
    instrument and a clean failing pair. That is where to pick it up.

### Also wrong along the way, and worth recording because it cost time

I read `tier=Floor` in the world trace as "not a chasm". **It is not evidence of anything**:
`Level` stores chasms in `pit[]`, a flag array entirely separate from `map[]` (`Level.java:878`),
so a pit cell renders as an ordinary Floor. Worse, a pit cell is exactly the "plain cell" shape the
trace's elision filter drops — walked, empty, passable, no heap or trap or transition — so the one
feature that can put the hero a floor down was **absent from the record entirely**. `pit[]` is now
recorded and pit cells are exempted from elision; without that, "the two floors differ" was an
assumption rather than a measurement.

I also counted 8522 "chasm cells" in a replay trace, which was records across steps rather than
distinct cells, and read it as the floor having plenty of chasms. It does not mean that.

---

## 16. Two trainer recordings diverge on a chasm fall, and floor 2 is generated differently - **CLOSED**

`GRB-YWQ-ZJH` and `LNL-PDP-CJK` are gone: the trainer section was regenerated and neither file was
ever committed, so the pair this was filed against cannot be re-run. What could be measured was, and
what it says is that the report above was investigating the wrong half of a larger fault.

**The chasm fall was never serviced at all.** A fall is the only floor transition that carries no
`LevelTransition`. `Chasm.heroFall` writes `InterlevelScene.mode = FALL`, calls
`Game.switchScene`, and writes nothing else — no `curTransition`, no destination, no landing cell.
`LevelPipeline.handleTransition` read `curTransition == null` **before** it read the mode:

```java
if (transition == null){ clearSwitchRequest(); return Transition.NONE; }
if (mode == Mode.FALL)          { clearSwitchRequest(); return Transition.FALL; }
```

On a fall `curTransition` is null *by construction*, so the first test fired every time.
`Transition.FALL` and `LevelPipeline.handleFall()` were unreachable code. Measured, by putting the hero
on a chasm cell through `Level.occupyCell` and stepping, on three seeds:

```
depth 1 -> 1, switch request acknowledged, hero still standing on the pit
```

So the two recordings could not have diverged *on a chasm fall* in the way the trace above describes —
a fall did not happen on either side. What they could have hit is the second half of the same fault,
which is real and is the shape the report measured and could not attribute:

**`InterlevelScene.mode` is a process static, and nothing headless ever resets it.** A real game gets a
fresh `InterlevelScene` for every descent, which is where its mode comes from. `LevelPipeline` now reads
the mode *first*, which makes it load-bearing — so a `Mode.FALL` left in the process by any earlier
episode is read by the next episode's first ordinary descent, and that descent is serviced as a fall: one
floor down, on a `fallCell`, instead of the floor's own entrance. That is precisely "the divergence is
not seed-determined — it needs a worker that has already run other episodes", on the one field that
decides how a transition is serviced.

**Three more things on the same path, found while fixing it and none of them in the table above.**

| | what it was |
| --- | --- |
| the landing | `handleFall` called `level.fallCell(true)` and ignored `InterlevelScene.fallIntoPit`, hardcoding the `WeakFloorRoom` case. They agree on a floor with no `PitRoom`, which is why changing it looked like it changed nothing; where the destination floor has one, the trainer landed the hero inside it and the game would not — a different cell and a different number of draws. |
| the landing effect | `InterlevelScene.fall` opens with `Buff.affect(hero, Chasm.Falling.class)`, whose act is `Chasm.heroLand` — fall damage, cripple, bleeding, and a press of the landing cell. The headless path skipped all of it, so **a fall was free**, and strictly better than the stairs. |
| the approach | walking a hero *at* a chasm threw `NullPointerException` out of `Chasm.heroJump`, from `new Image(Dungeon.level.tilesTex(), ...)`: `tilesTex` names a texture, `TextureCache` returns nothing for it with no renderer, and `Image`'s five-argument constructor derived its frame rectangle from the texture without the guard `frame(RectF)` had carried since the bypass landed. It is thrown from inside `Actor.headlessStep` and nothing between there and `Worker.serve` catches it, so it ends a worker's episode. |

`Image`'s guard is the one change in `:SPD-classes`, and it is the same category as the rest of
`ENGINE-CHANGES.md` §2: behaviour-preserving when a renderer is present, and the class that a gate in
this project cannot reach without a display.

**`Mob.holdAllies` is deliberately still not called.** `InterlevelScene.fall` opens with it, and it
still uses `Collections.shuffle` (`ENGINE-CHANGES.md` §7 has it open), so copying it would put an
unseeded draw into every fall and undo reproducibility for the sake of a behaviour no policy in this
project can reach.

**And the second half, which made the chasm unreachable rather than merely broken.** Walking *at* a
chasm raises the game's own "jump?" prompt, and the environment was handed it and then had the answer
thrown away. `Chasm`'s dialog gates both its option and its hide on `elapsed > 0.2f`, a counter its own
`update()` increments, and `PixelScene.update()` is the only thing that ever called it — so headlessly
the option could not fire. `WindowBridge.selectOption` made it worse: it cleared the parked window on
any press, so a handler that declined had its dialog removed anyway, which is exactly what `Chasm` does
while `elapsed` is under 0.2 s. Three fixes, all gated:

- `WindowBridge.age()` advances the parked dialog once per environment step — once per frame, the
  approximation `ReplayPlayer` already makes about a recorded step, so the gate is the game's own 0.2 s.
  Measured on `DIALOGCHECK-A`: **the answer takes 13 steps**, and **one crossing costs -15.00** — the 12
  refused steps are free because `settle` returns before it scores a turn, but the accepting step resumes
  the hero's interrupted move and that turn is scored as one that advanced no engine time. Against a
  descent at `+1800.00` that is cheap, which is what makes it worth having. It is not free, and an
  earlier draft of this entry claimed it was.
- a parked window lives in a one-slot `Group` rather than a bare field, so the game's own
  `Window.hide()` closes the slot. `GameScene.answerableWindow()` had to read the accessor rather than
  the field for the same reason — a dialog the game had hidden was still reported answerable.
- `settle` reports a dialog opened *during* the drain rather than a turn later. The prompt is raised from
  inside `Hero.act()`, so the agent was being handed the world for one more turn with a modal question
  already up — a turn it could spend walking away from the chasm it had just asked about.

Measured end to end: **the prompt opens, the agent answers, the game accepts, the dialog closes itself,
and the next move puts the hero a floor down.** Both answers cost the same 13 steps and the same -15.00;
declining leaves the hero where he was (`onSelect(1)` is empty) and the chasm still there, and he can
walk off it.

`Levitation.detach()` still does not re-check the landing cell headlessly, because the game guards that
call on a `GameScene`. Measured, with the hero on a pit cell while flying:

```
hero placed on pit 197 while flying: onPit=true flying=true depth=1
after levitation.detach():           onPit=true flying=false depth=1 alive=true
then walked MOVE_SW off it:          pos 197 -> 232, depth=1 (still no fall)
```

So the hero stands *on* the chasm and walking off it does not drop him either —
`Level.occupyCell`'s pit check is behind `if (!ch.flying)` and is only reached on entering a cell. In
the game that sequence falls him.

**Fixed on the supervisor side, without touching `:core`.** The agent is exactly the party that can
make this decision: it already decides when to service a switch request, and this is the same decision
the game makes inside a renderer. `LevelPipeline.runToHeroReady` notices a hero standing on a chasm with
`flying` false and calls the game's own `Chasm.heroFall`, so the landing cell, the damage and the fall
flag stay the game's. Measured, same state both ways:

```
without the check:  depth 1, pos 391, 20 health, still on the chasm
with the check:     depth 2, pos 759, 15 health
```

It runs at the top of the drain rather than after a scheduler step, because the buff ends *inside*
`Actor.headlessStep()` and the next iteration is the one where the hero is grounded with the drain still
running — checking only after a step misses it, because the hero becomes ready on that same step. Two
guards, both the game's own preconditions: the hero has to be on the level being pressed, and off a
`FeatherBuff`, since an elixir of feather fall is the one state in which `Chasm.heroLand` returns
early and a hero is *meant* to be standing on a chasm.

There was a sixth fault on this path, a thrown item never triggering what it landed on, which is **not**
part of this issue** and is filed as its own at 17.

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
`hide()` and `onSelect` are both gated on its own elapsed clock — so the property is asserted against the
shape and not against the one place it was found.

The fifth fall case is `issues.md` 16's question asked directly: the same seed, the same fall, twice in one
process with other runs churned in between, landing on the same cell of the same floor with the same
health, clock and `SpecialRoom.runSpecials`. It is the measurement the control above was reaching for
and could not get, because it needed a fixture that drives a fall.

**What is left, and it is small.** The agent can cross a chasm. It cannot cross one silently:
`Chasm.jumpConfirmed` is a one-shot that `Chasm.heroFall` clears on the very step that acts on it, so a
second chasm asks again — which is the game's own behaviour, not this module's. `Levitation.detach()`
still does not re-press the cell itself because the game guards that call on a `GameScene`; the
environment now makes the same decision the guard was suppressing, so the outcome is right even though
the guard is still there.

---

# Chasm

17. A thrown or dropped item never triggers what it lands on — **CLOSED**, measured.

    Found while closing `issues.md` 16. The chasm path has two halves — the engine asking whether the
    hero wants to jump, and the engine dropping him — and this is a third thing on the same path that
    neither of them covers.

    **`Level.drop` presses the landing cell**, which is how a thrown stone sets off a trap, opens a door
    or fills a well. It does so only behind a scene test:

    ```
    if (Dungeon.level != null && ShatteredPixelDungeon.scene() instanceof GameScene) {
        pressCell( cell );
    }
    ```

    Headlessly that second half is false, so the press never happened and a dropped item was inert.
    Measured, four seeds out of four — a `Food` dropped on an armed `WornDartTrap`:

    ```
    PRESS-A:      trap WornDartTrap at  253  ->  still armed after Level.drop
    PRESS-B:      trap WornDartTrap at 1107  ->  still armed after Level.drop
    PRESS-C:      trap WornDartTrap at  992  ->  still armed after Level.drop
    FALLCHECK-A:  trap WornDartTrap at   86  ->  still armed after Level.drop
    ```

    ### The fix, and why the scene half survives

    `Level.drop` is the one chokepoint for the whole item tree — `Item.onThrow` and `Item.doDrop` both
    call it and there is no other way an item reaches a floor — so the fix is one line and there is no
    interception to build:

    ```java
    if (Dungeon.level != null && (ShatteredPixelDungeon.scene() instanceof GameScene || GameScene.headlessGameplay())) {
        pressCell( cell );
    }
    ```

    **`GameScene.headlessGameplay` is a new static, and only a renderer-bypassed process sets it.**
    `HeadlessGame.install()` declares itself; nothing in the game's own launch path does, so **in a real
    game the guard's answer is unchanged**, which is the property the earlier attempt at this fix lacked.

    The earlier attempt dropped the scene half outright. It built, every gate passed, and it was wrong:
    the scene half is not redundant. It separates *gameplay* drops from *scene* drops, and two scene
    drops are deliberately not gameplay drops:

    | call site | scene | cell that would start pressing |
    | --- | --- | --- |
    | `AlchemyScene.java:761,864,1003` | `AlchemyScene` | `Dungeon.hero.pos` — the cell the hero is standing on, so crafting would trample the tall grass under him (`Level.java:1282`) |
    | `InterlevelScene.java:815` | `InterlevelScene` | `invPos` — the cell the hero died on, during resurrect, while the lines directly above it deliberately pick a respawn cell with no trap or plant on it |

    Both of those scenes need a renderer, so neither can exist in a headless process — which is exactly
    why the flag is safe. Every drop a renderer-bypassed process makes is a gameplay drop.

    The `Dungeon.level != null` half is untouched, and it is still doing the work it was doing: level
    generation runs with no current level, so a press during `RegularLevel.createItems` is still
    skipped.

    Measured after the fix, same seeds, same trap:

    ```
    PRESSCHECK-A:  trap WornDartTrap at 1093  ->  disarmed after Level.drop
    PRESSCHECK-A:  high grass at cell 281    ->  terrain 15 -> 2 after Level.drop
    ```

    ### What is gated, and what it refuses

    `gradle :superintelligence:presscheck`, **three cases**, and it is now **in `gates`** — it was left
    out only because it was red.

    | case | fails when |
    | --- | --- |
    | an item dropped on a trap fires it | the `headlessGameplay` half is removed from the guard |
    | an item dropped on high grass tramples it | the same — 2 of 3 |
    | generating a floor presses nothing | the `Dungeon.level != null` half is removed |

    The second case covers the branch a trap does not reach, and it is the reason `AlchemyScene` is a
    special case at all: crafting puts an item on the cell the hero is standing on, so a press there
    would flatten the grass under him on every craft.

    The third case's mutation is worth recording honestly: removing that half does not make the case
    fail, it makes **level generation itself** die, before the case is ever reached —

    ```
    NullPointerException  at RegularLevel.build(RegularLevel.java:120)
    ```

    — because the press now runs against a level that is still being built. The gate goes red either
    way, which is what it is for, but "the case refused it" would be the wrong description.

    ### And a recording, because a gate that cannot reach a fault is not a gate

    `presscheck` proves a press happens. It cannot prove the two environments *agree* about one, which
    is the property a trainer whose runs are watched has to have — and **the corpus could not ask it**:
    twenty recordings, no throw of anything onto a cell a press could act on, so `viewcheck` and
    `verify` were both green about a behaviour neither had ever run.

    `test-replays/duelist-throw.replay`, recorded by `gradle :superintelligence:pressfixture`, which
    **verifies the recording before writing it** and refuses to write one whose throw did not press:

    ```
    the throw pressed its landing cell: PRESS-THROW-C, ThrowingSpike thrown at cell 609, terrain 15 -> 2
    [OK]  wrote 60 steps, first throw resolved in TARGETING at step 2; reproduced in 60 steps
    ```

    Terrain 15 is `HIGH_GRASS` and 2 is `GRASS`: the spike flattened it. The throw is driven through
    the agent's own three steps — `USE`, `USE` on the slot, `USE` on the target — so the recording
    carries the aim the way a real run does, and the seed is searched rather than fixed because no
    single floor can be relied on to have grass beside the hero.

    **Mutation-tested, and the gate that refuses it is not the one you would guess.** Reverting the
    guard leaves `viewcheck` green on all twenty recordings, because the viewer's press never depended
    on the flag — it has a real `GameScene` and always pressed. That is the correct answer and it is
    worth stating: the viewer half of this fix was never at risk, and the trainer half is caught by
    the gates that compare a recording against a fresh headless run.

    ```
    verify          replay diverged at step 27 of 60 - hp: replayed 17, recorded 19
    playbackcheck   duelist-throw.replay diverged at step 27: DIVERGED at step 28 - INTERACT/0 in WORLD:
                    hp is 17, recording says 19
    ```

    **Not fixed by any of this, and it is the right answer:** a thrown item does not *trigger* a trap the
    way a hero walking onto one does, in one specific respect. `Level.occupyCell` presses with the hero
    standing on the cell; `Level.drop` presses with nothing on it. That is the game's own arrangement
    and it was not changed.

    **What is not broken, and is worth being precise about:** traps still fire when a hero *walks* onto
    one. `Level.occupyCell` presses unconditionally and was never scene-guarded. Only thrown and
    dropped items were inert, so the hole was much narrower than "traps do not work".

---

18. `rolloutcheck` fails about one run in thirty, and the recording it made verifies on the next
    attempt — **OPEN**, measured, pre-existing.

    Found while closing 17, and unrelated to it. `rolloutcheck` is in `gates`, and `gates` failed on one
    run out of roughly thirty while the fix above was being verified:

    ```
    [ERROR]  rollout recording: 2 of 3 checks failed
        a recording made from a random-seed episode does not verify: position: replayed 1026, recorded 193 at step 32
        verification covered 33 of 60 steps; a short match is not a reproduction
    ```

    **It is not caused by the change that was in flight.** A/B'd against the base tree, the same
    committed code with every edit stashed:

    | tree | runs | failures |
    | --- | --- | --- |
    | base (`HEAD`, nothing stashed in) | 30 | 1 — `position: replayed 656, recorded 582 at step 54` |
    | with the `issues.md` 17 fix | 60 | 1 — `position: replayed 1026, recorded 193 at step 32` |

    Same signature, same rate, different random draws.

    **Why it is intermittent, and it is not a seed lock.** `RolloutCheck`'s first two cases record an
    episode with an **empty seed**, so `SeedPool` puts it on a random one — which is deliberate, and is
    what makes the "does a random-seed recording verify" case possible at all. A locked-seed gate cannot
    see this class of fault, so this gate exists specifically to see it, and what it is seeing is that
    a minority of worlds do not reproduce.

    **Not investigated, and stated as open rather than guessed at.** A recorded position and a replayed
    position several hundred cells apart, mid-run, on a floor that presumably contains whatever caused
    it — the chasm path and the stairs path are the two places a position this far apart can be
    produced in one step, and both were changed recently and are gated (`fallcheck`,
    `transitioncheck`). What is needed is the world trace for a failing seed, which means catching a
    failing draw rather than waiting for one: `episodediff` takes a seed and two runs and reports where
    they part, and a random-seed sweep that keeps the seed of any recording that fails to verify would
    turn a one-in-thirty flake into a reproducible case. Until then this is a gate that is **red about
    three percent of the time and does not say why**, which is worth more than a silent green and is
    still not a gate anyone should have to learn to re-run.

    **What is not wrong:** `paritycheck` verifies 32 recordings across seeds in one process and passes,
    and both viewer gates pass the whole committed corpus. The failure needs a *random* seed and a
    60-step scripted episode.

## 19. An item that creates a mob leaves it with no sprite, and a mirror image then casts its sprite - **CLOSED, in `:core` and gated**

Found by a 30-generation training run under `reward.profile=depth_only` (16 workers, seed 20261013): two
workers died with `NullPointerException: Cannot assign field "visible" because "this.sprite" is null` at
`Char.move(Char.java:1334)`, through `ScrollOfMirrorImage.spawnImages` -> `ScrollOfTeleportation.appear`.
Reachable on floor 1 with nothing but a mirror image scroll and a policy that reads what it finds.

`GameScene.add( Mob, float )` gives a mob its sprite and its place in `Actor.chars` only when a scene
exists; a renderer-bypassed process has none, so an item-created mob has no sprite and `Char.move`
dereferences it unconditionally. `add( Heap )` already carries the headless answer for heaps and its
comment says why; the mob branch does not. The fix and its second half - `MirrorImage.act`'s unguarded
`MirrorSprite` cast, which a `HeadlessSprite` cannot satisfy - are written up in `ENGINE-CHANGES.md` §8 and
are **committed**, on the owner's word, with `mobspritecheck` as the gate: 3 cases, in `gates`,
mutation-tested by reverting each half, and the third case asserting that with `headlessGameplay` off the
stand-in factory is never consulted - so a game with a renderer cannot reach the branch.

```
reading a Scroll of Mirror Image threw java.lang.NullPointerException at Char.move:1334
reading a Scroll of Mirror Image threw java.lang.ClassCastException at MirrorImage.act:86
2 mirror image(s) created by a scroll, every one with a sprite
every mirror image is in level.mobs and in Actor.chars, so it acts and the hero can walk into it
with the headless flag off, GameScene.add(mob) never consults the stand-in factory
```

## 20. Under `depth_only` the run converges on dying early rather than on descending - **OPEN, and it is a finding rather than a defect**

Measured on the same 30-generation run (30 generations, 16 workers x 4 episodes, `reward.profile=depth_only`,
`env.turn_limit_per_floor` at its default, seed 20261013, `depth_reward=1800`, `turn_cost=1.0`,
`death_penalty=2500`, `stall_penalty=3000`):

| generation | meanScore | meanDepth | bestDepth | meanTurns | ended |
| --- | --- | --- | --- | --- | --- |
| 0 | -7616.8 | 1.00 | 1 | 795 | death 58, turn limit 6 |
| 6 | -3726.3 | 1.02 | 2 | 231 | death 64 |
| 11 | -3735.1 | 1.02 | 2 | 276 | death 64 |
| 20 | -3471.4 | 1.00 | 1 | 209 | death 64 |
| 29 | -4156.2 | 1.00 | 1 | 363 | death 64 |

**`bestDepth 2` in 5 of 31 generations, all of them before generation 12, and 100% of episodes dead from
generation 4 on.** Mean score improved by 4,460 points and mean turns fell by 432. Read plainly: the policy
learned to **die more cheaply**, not to descend, and the run's improvement is the cost of dying falling
rather than the cost of descending being paid.

**Why, and it is arithmetic rather than a mystery.** With nothing but the objective paying, the only
reachable outcomes for an untrained policy are the two terminals and the per-turn cost. Dying at turn 300
costs `-2500 - 300 = -2800`; stalling costs `-3000 - 120 = -3120`, so death is the cheaper floor and the
gradient can reach it from anywhere - which is what `meanTurns` falling from 795 to ~300 measures. The
`stall > death` ordering that §1.9 put in place is doing its job; the problem is that under a profile with
no positive signal outside depth, **the cheapest reachable outcome is the one the policy converges on.**

**So `depth_only` is a diagnostic profile and not a training default.** It answers the question the
assignment asked - is the objective the only route to a positive score - and it does: 80 incidental events
paid 0.00 and the descent's 1800.00 was the whole of a real episode's positive score. What it also
answers, unintentionally and honestly, is that switching the dense terms off is not a free way to make the
agent smarter. Compare §1.15: the same 16-worker setup under `default` recorded `bestDepth 2` in 104 of
150 generations.

**The next measurement is a curriculum rather than a switch**, and the machinery for it already exists and
has never engaged: `reward.curriculum_progress` is `0.0`, the trainer prints `shaping=1.00` in all 30
generations, and `Curriculum`'s fade is what would keep the dense terms for survival while the objective
carries the direction. Nothing in this run reached a descent often enough for the fade to matter, which is
the finding: **the fade needs a policy that survives, and this profile does not produce one.**

## 21. Descending pays every time, so the hero ping-pongs between two floors and banks it - **CLOSED**

Found by the run that came after the free-step bound, and it is the same shape as everything else in this
file: a policy that finds a cheaper way to end an episode, found by a policy rather than by a gate.

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
and the hero went back up after each: 1 -> 2 -> 1 -> 2 -> 1 -> 2 -> 1. It never got past floor 2 and it
earned four descents' worth of reward for standing on the same two floors.

**The cause is one comparison.** `RewardModel.step` pays `DEPTH_ADVANCE` when `depth > prevDepth`, and
nothing asks whether the floor below is one the run has already been on. `Dungeon.depth` counts where the
hero is, not how far he has been: ascending is already priced at zero, so the pair of them is a closed
money machine. Each cycle is roughly 45 turns and pays +1800 against about -45 in turn cost.

**This is worse under `depth_only` than under `default`, and that is the uncomfortable part.** The profile's
whole claim is that reaching a new floor is the only route to a positive score, and its gate case
(`checkDepthOnlyPaysOnlyForDepth`) asserts that the run's positive score equals its `DEPTH_ADVANCE` total -
which a ping-pong satisfies perfectly, because the payment is real. **The case tests the arithmetic and not
the question.** A re-descent of an already-visited floor is not "a new floor" in any sense a player would
recognise, so the case should be asking whether every descent reached a depth this run had never been on.

**The fix is one condition, and it took the first of the two readings.** `51f369f9f` pays when the descent
reaches a depth deeper than any this episode has reached, tracked in `RewardModel.deepestPaid` and reset
with the snapshot - which `SPDEnv` calls at the start of an episode and deliberately not on a floor change.
It is the stricter of the two: paying on the first entry to each floor would also have stopped the
ping-pong, but would still have paid for a re-descent after an ascent, which is the move this recording is
made of.

Gated by `checkOnlyANewFloorIsPaidFor`, which drives **ten descents with ten ascents** through the real
environment and requires the total to be one floor's reward:

```
10 descents and 10 ascents reached depth 2 and paid 1800.00 - one reward per new floor, not per stair
```

Reverting the new term fails it with the measurement it is about - `paid 16200.0 where 1800.0 was
expected`.

**What it cost, and what it bought, is in `issues.md` 22.** Closing this removed every positive score from
the run that had 21 of 30 generations positive, which is the point: those were the exploit.

`ReplayIO.verify`'s `[WARN] score recorded ... reproduced` now prints on the committed corpus, because
every recording's reward column moves - the same consequence `CHANGELOG.md` 0.9.0 records for the
re-pricing. The trajectory comparisons are unaffected; the score is a reward-model artefact, which is what
that warning has always said.

## 22. With the ping-pong closed, nothing the policy does pays - **OPEN, and it is the honest measurement**

The third of the three runs, and the first one where the reward function means what it says. Same seed
(20261013), same profile (`depth_only`), 30 generations at 16 workers, `out/evidence/ping-pong-fixed-30.csv`:

| | run 1: nothing fixed | run 2: free-step bound | run 3: bound + ping-pong closed |
| --- | --- | --- | --- |
| `bestScore` | -2,579 | **+9,540** | -2,132 |
| generations with a positive best | 0 | **21 of 30** | **0 of 30** |
| `bestDepth 2` | 5 of 31 | 28 of 30 | **2 of 30** |
| last generation's endings | death 100% | death 49% / stalled 52% | death 2 / **stalled 62** |

**Run 2's numbers were the exploit, not progress, and closing it removed all of them.** A positive best
score in 21 of 30 generations sounded like the objective working; it was `DEPTH_ADVANCE` paying for
1 -> 2 -> 1 -> 2 at `deepest=2`. With the comparison fixed to a new maximum depth, the same run produces no
positive episode at all and reaches floor 2 twice in thirty generations.

**What the policy does now, from its best recording** (`JEA-FKU-WZS`, CLERIC, 84 steps, reproduced exactly):

```
terms
  NO_PROGRESS              -264.00
  TURN_COST                -104.00
  DEATH                   -2500.00
```

15 moves north, 13 interacts, 9 searches, 4 inventory opens: real actions, no free-step flood, no descent to
farm. It wanders, and then it ends on a terminal - 62 of 64 episodes in the last generation stalled rather
than dying, because under this profile a stall is the cheapest surviving ending and the free-step budget
now decides which one that is.

**So the profile is not a training setting, and this is the proof rather than the suspicion.** Every route
to a positive score is a route the policy cannot reach: no floor, no win, and with the loop bounded and
depth farming closed, nothing it does pays at all. Sparse reward is a wall, not a setting - and
`research.md`'s Phase 1 / Phase 2 split, which `Curriculum` implements and which has never once engaged
(`shaping=1.00` in all 30 generations), is the machinery that was supposed to be what gets a policy past
it: dense terms for survival while the policy is weak, faded out as depth starts moving.

**The next run is `default`, not `depth_only`,** with the bound and the depth payment in place. That is the
configuration the milestone from `research.md:40` is measured against, and it is the first run in this
project's history where `bestDepth` and the score cannot both be explained by something other than
progress.

## 24. Pressing `r` at the viewer prompt paid a Gradle startup to repaint a list - **CLOSED**

**Measured, on this machine, over the 64 recordings the catalogue lists:**

| | cost |
| --- | --- |
| `:superintelligence:replays` through gradle, daemon warm | **3.4s** |
| the same through gradle with `--no-daemon` | **8.7s** |
| the same code as a plain `java -cp` launch | **0.5s** |
| `replay-viewer --list` now | **0.62 - 0.76s** |

Gradle's entire contribution to that call is resolving a classpath that is **already written to disk** -
`desktop/build/replay-classpath.txt`, written by `:desktop:replaycp`, 11,063 characters, and the launcher
next to it is `java -cp "<that>" %*`. So the launcher the viewer already uses for playback could serve the
catalogue too, and did not: `:catargs`, `:bynumber` and `:resolve` each shelled out to gradle.

**Why it matters is the `r` key and nothing else.** A refresh is what a person reaches for in the one
moment it exists for - immediately after a training run has written new recordings into
`trainer-replays/` - and it was costing three seconds per repaint. The numbering was never wrong, because
`:catalog` re-runs the query rather than reprinting a cached listing; that is deliberate and it is what
makes a number trustworthy after the directory changed. **The rebuild was right and the transport was
wrong.**

Gradle is still what **builds**. It is called exactly twice from the viewer's script now: once to build
the launcher if it is missing or older than the classes it names, and once on the fallback path if that
build produced nothing. Listing, resolving and playing need no build system at all.

**Two things got wrong on the way and both are worth keeping.**

1. **The staleness check was not a comparison.** `for %%I in ("%SUPJAR%") do if %%~tI GTR "%RUNBAT%"`
   looks like a timestamp comparison and is not one - `%%~tI` expands to a locale-formatted *string*, so
   the test fired on every call and rebuilt through gradle every time. **The change therefore measured
   3.6s while the thing it was measuring cost 0.5s**, which is the worst possible failure: a fix that
   looks like it did nothing. It now compares through PowerShell once per session and tests the string.
2. **`for /f` runs its body on any output line**, whatever that line says. The first correct staleness
   command printed `False` and still set the flag. Comparing the string rather than "did it print
   something" is the whole difference.

Verified: `replay-viewer --list` output is **byte-identical** to gradle's for the same 64 recordings, and
`replay-viewer --verify duelist-drop` resolves a bare file name through the same new path and reproduces
the run exactly. The interactive `r` repaint could not be exercised by a harness that cannot feed a
prompt, so what was measured is the listing call the repaint makes.

## 26. The viewer spends 1 HP on a step the recording does not - **OPEN, diagnosed, not fixed**

Found while replacing `duelist-death-last` after the `WAIT` fix. It is the ninth recording this
project has produced that verifies exactly headlessly and does not play in the real viewer, and the
first one whose cause is not a presentation draw.

### What it is

The replacement recording - `duelist-death-last.replay`, seed `DUELIST-LONG-16`, DUELIST, 100 steps,
`DEATH` on the final step - verifies 100 of 100 headlessly and halts in the viewer:

```
DIVERGED at step 95 - USE/0 in SLOT: hp is 6, recording says 7
```

Confirmed with `VIEWCHECK_JOBS=1` over 257s, so it is **not** the documented parallel-playback
artifact.

### Where it is not

**The randomness is identical.** Compared per step on both sides, `baseDraws` and
`baseFingerprint` agree at **every step both sides recorded** - steps 0 through 93:

```
step  headlessTurn  viewerTurn  deficit  h-hp  v-hp
 88          87.0        84.0      3.0  9/20  12/20
 89          87.0        87.0      0.0  9/20   9/20
 90          90.0        87.0      3.0  8/20   9/20
 91          90.0        90.0      0.0  8/20   8/20
```

**It looks like a three-turn deficit and is not one.** The readings oscillate - behind at 88, level
at 89 - and **at every catch-up the state is identical**. HP agrees at every engine-clock value both
sides recorded, which is the comparison that does not depend on when a snapshot was taken. That
oscillation is the same thing `viewdiff` warns about: *"a recorded step is applied on some frames and
not others, and only the second kind can hide a turn."* A first pass at this read the deficit as a
fault and that was wrong.

### Where it is, measured

```
[DIFF] at step:94: hero (settled) field hp
  expected: 7/20
  actual:   6/20
```

**Only `hp` differs.** The whole step-94 `hero` record is otherwise identical on the two sides - same
`pos=552`, same `turn=92.0`, same `ready=true`, same buffs (`Charger:0.0,Haste:18.0,Hunger:0.0,
Recharging:29.0,Regeneration:0.0`), same inventory - and the `roster` record, which is every mob's hp,
state, energy and buffs, is **byte-identical**. So no mob differs either. A difference in damage that
leaves no trace in the roster is a damage source the roster does not carry.

Producing that line needs the documented pair of commands and nothing else, which it did not before -
see the tool fixes below:

```
./gradlew :superintelligence:worldtrace -PworldArgs="<replay> <headless.txt>"
./gradlew :desktop:replay --args="--file <replay>" -PspdWorldTrace=<viewer.txt> ...
./gradlew :superintelligence:viewdiff -PviewDiffArgs="<headless.txt> <viewer.txt>"
```

Step 94 is the first step the headless spends draws on the scroll at all, and it is the step the
viewer halts on:

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
version of this entry pointed, and it is not the cause.** Three measurements killed it:

1. A tripwire on `Scroll.readAnimation`, run through both the real viewer and the headless verifier,
   printing the engine clock either side of `sprite.read()`:

   ```
   read-enter now=91.0     <- viewer
   read-exit  now=91.0     <- viewer
   read-enter now=91.0     <- headless
   read-exit  now=91.0     <- headless
   ```

   **Identical.** The read is entered and left at the same engine time on both sides, so it cannot be
   what shifts the clock afterwards.

2. `HeroSprite.read()`'s `animCallback` does `idle(); ch.onOperateComplete();`. The viewer fires
   `onOperateComplete` at 91.0; **the headless never fires it at all**, because it uses the empty
   `CharSprite.read()`. So the two sides differ in whether the call is made - and yet both reach the
   same clock on that step, which says the call is not what releases the hero.

3. **The fix was implemented and made no difference.** `HeroSprite.read()` was made to skip its
   animation when a new `GameScene.replayFidelity()` flag was set, which `ReplayLauncher` turns on for
   playback and nothing else - the same shape as `GameScene.headlessGameplay()`. Re-running the viewer
   on the same recording at the same speed produced a **byte-identical** verdict:

   ```
   [replay] halted: DIVERGED at step 95 - USE/0 in SLOT: hp is 6, recording says 7
   ```

   The change was reverted. It touches `:core` for no gain, and `instructions.md` 12.3 says an engine
   logic change stays out of a commit unless it is the fix - this was not.

**What is left is that the viewer applies 1 HP of damage the recording does not contain**, at step 94
and again at 97. Not the read, not the animation, not the RNG, not the engine clock. The two tools
below are what makes that a measurement rather than a suspicion.

### The turn deficit that was here earlier was not a fault

An earlier version of this entry read the divergence as **the viewer being one engine turn behind**,
from `at step:94: hero field turn / expected 92.0 / actual 91.0`. **That reading was wrong, and it was
an artefact of the instrument rather than of the game.**

The viewer's world trace is written on halt, and the viewer halted on step 94. Its last `hero` record
before the halt was therefore written at `turn=91.0` - one engine clock earlier than the *settled*
state of that step. Comparing that against the headless's settled step-94 record at `turn=92.0` reads
as a turn deficit. It is the same frame-versus-step granularity `viewdiff` already warns about, one
level up: the viewer was asked to compare a record taken mid-step against one taken after it.

Once the viewer was made to keep playing past the divergence (see the last section), it ran all 100
steps, and the settled step-94 record on its side reads `turn=92.0` - **the same clock as headless**.
Across every step from 86 to 99, `turn` and `pos` agree exactly:

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
dealing extra damage. **The same damage lands on a different step.** Per-step damage, from `spd.trace`:

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
step-94 one and the headless does not; the headless applies it at step 96 instead, which is why the
two sides are level again at 96 and the totals match.

**Not a speed artefact.** Measured identically at `spd.fast=800` (2600 frames) and at the gate's
`spd.fast=40` (2601 frames), same divergence both times.

### Where the damage comes from is still open, and cells are the missing channel

The damage leaves **no trace in the roster** - every mob's hp, state, energy and buffs is identical -
so whatever deals it is not a mob. The candidates are room features and cell state: `specials` on this
floor lists `ToxicGasRoom`, and the hero carries a `Charger` buff.

**The viewer writes zero `cells` records.** `ReplayPlayer` constructs its `WorldSnapshot` with
`.cells( false )`, because it also samples per frame and a floor-sized cell dump per frame would be
tens of thousands of records. Headless writes 100, one per step. **So cells are the one channel in this
diff that cannot be compared, and they are the one place a room hazard would show.** Turning cells on
for `step` records only - not `frame` - is the next instrument.

### Why 20 of 20 recordings played clean until now

**The corpus had no scroll to read.** The previous `duelist-death-last` used `USE` on food, a
throwing spike and a potion, and nothing that goes through `HeroSprite.read()`. The replacement was
found by probing 28 DUELIST seeds for one that ends in `DEATH`, and that probe found one that also
drinks a haste potion and reads a scroll. The path had never been recorded, so it had never been
played.

### What is not established

- **What deals the extra HP.** Not the scroll read, not its animation, not the RNG, not the engine
  clock, not the speed - all five measured. The roster is identical, so it is not a mob. Cells are the
  uncompared channel and the likely place to look; see above.
- **A fix.** The one that looked obvious - the read animation - was implemented and makes no
  difference, and was reverted.
- **A gate.** This is the ninth recording in this shape and `playbackcheck` passed it, because
  `ReplayPlayer` headless agrees with `verify` - the divergence needs the **renderer**, so only
  `viewcheck` can see it, and `viewcheck` is outside `gates` because it needs a display. The two-sided
  diff is the gate-shaped answer and it is not wired to anything yet: it needs a display for one of its
  two inputs, exactly as `viewcheck` does.

### The two tools that were broken, and what fixing them bought

Both halves of the instrument were wrong, which is why the fault above read as *unobservable* rather
than as *unknown*:

- **`:desktop:replay` did not forward `-PspdWorldTrace`.** Only `viewcheck` did, so the invocation this
  file's "measured" block uses wrote nothing and said nothing. Fixed; the command above now produces a
  trace.
- **`viewdiff` keyed its two inputs by a scheme neither side shares.** It reported
  `hero #1 exists here and not in the expected snapshot` on a run whose two sides agreed, because
  `ReplayPlayer` snapshots per frame and `WorldTrace` per step; and it reported `a accepted record the
  expected snapshot does not have`, and then `no cells record`, for records only one side ever writes.
  Fixed in three parts - the settled state of a step is its **last** record where the expected side
  holds one row and this side holds several; `accepted` and `cells` are skipped as one-sided.

  Before, on the same two traces: `at step:0: hero #1 exists here and not in the expected snapshot`.
  After: `at step:94: hero field turn`, which is the fault.

### Three smaller things found alongside it

- **The viewer's world trace was written on halt but only carried the frame the halt landed on.** The
  viewer's last `hero` record before the divergence was step 93, and the step that diverges is step 94 -
  which is why the fault could be pinned only by comparing `viewdiff`, and why keeping playback running
  past the divergence was needed at all.
- **`-Dspd.replayContinueOnDiverge`, added here.** `ReplayPlayer` halted on the first divergence, so the
  steps *after* the fault - the only ones worth looking at - were never recorded. It now keeps applying
  and tracing after a divergence, reporting it once. Off by default; it is never enabled by a gate,
  because a run enabled this way is already off the recording's path, so its trace describes the live
  game rather than the replay.
- **`:desktop:replay` did not forward `-PspdHidden`.** `ReplayLauncher` reads `spd.hidden` and
  `ViewCheck` already passes it, so the setting existed and worked - but this task never bound it, so
  `-PspdHidden` had nothing to bind to and the viewer window came up on the desk in the middle of a
  traced run. Same class of gap as `spdWorldTrace` above, and the reason this file's commands could not
  be run unattended.

With `-PspdContinueOnDiverge` the traced run now completes and the diff has 5 more steps to work with,
which is what turned "the viewer is a turn behind" into "the viewer spends 1 HP at step 94".

## 25. Every update thread shares one Network, so a gradient is accumulated on whatever survived the last clear - **CLOSED, gated**

Found while reading the metrics of a `default`-profile run (see below), and it is the most consequential
defect this project has recorded. Six generations reported an **entropy that cannot exist**: a categorical
distribution has `H >= 0` for any probabilities in `[0, 1]`, and `Policy.entropy` is
`-sum(p * log p)` over the buffer `Policy.probabilities` just wrote, where every entry is
`exp(logit - max) / total` summed over the same masked set - so each is `<= 1` and each term is `>= 0`.
`-54.158` was impossible, and the only way to get it is an entry above 1.

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
accumulators, and every internal forward scratch - `slotLogits()` among them - shared. The class header
states the opposite twice: *"one `Shard` per thread holding a copy of the parameters and its own
scratch"*, and *"Why per-thread networks rather than shared tensors ... Sharing the tensors would save
that and reintroduce exactly the aliasing this avoids."* `PLAN-data-flow.md` step 4 records the same
intent. The code does the thing the document exists to prevent.

### The chain, each step measured rather than read

1. **A tripwire in `Policy.entropy`** printed the offending buffer: `n=32` (the slot head), `out[0]=1.128`,
   every other entry `0`. One entry above 1, which is exactly what makes the sum negative.
2. **A stage trace in `PPO.oneSample`** took the buffer's max after each of the three calls that could
   touch it: after `probabilities` 1.402, after `accumulatePolicyGradient` 1.402, after `backward` 1.402.
   The corruption is inside `probabilities` and neither other call moves it.
3. **Instrumenting `probabilities` itself.** The mask is stable and the corruption is in the logits:

   ```
   legalBefore=1  legalAfter=1
   logitsBefore=0.24949923157691956   <- read before Arrays.fill(out, 0f)
   logitsAfter =0.6160362958908081    <- read after it
   max=0.094019055  total=1.0438831060904241  out=1.579382
   ```

   One legal action, mask unmoved, and the single legal logit changed value *inside the function* with no
   other code running on that thread. The only other code running is the update's other threads.
4. **An identity probe across pool calls** - 67 samples, 2 generations, `--update-threads 6`:

   ```
   thread=ppo-update net=756441311 logits=1186636202 probs=...
   thread=ppo-update net=756441311 logits=600935181  probs=...
   ```

   `net` is identical on every one. `probs` differs per sample, because that scratch **is** per-shard -
   which is what made the fault hard to see: the one thing a reader would check is the one thing that is
   correct.

All instrumentation was reverted; the committed tree carries none of it.

### Why `parallelcheck` did not catch it

`ParallelCheck.reduce` never runs `ShardedUpdate`. It builds its own

```java
Network[] parts = new Network[ shards ];
```

and calls `ParallelFixtures.oneSample` on each in a plain serial loop - its own networks, its own loop,
no pool, no `Shard`, no `ShardedUpdate.run`. It proves that N independent networks accumulate the same
gradient as one network, which is true and worth proving. **It cannot catch a shared network because it
supplies the networks itself and never asks `ShardedUpdate` where its shards come from.**

`PLAN-data-flow.md` §8 lists *"Parallel update silently races on shared state"* as a **high** risk that
`parallelcheck` would cover. It covers the reduction, not the isolation the reduction rests on. That is
the same shape as the six gates in `testing-guide.md`: a green is a claim about what was checked.

### What follows, from reading the source rather than measuring it

- Every shard calls `clearGradients()` on the master, wiping other shards' partial sums.
- `network().addGradientsTo(network())` runs once per shard, all on the same object, so the applied
  gradient is whatever survived the last clear, not the sum over the batch.
- `copyParametersFrom(ppo.network)` is a self-copy.
- Layer scratch - `Dense.preAct`, `Conv2D`'s im2col, `LSTM.gates/h/c` - is shared, so forwards are torn
  between threads. `entropy`, `kl` and `clipFraction` are all read off those torn buffers, which is why
  those were the columns that looked wrong.
- **Training does not crash**, and that is why nothing looked wrong: PPO tolerates a noisy, badly-scaled
  gradient. The visible consequence is a policy that does not learn.

### What it plausibly explains

Every training run in this project's history used `--update-threads` above 1, so every one of them
learned on this path. `default-60` - 77 generations, 20 workers, 6 update threads, `out/evidence/`:

| | |
| --- | --- |
| `bestDepth` | **1 in all 77 generations** |
| mean score, first third -> last third | -3,149.81 -> -3,182.23 |
| endings, generation 24 and generation 76 alike | stalled 80 (100%), death 0 |
| generations reporting impossible entropy | 6 (35, 43, 47, 48, 73, 76) |

`kl` of 5.15 and 5.61 on generations 73 and 76, against 0.017-0.04 everywhere else, is the same fault
and not a second one. **`issues.md` 22 measured "nothing the policy does pays" under `depth_only`; this is
a mechanism that would produce the same flatness under any profile, and it has been present for every one
of those runs.** That is a claim about what this explains, not a measurement that it explains all of it -
the runs that would settle it have not been re-run without the fault.

### The fix, and what it cost

One line in spirit: `Shard` constructs its own `Network` from the config and a seed, exactly as
`ParallelCheck.reduce:177` already does - `new Network( config, new Random( 1L ) )` followed by
`copyParametersFrom( ppo.network )`. The per-minibatch parameter copy the design describes then becomes
real rather than a no-op.

**It invalidates every checkpoint and every measurement in this file that used more than one update
thread.** The parallel path had to be re-measured rather than re-derived.

`parallelcheck` is **5 cases**. The new one asserts the property deterministically - no two shards hold
the same `Network` object, and none is the master's - which is what makes one thread's
`clearGradients()` and layer scratch unreachable from another. `PPO.updateShardNetworks()` exists only so
a gate can see where the real shards' networks come from; the other four cases supply their own and so
could never have asked.

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
**Reinstating the fault and silencing only the identity assertions, 64 samples reported 1.4649 and the
check passed.** A race does not reliably corrupt anything in one update of one minibatch-sized batch;
over a real run it corrupted roughly one sample in ten thousand, which is why it took 77 generations to
show and why the big run's `-54.158` was a rare generation rather than a constant.

So that assertion would have been a check that cannot fail, which is the same failure as no check. It is
**printed** instead, and the case's own comment says so - a reader who assumed it was covered would be
wrong.

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
scratch into one object were contending for the same cache lines; separate networks give each thread its
own memory, and that more than pays for the per-minibatch parameter copies the design already budgeted
at 0.5%. The worst `kl` nearly halves, which is the reduction agreeing better with itself.

**The memory is real and is not reported anywhere.** Six shard networks is 6 x 14.3 MB = ~86 MB of
trainer heap on top of the master's, against the ~100 MB transition buffer the generation report prints
as `buffer ... MB resident` - so that line understates the trainer's memory by roughly its own size. Left
alone rather than changed unasked, and recorded here instead.

**What this does not settle.** The 77 generations in `default-60` were trained on the broken path, and
so was every run before it. `issues.md` 22 measured "nothing the policy does pays" under `depth_only`
with the same flatness this explains; whether a correct update path changes that is **unmeasured**, and
the run that would answer it has not been made.

Evidence, with the full measurement chain: [`out/evidence/entropy-shard-diagnosis.txt`](out/evidence/entropy-shard-diagnosis.txt).
The run the finding came from: [`out/evidence/default-60-run.txt`](out/evidence/default-60-run.txt) and
[`out/evidence/default-60-full.csv`](out/evidence/default-60-full.csv).
