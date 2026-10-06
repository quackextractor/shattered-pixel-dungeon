# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `gradle :superintelligence:gradcheck` (or `gradcheck --verbose`) finite-difference checks the
  network's analytic gradients against central differences and exits non-zero on a mismatch, so a
  broken backward pass fails loudly instead of silently training the wrong function.

### Fixed

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

  Verified: 6 rollouts of one seed across 6 separate JVMs produce 1 distinct score, for each of
  the five hero classes; a recorded 499-step run re-executes exactly 4 times out of 4.

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