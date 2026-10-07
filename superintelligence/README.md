# Shattered Pixel Dungeon - Superintelligence

A headless reinforcement-learning framework for Shattered Pixel Dungeon, implementing the design
described in [`docs.md`](docs.md) and [`research.md`](research.md).

The game runs as a pure simulation - no window, no renderer, no audio - so runs can be rolled out
thousands of turns per second, each scored by a configurable reward function, with the best run per
seed recorded and re-verified.

## Read this first

**Nothing has been trained, and nothing is saved even if it were.** The loop now learns — see below —
but there is no checkpoint yet (`TODO.md` 1.3), so every run starts from a random initialisation and
is discarded at the end. The agent has never left floor 1.

The network does run, and its gradients are correct. `gradle :superintelligence:gradcheck`
finite-difference checks the analytic gradients against central differences on 112 sampled parameters
across all seven layers, and fails the build on a mismatch. It was worth adding: the backward pass
had five independent defects that all produced plausible numbers rather than an exception, and an
optimiser step that moved nothing.

**The trainer now updates on real data.** Workers play their own episodes with the real policy,
compute GAE over the whole episode, and ship a sample of the transitions; the trainer pools them
across every worker and applies one update. `policy=` and `value=` are non-zero. That is the first
time the loop has learned anything at all — and it is not yet a trained agent: the runs so far were
smoke tests of a few dozen generations each, and the barrier is currently ~6× the cost of collecting
the data. `gradle :superintelligence:gaecheck` keeps the two advantage implementations honest, and
`gradle :superintelligence:updatecost` measures what an update costs.

**The environment is verified reproducible**: 6 rollouts of one seed across 6 separate JVMs give 1
distinct score, for each of the five hero classes, and a recorded 499-step run re-executes 4/4.
That was not true at first. An early smoke test reproduced twice and I took that as proof, but a
six-run audit found four distinct traces, and a second audit found more identity-hash iteration
order plus six uses of `Collections.shuffle` that ignores the seeded generator entirely. See
`TODO.md` section 0.

**Seed text is capped at 20 characters.** The game silently discards anything longer and falls
back to a random seed, which looks exactly like residual nondeterminism. The pipeline now fails
with an explanation instead.

The environment, observation encoder, reward ledger, replay verification and diagnostics are done and tested. The
learning loop runs end to end across worker processes; nothing has been trained for long enough to
behave differently from random yet.

[`TODO.md`](TODO.md) is the full status: what is missing, ordered by what unblocks learning first,
plus a section on deliberate deviations from the source documents and where each one costs
something.

## Status

| Area | State |
| --- | --- |
| Headless engine (renderer bypassed, single-threaded scheduler) | Verified, reproducible across processes |
| Level pipeline, floor transitions, chasm falls | Verified |
| Action space, action masking, menus, targeting | Verified |
| Observation encoder (spatial planes + inventory + hero scalars) | Verified |
| Replay record / re-verify | Verified exact - 4/4 fresh processes, identical score |
| Gradient check vs central differences | Verified - `gradcheck` passes, and fails when a derivative is removed |
| Update cost measurement | Measured - `updatecost`, 11.3 ms/sample, projects across sample rates |
| Diagnostics dashboard (colour-coded floors, graphs) | Console only |
| Reward model, per-term ledger, curriculum fade | Partial - 6 terms never fire |
| CNN + LSTM network, PPO agent | Verified; **PPO now runs on real worker data** |
| Weight save / load, resume a run | **Missing** - blocks any training that has to be kept. See `TODO.md` 1.3 |
| Trained anything yet | **No.** Never left floor 1; milestone is depth 5 (`research.md:40`) |
| Parallel worker processes, seed schedule | Running; exercised to 8 workers x 25 generations |
| Graphical trainer UI, desktop replay viewer | Not started |
| Garbage collection / object pooling audit (research.md:60) | Not started |

## Usage

```sh
# play one run headlessly and print the report
./gradlew :superintelligence:rollout --args="--seed PROBESEED --max-turns 400"

# record a run as a replay
./gradlew :superintelligence:rollout --args="--seed PROBESEED --save run.dat"

# replay a recorded run and confirm it still reproduces
./gradlew :superintelligence:verify --args="run.dat"

# check the network's analytic gradients against central differences
./gradlew :superintelligence:gradcheck

# check the two advantage implementations agree and sampling behaves
./gradlew :superintelligence:gaecheck

# measure what a PPO update costs, and project it across sample rates
./gradlew :superintelligence:updatecost

# train

./gradlew :superintelligence:train --args="--workers 8 --generations 200"
```

`probeClasspath` prints the runtime classpath, which is what the trainer uses to launch workers.

**Batch size is chosen against compute, not bandwidth.** An update costs 11.3 ms per sample on this
machine, almost all of it forward and backward rather than Adam, and it runs on one thread — so at a
5% sample rate and 4 epochs it is about 107 s of trainer CPU per generation, against 0.12 s of
transport. Transport is not what limits this trainer; the update is.

## Layout

| Package | Responsibility |
| --- | --- |
| `headless` | libGDX/noosa shims: silent audio, classpath file resolution, inert `Game`, headless `Graphics`, sprite that resolves animations instantly |
| `env` | `SPDEnv` (reset/step), `ActionMapper` (action injection and masking), `LevelPipeline` (run start, floor transitions, actor scheduling), `WindowBridge`, `SlotAction` |
| `obs` | `ObservationEncoder`, spatial channel definitions, fixed inventory vector, hero scalars |
| `reward` | `RewardModel` (state diffing), `RewardTerm`/`RewardLedger` (per-term, per-floor breakdown), `Curriculum` |
| `policy` | `ScriptedPolicy`, the network-free heuristic used for smoke tests and worker bootstrap |
| `rl` | `Network` (CNN + LSTM + heads), `PPO` (buffer + update), `Policy` (masking, losses, GAE), `Transition`, `EpisodeCollector` (plays an episode, computes its advantages), `EpisodeRecord` (one episode's scalars + sampled observations) |
| `train` | `Trainer` (generation loop), `WorkerPool` (processes, pipes, stall watchdog), `Protocol` (wire format), `TransitionCodec`, `TrainOptions`, `Episode`, `Worker` (worker side), `SeedPool` (generalisation schedule) |
| `replay` | `Replay`, `ReplayRecorder`, `ReplayIO` (write, read, verify) |
| `diag` | `RunReport`, `Graph`, `Ansi`, and the checks: `GradientCheck`, `ModeCoverageCheck`, `RestartCheck`, `GaeCheck`, `UpdateCostCheck` |

## Design notes

**One run per JVM.** The game's simulation state is almost entirely static - `Dungeon.hero`,
`Dungeon.level`, `Actor.now`, `Level.visited` and the whole actor registry. Two environments cannot
coexist in one process. Parallelism is therefore separate worker JVMs talking to the trainer over
stdin/stdout, not threads.

**Renderer bypass, not renderer stubbing.** Rather than mocking the renderer into behaving like a
real one, `Gdx.gl` is left null and every presentation path is made to survive that. Texture loading
returns null, nine patches become blank, sprites resolve animations instantly, and `Chrome` returns
untextured chrome. The consequence is that no rollout can accidentally depend on something being
drawn.

That does not mean nothing native is loaded. The desktop build gets the libGDX natives implicitly,
through `Lwjgl3NativesLoader`; the headless backend replaces that, and a natives jar on the classpath
does not load itself. `HeadlessServices.install()` loads them explicitly. Without it the first
`Flare` or `ColorBlock` reached from item logic dies with `UnsatisfiedLinkError` - and only now, with
a policy that actually picks items, is that reachable at all.

**Interaction is one action.** `Hero.handle(cell)` already resolves "the right thing to do here"
into attack / loot / unlock / stairs / talk. Exposing that as a single action lets the policy learn
intent rather than rediscovering which `HeroAction` subtype a tile implies.

**Targeting and inventory are separate heads.** The environment pauses for them. Predicting a target
on the 95% of turns that are not aiming wastes capacity and invites spurious correlations, and
baking 32 item actions into the output would tie the network's width to the hero's carrying capacity.

**Rewards are measured, not hooked.** `research.md` suggests placing hooks in the item and buff
trees. Diffing observable state is used instead: it needs no edits to hundreds of item classes, and
it measures what an action *achieved* rather than which code path it took. A potion that heals
through a wand and a scroll that heals through an upgrade both read as "hero gained health".

**Replay is verified, not assumed.** A replay records decisions, not consequences, and playback
compares the hero's position after every step against what was recorded. A replay that stops
reproducing means the seed lock has been broken somewhere, and every locked-seed comparison the
trainer makes becomes meaningless - so divergence is a hard error.

### Known approximation

The PPO update replays each stored observation through the current weights and backpropagates each
step in isolation, so the recurrent gradient is truncated at length one. An exact through-time
gradient needs every timestep's pre-activation state retained until the update, which at this
sequence length is gigabytes. The trunk, the heads and the critic all get exact gradients; the LSTM
gets exact gradients within a step and none across steps.

## Changes to the game

The renderer bypass required small, additive changes to `:core` and `:SPD-classes`. All are guarded
rather than behaviour-changing when a renderer is present:

- `Actor.headlessStep()` - one scheduler iteration without the render-thread handshake.
- `GameScene` - `show`, `selectCell`, `checkKeyHold` and `resetKeyHold` handle a null scene /
  cell selector, and stash pending dialogs and aiming requests for a headless caller.
- `Button.press()`, `WndOptions.optionCount/optionSelectable/selectOption` - let a caller drive a
  dialog with no UI.
- `CharSprite.sprint/updateArmor/read/fall` - presentation hooks moved up from `HeroSprite` and
  `MobSprite` so game logic stops depending on the concrete sprite type.
- `Item.throwAt()` - the sprite-free half of `Item.cast`.
- `Chrome.get()`, `ShadowBox`, `NinePatch`, `TextureCache`, `PlatformSupport` - tolerate a null GL
  context.
- `ItemSpriteSheet.Icons.film()` - the icon film is built on first use, so item constructors no
  longer force a texture decode. The icon *indices* are game data and are still pure arithmetic.