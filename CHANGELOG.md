# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Every episode ended `STALLED` after 8-13 turns,** which made the environment useless for
  collecting experience. `Hero.act()` was being entered with a null `curAction`: it clears `ready` and
  then dispatches on `curAction`, so no branch matches, and `Hero.ready()` is the only thing in the
  game that sets `ready` back to true. A normal playthrough never reaches that state because the cell
  selector re-prompts for input, and that prompt is what calls `ready()`; headless there is no scene
  and no selector, so nothing was left to re-prompt. Five seeds that all stalled now run the full
  turn budget.
- **`MENU` mode was unreachable in a real run,** not merely hard to record. `step()` cleared the
  pending dialog on every turn, so the step that could have answered it arrived to find it gone and
  the rollout sat selecting at nothing until it stalled. Clearing now happens on `reset()`, where
  per-run state belongs.
- **The `MENU` branch never returned to `WORLD`,** so a dialog could be answered successfully and the
  mode still never changed hands again.
- **`WindowBridge.open()` meant "a window is showing" rather than "a dialog the agent can answer."**
  `GameScene.show` parks informational windows there too, and "you cannot leave the dungeon yet"
  arrives that way constantly, so each one put the environment into `MENU` with nothing to select.
- **`SLOT` had no entry in the action mask** and fell through to the `WORLD` mask, which offered
  movement and `WAIT` while the environment was waiting for an item choice. `SlotAction.execute`
  treats every action that is neither `CANCEL` nor `DROP` as a use, so a legal-looking move there spent
  the item.
- **`EnvMode.INVENTORY` was unreachable.** The mode was documented on `Action.OPEN_INVENTORY` but
  never assigned, and `OPEN_INVENTORY` fell through to the generic cell handling as a no-op, so no
  rollout could ever contain the mode and no replay could exercise it. It is now entered from the
  `WORLD` step and offered in the `WORLD` action mask, and its branch resolves an aim the same way
  `SLOT` does instead of dropping it.
- **Throwing stones were equipped rather than thrown.** `SlotAction.use` tested equipability before
  the item's own action, and `Weapon extends EquipableItem`, so a missile weapon took the equip path.
- **Every equipment change invented a targeting step.** `SlotAction.use` returned `toggleEquip`'s
  "equipped OK" boolean, which the environment read as "needs an aim", and its success path returned
  `true` outright, so plain consumables falsely demanded an aim too.
- **A refused equip stalled the episode.** `toggleEquip` returned without releasing the hero, which
  left it permanently mid-action; the pipeline never saw it become ready and the run ended `STALLED`
  within about a dozen turns.
- **No aim could ever be resolved or cancelled.** `SPDEnv.step` cleared the pending use item on every
  step, including the `TARGETING` step that needed it. `CANCEL` during targeting was also treated as a
  throw at the default target, spending the item. `CANCEL` is now handled on its own and releases the
  hero's turn.
- **The replay viewer could leave the game completely unresponsive.** Reaching a transition the hero
  cannot use raises an informational `WndMessage` - "you cannot leave the dungeon yet" - and windows
  are modal, so the scene stopped accepting input while playback carried on, driven by the frame hook
  rather than by keys. The viewer now dismisses windows a recording has no step to answer, and leaves
  `WndOptions` alone because a recorded `MENU` step does resolve that one.
- **`lockCellInput` did not lock the keyboard.** It disabled the cell selector's pointer path, but the
  selector's own `KeyEvent` listener ignored `enabled` and kept turning arrow keys into movement, so a
  viewer could still have its hero walked away and desync the recording.
- **Viewer keys could not override a game binding.** `KeyBindings.getActionForKey` consults
  `hardBindings` last, so forcing `SPACE` to nothing lost to the default `SPACE`→`WAIT` and a press
  both paused and waited the hero. `KeyBindings` gained an explicit override layer for this instead of
  reordering the existing lookup, which would have broken players who rebound `ENTER` or `ALT_RIGHT`.
- **`R` did not restart.** It rewound the cursor while leaving the live game where it was; it now
  rebuilds the level through the same path the viewer starts on.
- **Viewer controls died after `R`.** `InterlevelScene` calls `KeyEvent.clearListeners()` to drop its
  own continue-button listener, which clears every listener including the viewer's. The viewer
  re-registers on each scene rebuild.
- **The replay HUD vanished after `R`,** because it was rebuilt against the outgoing scene and the
  non-null field then prevented a rebuild. It is now rebuilt whenever the live scene changes.
- **Resuming a diverged replay re-reported the same divergence forever.** Divergence is now sticky,
  since a diverged game can never rejoin the recording's path.
- **On-screen replay divergence could never fire.** `ReplayPlayer` advanced the playback cursor before
  comparing the landing cell, and advancing clears the expected position, so the check always passed.
- **`GameScene` gained `topWindow()`.** `showingWindow()` only reports whether a window exists, which
  is not enough to decide what to do about one.

### Added

- **`gradle :superintelligence:modecheck`** fails if the environment cannot reach one of its own
  action modes. It drives an explicit script through `WORLD`, `SLOT`, `TARGETING`, `INVENTORY` and
  `MENU`, resolves a real aim rather than cancelling one, and executes a drop. Six real bugs reached
  main while every recording was `WORLD`-only, and a replay fixture cannot prevent a recurrence:
  nothing failed when a mode quietly stopped being reachable.
- **`gradle :superintelligence:restartcheck`** fails if restarting a run does not rebuild an identical
  floor 1, even after several hundred turns have churned the random generators.
- **`replay-viewer.bat`** plays, records and verifies recordings from a terminal: `replay-viewer`
  plays the newest one, `--record <seed>` makes one, `--verify <file>` checks one, `--list` shows them.
  It drives Gradle tasks rather than hand-building a classpath, and resolves paths to absolute first
  because Gradle's `run` task uses the module directory as its working directory.
- **Replay fixtures in `replays/`,** including `modes-all.replay`, whose first six steps cover `WORLD`,
  `SLOT`, `TARGETING` and `INVENTORY`. Every recording made before this was pure movement.
- **Diagnostics on stderr.** The viewer reports each keypress and a per-two-second heartbeat, because
  "the key did nothing" and "the render loop is stalled" look identical from the outside and need
  different fixes.

### Added


- **Desktop replay viewer.** `gradle :desktop:replay --args="--file <replay>"` plays a recorded run
  back in the rendered game. Recorded actions are applied through `ActionMapper`, which hands them to
  `Hero.handle` — the same call the game's own cell selector makes — so attack-versus-loot-versus-
  stairs-versus-menu resolves exactly as it does for a player. The HUD shows step count, seed, hero,
  recorded score, depth, turns, speed and any divergence.

  Two engine hooks were needed, both general rather than replay-specific: `Game.lockCellInput`
  disables the cell selector each frame so nothing but the recording can inject an action, and
  `Game.setSceneClass` lets an entry point that builds the game choose its initial scene.
  `InterlevelScene.autoContinue` skips the region continue prompt so a viewer run starts on its own,
  and the viewer's keys are hard-bound because `InputHandler` only emits a `KeyEvent` for keys present
  in `KeyBindings`, which left space, `R` and `+`/`-` unreachable.

  Entering through `InterlevelScene` with `Mode.DESCEND` is what makes this work: `InterlevelScene`
  switches to `GameScene` hardcoded, so a `GameScene` subclass would never be entered. A static
  `frameDriver` hook pumps playback instead, and the recording is watched in the real scene with real
  sprites.


- `PPO.rolloutCap` bounds collection between updates. It is not only a memory guard: PPO measures
  how far the policy has drifted since it collected the data, so frequent updates are what PPO
  wants anyway.

- `gradle :superintelligence:gradcheck` (or `gradcheck --verbose`) finite-difference checks the
  network's analytic gradients against central differences and exits non-zero on a mismatch, so a
  broken backward pass fails loudly instead of silently training the wrong function.

### Fixed

- **An uncapped PPO rollout was an out-of-memory crash waiting to happen.** Collection stored every
  step of an episode until the next update. A step is dominated by its packed grid at ~49KB and
  `turnLimitTotal` is 40000, so one long episode is ~1.9GB against a 1536m worker heap.
  `PPO.collect()` now stops at `rolloutCap` (default 2048, ~98MB) and leaves the env mid-episode,
  resuming it with the recurrent state intact on the next call, so a long episode is collected
  across several calls instead of being cut short. Each collection is its own advantage segment.

  An update invalidates the hidden state of an episode still in progress - it changes the weights
  that state came from, and resets the state itself to shuffle minibatches. A weight change is now
  an information boundary and the agent forgets, because otherwise where updates happened to land
  would silently change an episode's actions and the same seed would stop reproducing.

- Three headless crashes that the scripted policy never reached but a random one walks into
  immediately, all the same shape of problem as a missing sprite:
  - Blobs had no emitter, so any of the fifteen blob types calling `emitter.pour()` from
    `evolve()` died with an NPE. `LevelPipeline` now attaches them alongside sprites.
  - `GameScene.cancel()` and `cancelCellSelector()` dereferenced a null `cellSelector`, so using
    any item crashed.
  - `GameScene.spellSprite()` read `scene.spells` with no null check, unlike the `emitter()`
    directly beside it, so eating food crashed.

- **The policy network's gradients were wrong in five separate ways, so training optimised a
  function other than the policy loss.** The network had never been executed before this, so none
  of the failures showed up as an exception - they produced plausible numbers and an optimiser
  step that moved nothing.
  - `Conv2D.backward` and `Dense.backward` omitted their activation derivatives, so no gradient
    reached the convolution or the trunk weights.
  - `LSTM.backward` read `h` and `c` that `forward` had already advanced, and used `c_t` where
    the derivative needs `c_{t-1}`. It now caches the gate pre-activations and both prior states.
  - `Network.backward` copied the hidden gradient into the LSTM *before* the heads had accumulated
    into it, so the recurrent cell always received zero.
  - The LSTM's input gradient buffer was never sized or cleared, so the gradient never reached the
    trunk. It is now sized from the cell's input width and refilled by `backward`.
  - `Network.headGradient` hand-rolled `W^T * dOut`, which skipped the head's own parameter
    gradient and its tanh derivative. It now goes through `Dense.backward`.
  - Also fixed the LSTM output being written back into the wider trunk buffer, so the heads were
    fed the wrong width, and a trunk input gradient buffer that was too narrow by the
    inventory and hero block.

  Verified against central differences: `gradle :superintelligence:gradcheck` compares analytic
  gradients to numeric ones on 112 sampled parameters across all seven layers, and fails the build
  if they disagree. The check has been confirmed to fail when a derivative is deliberately removed.

- **More identity-hash iteration in gameplay and level generation.** Collections keyed on
  identity hash codes iterate in a different order in every JVM, and `java.lang.Enum` inherits
  `Object.hashCode`, so enum-keyed maps are affected too. Sites where that order reached the
  outcome were made insertion-ordered:
  - `Char.buffs(Class)` returned a `HashSet`. Callers sort or iterate the result while consuming
    the RNG or folding floats non-associatively - `ShieldBuff.processDamage` distributes damage
    across barriers in that order - so damage and death could differ per run. Now a
    `LinkedHashSet`.
  - `Random.chances(HashMap)` picks off the map's iteration order, so the secret laboratory and
    secret library chose a different potion or scroll every run, changing what the floor
    contained. Their chance tables, and the ones in `WandOfCorruption`, `UnstableSpell`,
    `UnstableBrew` and `ChaoticCenser`, are now `LinkedHashMap`.
  - `Mob.chooseEnemy` resolved equal-distance and equally-attackable candidates by iteration
    order, so which of two equidistant mobs got targeted varied. Now insertion-ordered.
  - `CursingTrap`, `VaultLevel` and `Hero` used `Collections.shuffle`, which ignores the seeded
    generator entirely and is therefore different on every run. Switched to `Random.shuffle`.

- **A seed longer than 20 characters was silently discarded**, and the run continued on a random
  seed. `GameSettings.getString(key, def, maxLength)` treats an over-long stored value as corrupt
  and overwrites it with the default, and `SPDSettings.customSeed()` reads with a 20 character
  cap. A rollout asked to reproduce would simply not reproduce, with nothing reporting it.
  `LevelPipeline.startRun` now verifies the seed survived the round trip and fails with an
  explanation instead.

- `gradle :superintelligence:rollout` and `:verify` never passed their own subcommand to the
  entry point, so `--args` was parsed as the command and both tasks failed. The subcommand is now
  prepended at execution time, which also keeps working when `--args` is supplied.

  Verified: 120 rollouts - 4 seeds x 5 hero classes x 6 repeats - produce one distinct score per
  seed/hero pair, and a recorded 499-step run re-executes exactly 4 times out of 4.

## [4.1.0] - 2026-10-06

### Fixed

- **The environment is now reproducible across processes.** An audit found that recording a run
  and re-executing it in a fresh JVM usually reproduced it but not always - six runs of one seed
  produced four distinct traces. Three causes:
  - `Random.resetGenerators()` installs an unseeded base generator, and `Dungeon.init` called it
    after pushing the seeded stack, discarding it. Only level generation drew from a seeded
    generator; mob turns, combat rolls and item drops did not. Added `Random.reseedBase(long)` and
    call it from `Dungeon.init` with the run seed.
  - `EntranceRoom.placeEarlyGuidePages` pushed an unseeded generator during level generation,
    deliberately, to keep meta progression out of levelgen. The first guidebook page therefore
    landed on a different tile in every process, making floor 1 irreproducible on its own. Now
    seeded from the floor's seed with a fixed offset.
  - `Actor.all`, `Actor.chars`, `Level.mobs` and `Level.blobs` were hash-based collections keyed on
    identity hash codes, so iteration order differed between JVM runs. `Actor.headlessStep` broke
    time ties on that order, so who moved first varied. All four are now insertion-ordered
    (`LinkedHashSet` / `LinkedHashMap`); membership semantics are unchanged.

  Verified: 10 traces of one seed across 10 separate JVMs produce one distinct result, and a
  recorded 299-step run re-executes exactly 5 times out of 5 with a bit-identical score.

- `GameScene.add(Heap)` left `heap.sprite` null when there is no scene, so trampling high grass
  dereferenced it and crashed a rollout.
- `Image.frame` derived its size from a null texture when there is no renderer.

### Added

- **Headless training framework** (`superintelligence` module). Implements the design in
  `superintelligence/docs.md` and `superintelligence/research.md`.
  - `headless`: libGDX and noosa shims so the game runs with no renderer, no window and no audio -
    silent `Audio`/`Sound`/`Music`, classpath-aware `Files`, in-memory `Preferences`, an inert
    `noosa.Game`, a fixed-size `Graphics`, and a `CharSprite` that resolves animations instantly so
    combat, doors and chests still resolve their bookkeeping.
  - `env`: `SPDEnv` with a reset/step interface; `LevelPipeline` that starts runs, drives the actor
    scheduler single-threaded and services floor transitions and chasm falls without entering the
    scene system; `ActionMapper` that injects decisions through `Hero.handle` and produces the
    legal-action mask; `WindowBridge` and `SlotAction` for dialogs and inventory.
  - `obs`: `ObservationEncoder` producing binary spatial planes (visible vs. remembered fog of war),
    a fixed-length inventory vector with per-slot identity, level, curse and identification state,
    and normalised hero scalars.
  - `reward`: `RewardModel` scoring by state diffing, a `RewardTerm` per documented reward and
    punishment, `RewardLedger` keeping per-term and per-floor totals, and `Curriculum` fading the
    dense shaping terms out as depth is mastered.
  - `rl`: `Network` (one convolution over the grid, an LSTM for fog-of-war memory, and heads for
    action, slot, target and value), `PPO` with masked sampling, clipped surrogate, GAE and Adam.
  - `train`: `Trainer` pooling episodes across worker JVMs over a binary stdin/stdout protocol,
    `SeedPool` implementing the one-seed to ten to hundred to random generalisation schedule.
  - `replay`: `Replay`/`ReplayRecorder`/`ReplayIO` recording decisions rather than consequences, and
    verifying playback reproduces.
  - `diag`: `RunReport` rendering the floor-by-floor breakdown with green/red colouring, per-term
    totals, and score-over-turns and score-over-floors graphs with floor transitions annotated.
  - `policy.ScriptedPolicy`: a network-free heuristic used as an environment smoke test and as the
    worker bootstrap before the first checkpoint exists.
  - Gradle tasks `rollout`, `replay`, `train`, `probeClasspath` and `workerJar`.

### Changed

- `ItemSpriteSheet.Icons` builds its `TextureFilm` on first use instead of in a static initialiser.
  Icon indices are game data and remain pure arithmetic, but item constructors no longer force a
  texture decode through a GL context.
- `CharSprite` gains `sprint`, `updateArmor`, `read` and `fall` as overridable no-ops, hoisted from
  `HeroSprite` and `MobSprite`, so game logic no longer depends on which sprite a character uses.
- `AttackIndicator.target`/`updateState`, `QuickSlotButton.target`, `GameScene.selectCell`,
  `GameScene.checkKeyHold` and `GameScene.resetKeyHold` tolerate absent UI singletons.
- `TextureCache.getBitmap` returns null without a GL context, and `TextureCache.get` no longer
  caches or wraps a null bitmap. `NinePatch` accepts a null texture and a new no-argument
  constructor for texture-free use. `Chrome.get` and `ShadowBox` return blank chrome without a
  renderer. `PlatformSupport.getSafeInsets` returns empty insets without a display.

### Added (game)

- `Actor.headlessStep()` - one turn-scheduler iteration with no render-thread handshake, for
  single-threaded headless rollouts.
- `GameScene.show` records a dialog when there is no scene; `GameScene.pendingCellListener` records
  an aiming request when there is no cell selector. `WndOptions.optionCount`,
  `optionSelectable` and `selectOption` plus `Button.press` let a caller enumerate and answer a
  dialog with no UI.
- `Item.throwAt()` - the bookkeeping half of `Item.cast`, without a projectile sprite.

### Documentation

- `superintelligence/TODO.md` - status against the design documents: what still needs doing, ordered
  by what unblocks learning first; deliberate deviations with the cost of each; and the source
  documents' references to classes that no longer exist in this version.

### Renamed

- `ReplayIO.play` is now `ReplayIO.verify`, and `ReplayIO.Result` is `ReplayIO.Verification`. The
  operation re-executes a recording to check determinism and draws nothing; `play` implied a human
  watching the game, which the desktop viewer - still to be built - would do. The `replay` CLI
  subcommand and Gradle task are now `verify`. The `Replay` file format keeps its name: a recorded
  run that can be re-executed is a replay.

[4.1.0]: https://github.com/00-Evan/shattered-pixel-dungeon/compare/v4.0.1...v4.1.0
[Unreleased]: https://github.com/00-Evan/shattered-pixel-dungeon/compare/v4.1.0...HEAD
