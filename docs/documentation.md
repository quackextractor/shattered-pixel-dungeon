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
- [`superintelligence/ENGINE-CHANGES.md`](../superintelligence/ENGINE-CHANGES.md) - every change this
  work has made to `:core` and `:SPD-classes`, what each one costs, and what is still unfixed. Read this
  before touching game code for this project
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
| `observecheck` | Reading the world drawing from the gameplay RNG, **or** a draw path the RNG trace cannot see |
| `configcheck` | A configuration binder that ignores its input |
| `paritycheck` | A recording that will not replay exactly, across seeds in one process |

Plus `playbackcheck` in `:desktop`, which drives the rendered viewer with no window and no scene, and
`viewcheck`, which plays the whole corpus through the real viewer and needs a display.

`playbackcheck` also plays **the committed corpus** headlessly, which is the headless half of
`viewcheck` and the only one of the two that can be a gate. That half was added because every other
case there builds its own fixture, and a freshly recorded run does not end on a rest - which is how a
real viewer-versus-trainer divergence went unnoticed (see below).

Every gate is mutation-tested: each one has had the thing it guards deleted, and the gate has been
required to fail. A check that cannot fail is not a check, and several of these exist only because the
property they protect had already been broken.

Three of them are worth singling out, because they reach faults the others structurally cannot:

- **`resetcheck`** proves a reset is a function of its arguments. It needs two episodes in one process,
  because with one there is nothing to leak from - which is why every single-episode check in the
  project passed while a static listener was carrying an aim across every reset.
- **`paritycheck`** proves a *recording* survives a write, a read, and a second episode in the same
  process. It is the only gate that lets the hero die repeatedly, which is where process-spanning game
  state such as a dead hero's remains becomes visible. It found that fault on its first run.
- **`observecheck`** checks its own instrument. All 17 public `Random` draw paths and `shuffle`
  must move the counter, so "the draw counts match" means the streams match. It has to: `Random.Int`
  and `Random.shuffle` were advancing the generator without being counted, which made a headless run
  and a rendered run report identical counts at every step of a 126-step recording while their gameplay
  streams were 12 values apart. Six hypotheses about the divergence had been refuted by
  measurement before that was found, because the instrument that was supposed to catch it was blind to
  the draws that caused it.

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

### Watching a recording

`replay-viewer.bat` plays a recording in the real game with a HUD over it. The keys are:

| Key | Does |
| --- | --- |
| `SPACE` | pause / resume |
| `+` `-` `[` `]` | speed, coarser and finer |
| `R` | restart from floor 1 |
| `A` | move the HUD to the next corner |
| `ESC` | close the top window, else quit to the title |

The HUD is anchored **top left** by default. It used to be bottom left, which put the block directly
over the hero — and a recording spends most of its time on a hero that has not moved. Top left is the
one corner the game keeps clear: the depth banner is centred and the item log runs up the right edge.
`A` cycles all four corners rather than toggling between two, because which corner is right depends on
the window and on what else is on screen.

The HUD wraps rather than clipping. This is not incidental: `BitmapText` cannot wrap *or* break a line
— its font has no newline glyph, so a `\n` draws as a blank and the text keeps running right until it
leaves the window. The HUD therefore draws each line as its own gizmo, wrapped on spaces against the
real font. No `BitmapText` anywhere in the game is given a `\n`, because the game's own
`RenderedTextBlock` exists precisely because this one will not wrap.

### The last recorded step is a step like any other

A recording ends where the trainer terminated, and the final step is one the trainer *applied* - so it
is applied, drained and compared here too. It used not to be: the test for "the recording ends here"
sat at the top of the drain, so it fired on the frame the final action was injected and playback
stopped before the hero performed it. Three of the seventeen committed recordings end in death, and all
three of them stopped with the hero still standing.

Two things follow from doing it properly, and both were found only by doing it:

- **A step that was never compared could have been diverging all along.** Draining the last step made
  the viewer compare it for the first time, and `cleric-mid` failed immediately. `ReplayPlayer`'s drain
  had none of the trainer's resting-stall backstop, so it sat out a rest that
  `LevelPipeline.runToHeroReady` had already given up on: `Hero.act()` handles a resting hero with no
  action by spending time and calling `next()` without ever becoming ready. The viewer waited out the
  rest and reported the world seven turns and eight health later than the recording. The backstop is
  ported, and counting scheduler steps the way the trainer's does - as a field, not a loop variable,
  because the viewer yields the frame and a local counter never reached the threshold.
- **`playbackcheck` now plays the committed corpus**, because no locally built fixture ends on a rest.

The general lesson is the one this gate was written for: a step that is not exercised is a step that
is not checked, and a viewer that reports "clean" over an unexamined step is reporting on less than it
appears to.

### What determinism is guaranteed, and what is not

Guaranteed, and gated:

- A seed plus a hero class plus the recorded header is a function of the whole trajectory. Six rollouts
  of one seed across six separate JVMs give one distinct score.
- Observations are side-effect free - encoding state draws nothing from the gameplay RNG - and every
  draw path the RNG trace counts is one the trace can actually see.
- Presentation randomness runs on its own generator, so a rendered run and a headless one consume the
  same gameplay stream. This one was broken for a long time and is the reason
  [`../superintelligence/ISSUE-viewer-frame-drift.md`](../superintelligence/ISSUE-viewer-frame-drift.md)
  exists: four presentation draws were still on the gameplay stream, which put the viewer's 12 values
  from the trainer's and made 14 of 17 recordings diverge in the viewer while verifying exactly
  headlessly. The rendered viewer now reproduces the corpus, and
  [`../superintelligence/ENGINE-CHANGES.md`](../superintelligence/ENGINE-CHANGES.md) records every
  change made to the game to get there.
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
- **`GameScene.answerableWindow()`** - a getter that prefers the scene's live window over the headless
  dialog slot. `show()` fills the slot only when there is no scene, so a caller reading the slot alone
  saw no dialog in the rendered game, and a recorded `MENU` step resolved against nothing.
- **`PRandom.element`** - mirrors `Random.element` for the presentation stream, so a caller choosing
  presentation can be moved across without reimplementing the indexing.
