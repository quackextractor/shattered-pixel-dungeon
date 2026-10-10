# Viewer / recordings

1. When using replay-viewer.bat, after a recording finishes and the user presses 'R', after reset the recording still says finished. When the user then tries to press spacebar to continue, the game thinks it's the waiting keybind and the recording doesn't play, instead diverges due to wait action. (closed)

2. When using replay-viewer.bat, the viewer doesn't actually play out the last step where the hero dies. Instead it finishes just before that. (closed)

3. Turns and turn limit is a bit misleading, since it's actions, as turns in the engine are often not whole numbers and certain items and conditions can affect the amount of turns an action takes. (closed)

4. The recordings are missing information about how the score changes with each action. Very important to see in the viewer as a live info. Additionally there should be a way to see total score gained and lost as well, not just their sum. (closed)

5. Recordings show coordinates (such as during diverted) as a single number. It'd be much easier for a human to troubleshoot if they were in x,y. With x increasing to the right of the screen and y increasing with height, but staying true to engine. So highest y value is at the bottom. (closed)

# Env

6. I am unsure if the env.gid dimensions are accurate. I read online that the max is 32x32. This number might've changed but a code sweep might be in order.

"Each standard dungeon floor in Shattered Pixel Dungeon is hardcoded to a grid size of 32x32 tiles.   

While the absolute boundaries of the floor remain locked at 32x32, the playable layout is procedurally generated within that space, leaving some of the grid as unused wall space. The rooms that generate within this grid fall into three primary size categories:   

    Normal rooms: Ranging from 2x2 to 8x8 tiles.

    Large rooms: Ranging from 8x8 to 12x12 tiles.

    Giant rooms: Ranging from 12x12 to 16x16 tiles.
"

8. The inventory slots / inventory system seems wrong. 

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
    recording in the corpus that descends, and it does this on its second floor change.

    Both environments now agree on the symptom and disagree on the world:

    ```
    DIVERGED at step 161 - MOVE_SE/0 in WORLD: hero at (11, 9) pos 317, recording says (10, 8) pos 282
    ```

    `:desktop:viewcheck` and `:desktop:playbackcheck` report it identically, so it is not a viewer
    artefact, and it is what is currently keeping `verifyall` red.

    Two differences between `InterlevelScene.ascend()` and `LevelPipeline.handleTransition()` are known
    and neither is confirmed as the cause:

    - `ascend()` calls `Mob.holdAllies(Dungeon.level)` and `Dungeon.saveAll()`; the pipeline calls
      neither. `Mob.holdAllies` still uses `Collections.shuffle`, which ignores the seeded generator
      entirely and has been an open engine gap since `ENGINE-CHANGES.md` §7 recorded it. That is the
      prime suspect and it has not been tested. It is an engine file.
    - `ascend()` calls `Dungeon.loadLevel(GamesInProgress.curSlot)` when
      `Dungeon.levelHasBeenGenerated(depth, branch)` is true, reading a `depth<n>.dat` a viewer run has
      never written. `ReplayPlayer` now clears `Dungeon.generatedLevels` on the frame a transition is
      pending, which is what the pipeline already does and why; that removed the error window and the
      hang, and did **not** remove the divergence.

    Next step is to make the two paths agree, in the same way 9 was closed: find the mechanism by
    measurement rather than by reading, and put it behind a case that fails when it is reinstated.
