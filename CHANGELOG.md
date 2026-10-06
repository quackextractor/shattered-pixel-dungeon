# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [4.1.0] - 2026-10-06

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

[4.1.0]: https://github.com/00-Evan/shattered-pixel-dungeon/compare/v4.0.1...v4.1.0
[Unreleased]: https://github.com/00-Evan/shattered-pixel-dungeon/compare/v4.1.0...HEAD