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

7. The inventory slots / inventory system seems wrong. 

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
    `TODO.md` §0.7 with the hypothesis and with what a green `viewcheck` does not cover. (open)

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

13. Duelist-mid ends with stalled without really being stalled? What's causing this? It didn't seem like the hero was idling for this many actions. Investigate. Worst case: Remove stalled and regen the recording? thx.