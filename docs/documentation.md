# Documentation

Two documents live here. This one covers the **Superintelligence module**, the reinforcement-learning
framework built on top of Shattered Pixel Dungeon; the other three cover the game itself.

| Document | Covers |
| --- | --- |
| [documentation.md](documentation.md) | The Superintelligence module - headless RL framework |
| [getting-started-desktop.md](getting-started-desktop.md) | Running the game on desktop |
| [getting-started-android.md](getting-started-android.md) | Building for Android |
| [getting-started-ios.md](getting-started-ios.md) | Building for iOS |
| [recommended-changes.md](recommended-changes.md) | Making your own version of the game |

---

# Superintelligence

A headless reinforcement-learning framework for Shattered Pixel Dungeon. The game runs as a pure
simulation - no window, no renderer, no audio - so runs roll out thousands of turns per second, each
scored by a configurable reward function, with the best run per seed recorded and re-verified.

- [`superintelligence/README.md`](../superintelligence/README.md) - status, usage, design notes
- [`superintelligence/TODO.md`](../superintelligence/TODO.md) - what is missing, ordered by what
  unblocks learning first, plus deliberate deviations and what each costs
- [`superintelligence/testing-guide.md`](../superintelligence/testing-guide.md) - how each class of
  fault is tested, and with which command
- [`superintelligence/docs.md`](../superintelligence/docs.md) and
  [`research.md`](../superintelligence/research.md) - the design brief this was built from

## Status, honestly

**Nothing has been trained.** The machinery is correct - the network's gradients check against central
differences, the environment is reproducible across processes, recordings replay exactly - but no run
has lasted long enough to learn anything. The agent has never left floor 1. The milestone is beating the
first boss at depth 5 (`research.md`).

Read `TODO.md` for what stands between here and there. The two things that matter are a reward
function that asks for survival rather than tolerating it, and a run long enough to judge.

## Quick start

```sh
# one run, headless, with a report
./gradlew :superintelligence:rollout --args="--seed PROBESEED --max-turns 400"

# record it, then prove the recording still reproduces
./gradlew :superintelligence:rollout --args="--seed PROBESEED --save run.replay"
./gradlew :superintelligence:verify --args="run.replay"

# every correctness gate, one invocation
./gradlew :superintelligence:gates

# train
./gradlew :superintelligence:train --args="--workers 8 --generations 200"
```

## Architecture

```
Main ──▶ SPDEnv ──▶ LevelPipeline ──▶ the game's engine (Dungeon, Actor, Level)
             │                              with libGDX/noosa bypassed
             ├──▶ ActionMapper ──────────▶ Hero.handle, GameScene.selectCell
             ├──▶ ObservationEncoder ────▶ grid planes + inventory + hero scalars
             ├──▶ RewardModel ───────────▶ state diffing, per-turn scoring
             └──▶ ReplayRecorder ────────▶ decisions, not consequences

train.Trainer ──▶ TrainerWorkers ──▶ N worker JVMs, stdin/stdout protocol
      │                                  │
      └──▶ PPO.update ◀── transitions ◀──┘   (each worker computes its own GAE)
```

The split follows the two boundaries that matter. `PPO` decides what a minibatch computes and
`ShardedUpdate` decides how many threads compute it, because threading is a different responsibility with
a different failure mode. `Trainer` decides what work exists and `TrainerWorkers` decides how a worker
hears about it, because a pipe is not a learning problem. In both cases the moved half calls *into* the
kept half rather than copying it - one implementation of the objective, one implementation of the frame.

| Package | Responsibility |
| --- | --- |
| `headless` | libGDX/noosa shims: silent audio, classpath file resolution, inert `Game`, null GL, sprite that resolves animations instantly |
| `env` | `SPDEnv` (reset/step), `ActionMapper` (action injection and masking), `LevelPipeline` (run start, floor transitions, actor scheduling), `RunState` (what a new run must not inherit), `EnvConfigBinder` (settings from file and environment), `WindowBridge`, `SlotAction` |
| `obs` | `ObservationEncoder`, spatial channel definitions, fixed inventory vector, hero scalars |
| `reward` | `RewardModel` (state diffing), `RewardTerm`/`RewardLedger` (per-term, per-floor breakdown), `Curriculum` |
| `policy` | `ScriptedPolicy`, the network-free heuristic used for smoke tests and fixtures |
| `rl` | `Network` (CNN + LSTM + heads), `PPO` (the learner), `ShardedUpdate` (the parallel minibatch and its gradient reduction), `Policy` (masking, losses, GAE), `Transition`, `EpisodeCollector`, `EpisodeRecord` |
| `train` | `Trainer` (generation loop), `TrainerWorkers` (everything crossing a worker pipe), `WorkerPool`, `Protocol`, `TransitionCodec`, `TrainOptions`, `PpoHyperparameters`, `Checkpoint`, `MetricsHistory`, `SeedPool` |
| `replay` | `Replay`, `ReplayRecorder`, `ReplayIO` (write, read, verify), `ReplayCatalog`, `RngTrace` |
| `diag` | `RunReport`, `Graph`, `Ansi`, and the gates listed below |

### Four design decisions worth knowing before reading the code

**One run per JVM.** The game's simulation state is almost entirely static - `Dungeon.hero`,
`Dungeon.level`, `Actor.now`, `Level.visited`, the whole actor registry. Two environments cannot coexist
in one process, so parallelism is separate worker processes over a binary pipe, not threads.

**Renderer bypass, not renderer stubbing.** `Gdx.gl` stays null and every presentation path is made to
survive that, rather than a mock renderer that behaves like a real one. The consequence is that no
rollout can accidentally depend on something being drawn - which is the property that let a headless
run be compared against the rendered game at all.

**Interaction is one action.** `Hero.handle(cell)` already resolves "the right thing to do here" into
attack / loot / unlock / stairs / talk, so the policy learns intent rather than rediscovering which
`HeroAction` subtype a tile implies.

**Targeting and inventory are separate heads, and the environment pauses for them.** Predicting a
target on the 95% of turns that are not aiming spends capacity on nothing, and baking slot actions into
the output would tie the network's width to the hero's carrying capacity.

## Configuration

Settings come from four places. Lowest priority first:

| Source | How |
| --- | --- |
| Compiled defaults | `EnvConfig` and `PpoHyperparameters` |
| Properties file | `--config <path>`, e.g. `superintelligence/superintelligence.properties` |
| Environment | `SPD_*` - `rl.learning_rate` is `SPD_RL_LEARNING_RATE` |
| Command-line flags | `--max-turns`, `--workers`, ... - these win over everything |

A run with no `--config` and no `SPD_*` variables behaves exactly as it did before the file existed,
which is what makes adding one a non-event.

Two rules the layer holds to, both of which exist because the alternative is silent:

- **A value that cannot be read is refused, not defaulted.** Handing back a configuration nobody asked
  for is worse than declining to load, in a system whose whole value is that its settings are the ones
  it reports.
- **An unknown key warns, naming itself and its file.** A misspelled key tunes nothing, and an operator
  who mistyped one has no other way to find out.

`superintelligence.properties` ships with every key documented against its reason, and `configcheck`
asserts it agrees with the compiled defaults in both directions - so a key cannot drift away from the
code that reads it unnoticed.

## Gates

Nineteen gates, one invocation, ~22 seconds:

```sh
./gradlew :superintelligence:gates
```

| Gate | What it refuses to let happen |
| --- | --- |
| `gradcheck` | An analytic gradient that disagrees with a central difference |
| `gaecheck` | Two advantage implementations that disagree |
| `collectcheck` | A recording the collector cannot replay |
| `checkpointcheck` | A policy that does not survive disk, or a bad checkpoint that loads |
| `statecheck` | A sampled observation that does not replay to its rollout's value |
| `parallelcheck` | A parallel update that differs from the serial one |
| `rewardcheck` | Ending an episode being cheaper than dying |
| `replaycheck` | A catalog that misgroups, misranks or misresolves |
| `rolloutcheck` | A recording with no usable seed |
| `modecheck` | An action mode the environment cannot reach |
| `restartcheck` | A restart that does not rebuild the same first floor |
| `resetcheck` | A reset that does not isolate an episode from whatever ran before it |
| `graphcheck` | A chart reporting a range it did not observe |
| `actioncheck` | A movement action that does not move the way it is named |
| `slotcheck` | A recorded slot index resolving to a different item |
| `verifycheck` | Verification that cannot detect an altered recording |
| `observecheck` | Reading the world drawing from the gameplay RNG |
| `configcheck` | A configuration binder that ignores its input |
| `paritycheck` | A recording that will not replay exactly, across seeds in one process |

Plus `playbackcheck` in `:desktop`, which drives the rendered viewer with no window and no scene.

Every gate is mutation-tested: each one has had the thing it guards deleted, and the gate has been
required to fail. A check that cannot fail is not a check, and several of these exist only because the
property they protect had already been broken.

Two of them are worth singling out, because they reach faults the others structurally cannot:

- **`resetcheck`** proves a reset is a function of its arguments. It needs two episodes in one process,
  because with one there is nothing to leak from - which is why every single-episode check in the
  project passed while a static listener was carrying an aim across every reset.
- **`paritycheck`** proves a *recording* survives a write, a read, and a second episode in the same
  process. It is the only gate that lets the hero die repeatedly, which is where process-spanning game
  state such as a dead hero's remains becomes visible. It found that fault on its first run.

## Replay and determinism

A replay records decisions, not consequences. Playback re-applies them through the same `ActionMapper`
a live run uses and compares, after every step, against what the recording carries: hero position, HP,
engine time and inventory.

**It compares against the recording, not against a second run.** Re-executing a replay through the same
headless code only proves the engine is deterministic - both sides share every headless-specific fault.
That is not hypothetical: a run whose hunger clock was frozen by the `intro` setting replayed perfectly,
identical position at all 1500 steps, while the hero was starving in the real game.

Divergence is a hard error, not a warning. The moment a replay stops reproducing, every locked-seed
comparison in the trainer becomes meaningless, and a run that looks fine is worth less than one that
stops loudly.

### What determinism is guaranteed, and what is not

Guaranteed, and gated:

- A seed plus a hero class plus the recorded header is a function of the whole trajectory. Six rollouts
  of one seed across six separate JVMs give one distinct score.
- Observations are side-effect free - encoding state draws nothing from the gameplay RNG.
- Presentation randomness runs on its own generator, so a rendered run and a headless one consume the
  same gameplay stream. Measured: a 14-step floor draws within one call of identical in the trainer and
  the rendered game, against 249 before.
- A new run inherits nothing from the previous one - not an armed aim, not an open dialog, not a dead
  hero's remains.

**Not guaranteed, and worth stating plainly:**

- **Long horizons are unverified.** Determinism has been measured at 400 turns on a handful of seeds,
  not across all 26 floors, boss levels, the shop and the alchemy path - which is exactly where the
  exotic code lives. A long-horizon soak across many seeds is the right next check.
- **The PPO update truncates BPTT at length one.** Each stored observation is replayed through the
  current weights and backpropagated in isolation. The trunk, the heads and the critic get exact
  gradients; the LSTM gets exact gradients within a step and none across steps. An exact through-time
  gradient needs every timestep's pre-activation state retained until the update, which at this
  sequence length is gigabytes per worker.
- **`Mob` and `Char` do not override `hashCode`.** Nothing found iterates them through a hash collection
  during play, but that is an invariant a future change could break.
- **Six reward terms have no emission site**, and one (`KILL`) has the plumbing but no trigger. The
  cause is structural and is written up in `TODO.md` §D2.

## Changes to the game

The renderer bypass required small, additive, guarded changes to `:core` and `:SPD-classes`. Every one is
a no-op when a renderer is present, and each is listed with its reason in
[`superintelligence/README.md`](../superintelligence/README.md#changes-to-the-game). One was added for
this work:

- **`Bones.clear()`** - forgets a fallen hero's remains. Nothing in the game calls it; a normal
  playthrough wants the opposite. It exists because an environment playing many independent runs in one
  process cannot have the world depend on which hero died last.