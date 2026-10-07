# Shattered Pixel Dungeon - Superintelligence

A headless reinforcement-learning framework for Shattered Pixel Dungeon, implementing the design
described in [`docs.md`](docs.md) and [`research.md`](research.md).

The game runs as a pure simulation - no window, no renderer, no audio - so runs can be rolled out
thousands of turns per second, each scored by a configurable reward function, with the best run per
seed recorded and re-verified.

## Read this first

**Nothing has been trained yet. The machinery is correct; the reward still does not ask for
anything.** Five things were missing; four are fixed and the fifth is the milestone itself:

- **A run can be judged.** `clip=` used to report `P(|N(0,1)| > 0.2)` = 0.8415 whatever the policy
  did, because it counted `|advantage| > clipEpsilon` on a *normalised* advantage. The real
  ratio-clip fraction is now counted. Every run appends a row to `metrics.csv` and prints an
  end-of-run trend.
- **A run can be resumed.** `--save` writes a checkpoint every 25 generations and on exit, `--resume`
  continues from one. Atomic, so a power cut cannot leave a half-written file that is newer than
  the last good one. The checkpoint carries Adam's moments and step count, not just weights.
- **The update was computing the wrong thing, three ways.** The replay ran under the wrong recurrent
  state, so the ratio `pi_new / pi_old` was formed across two different states; the critic was tanh-
  bounded to [-1,+1] against a death penalty of 100; and `dCell` was not cleared between samples, so
  every gradient depended on whichever sample preceded it. All three are fixed, and each has a
  gate that fails when the fix is reverted.
- **The reward paid the agent to give up.** `STALLED` cost -5.0 against death's -100, and the cheapest
  action in the game — `WAIT`, which moves nothing — walked straight into the guard that triggers it.
  A 20-generation run converged `meanScore` on exactly -5.0 with turns collapsing 62 → 8. `STALLED` is
  now zero and priced by turn cost alone. Measured after: `meanScore` +3.29, `meanTurns` 56,
  `valueLoss` 0.44. See [`PLAN-reward-signals.md`](PLAN-reward-signals.md).
- **`REST` was a one-way door, and that was the real cause of a 100% stall rate.** The agent was not
  choosing to idle - it was being trapped. `ActionMapper` set `hero.resting = true` without setting a
  `curAction`, so `Hero.act` took the rest branch forever and never called `ready()`; and
  `recoverStrandedHero` refuses a resting hero, correctly, because a *player* escapes rest by choosing
  another action - headless input has nobody to do that. Instrumenting the two stall guards settled it:
  36 of 36 stalls came from `LevelPipeline` and **zero** from the idle guard, at turns 10-51 rather than
  121. Any non-`REST` action now ends the rest. Stall share is 0% and depth 2 appears in training for
  the first time.
- **Two further faults were hidden behind that one**, both unreachable while episodes ended at turn ~50.
  Headless has no GL context and every `TextureFilm` constructor dereferenced the null texture, so hunger
  damage - which touches a statically-initialised film - killed worker processes. And `HeadlessSprite.die`
  invoked the death callback to fake an animation, recursing `Hero.die` -> `Char.die` -> `sprite.die`
  until the stack gave out, which means `DEATH`, the ending the whole reward function is built around,
  was unreachable. All three are fixed and gated; see [`PLAN-reward-signals.md`](PLAN-reward-signals.md) sections 7-8.
- **What remains is neither a crash nor a stall.** Every episode now runs the full 1500 turns with zero
  stalls, and `meanScore` is falling (186 -> 59 across 6 generations) as the critic fits longer episodes.
  But `bestDepth` is still 1 and nothing dies. `depthReward` of +10 against `turnCost` of 0.002 means
  descending pays 5,000 turns of idling, and `KILL` / `GOLD_GAIN` / `ITEM_PICKUP` still never fire
  (`TODO.md` 1.8).

The update is 3.29x faster at `--update-threads 4`, so a 100-generation run is now a matter of
hours rather than most of a day. **What is left is to run one and find out whether it learns**
(`TODO.md` 1.4). See [`PLAN-data-flow.md`](PLAN-data-flow.md), whose steps are all resolved.

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
| Diagnostics dashboard (colour-coded floors, graphs) | Console only; live, with a per-generation history behind it |
| Per-generation metrics history (CSV, end-of-run trend) | Done - `metrics.csv` per generation, `diag.Graph` at end of run |
| PPO clip fraction (`clip=`) | Fixed - was reporting `P(\|N(0,1)\|>0.2)`=0.8415; now the real ratio-clip fraction, measured 0.047-0.737 |
| Reward model, per-term ledger, curriculum fade | Partial - 6 terms have no emission site; `KILL` has the plumbing but no emission; curriculum is written but `observe()` is never called, so shaping is a constant 1f |
| Episodes can be had at all | Done - `REST` was a one-way door (0 idle-guard stalls, 36 pipeline stalls), headless had no texture and every `TextureFilm` dereferenced it on hunger damage, and every death blew the stack so `DEATH` was unreachable. `rewardcheck` cases 7 and 8. |
| Terminal rewards are honest | Done - `STALLED` was -5.0 against death's -100, so ending an episode beat losing and the agent learned to. Now zero, priced by turn cost. `rewardcheck` is a gate |
| Actions report what happened | Done - `WAIT`/`REST`/`SEARCH` were recorded as `INVALID_ACTION`, and the term carried no weight, so nothing observed it |
| CNN + LSTM network, PPO agent | Verified; **PPO now runs on real worker data** |
| Weight save / load, resume a run | Done - `--save` / `--resume` / `--checkpoint-every`, atomic writes, Adam moments and step count included. `checkpointcheck` is a gate |
| Bad checkpoint handling | Done - foreign, truncated, trailing-byte and wrong-config files all refused, the last naming the field |
| Parallel minibatch update | Done - `--update-threads N`, measured 3.29x at 4 threads, 3.47x at 8. `parallelcheck` is a gate |
| Cross-sample gradient leak | Fixed - `dCell` was not cleared, so every gradient depended on the previous sample |
| Value head range | Fixed - was tanh-bounded to [-1,+1] against a death penalty of 100 |
| LSTM state with each transition | Fixed - the replay ran under an unrelated sample's state |
| Trained anything yet | **No.** Never left floor 1; milestone is depth 5 (`research.md:40`) |
| Parallel worker processes, seed schedule | Running; exercised to 8 workers x 25 generations |
| Graphical trainer UI, desktop replay viewer | Replay viewer done; trainer UI not started |
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

**The update is parallelisable and now is.** 11.3 ms per sample single-threaded, almost all of it
forward and backward rather than Adam. `--update-threads N` splits a minibatch across N threads, each
with its own scratch and its own gradient accumulators, reduced before the Adam step:

```sh
./gradlew :superintelligence:train --args="--workers 8 --update-threads 4 --generations 200"
```

Measured 8.98 -> 5.09 -> 2.73 -> 2.59 ms/sample at 1, 2, 4 and 8 threads. The curve flattens past 4
because the reduction is 14.3 MB per thread per minibatch, which costs about what the extra compute
saves. `parallelcheck` asserts the sharded gradient equals the serial one.

The default is **2 epochs**, which also corrects a misconfiguration: 4 epochs is 300 Adam steps over a
2,400-sample batch. Transport is 0.12 s against ~7 s of collection — never the bottleneck.

Sampling and metrics are tunable, and the three sampling knobs were previously `Trainer` fields with
no way to reach them from the command line:

```sh
./gradlew :superintelligence:train --args="--sample-rate 0.05 --max-samples 8192 --metrics run.csv"
```

**A run can be stopped and continued.** `--save` writes a checkpoint every `--checkpoint-every`
generations (default 25) and on exit; `--resume` continues from one, keeping the generation and
optimiser-step numbering so the two runs' `metrics.csv` rows do not collide. Note that the *seeds*
restart from `--seed` — the policy continues, the schedule does not.

```sh
# 50 generations, then 50 more
./gradlew :superintelligence:train --args="--generations 50"
./gradlew :superintelligence:train --args="--generations 50 --resume %TEMP%\spd-train\weights.bin"
```

`gradle :superintelligence:weightsdiff --args="<file>"` reports how far a checkpoint is from a freshly
initialised policy — mean and max absolute weight difference. `checkpointcheck` asserts that a policy
survives disk exactly, Adam moments included, and that bad checkpoints are refused.

## Layout

| Package | Responsibility |
| --- | --- |
| `headless` | libGDX/noosa shims: silent audio, classpath file resolution, inert `Game`, headless `Graphics`, sprite that resolves animations instantly |
| `env` | `SPDEnv` (reset/step), `ActionMapper` (action injection and masking), `LevelPipeline` (run start, floor transitions, actor scheduling), `WindowBridge`, `SlotAction` |
| `obs` | `ObservationEncoder`, spatial channel definitions, fixed inventory vector, hero scalars |
| `reward` | `RewardModel` (state diffing), `RewardTerm`/`RewardLedger` (per-term, per-floor breakdown), `Curriculum` |
| `policy` | `ScriptedPolicy`, the network-free heuristic used for smoke tests and worker bootstrap |
| `rl` | `Network` (CNN + LSTM + heads), `PPO` (buffer + update), `Policy` (masking, losses, GAE), `Transition`, `EpisodeCollector` (plays an episode, computes its advantages), `EpisodeRecord` (one episode's scalars + sampled observations) |
| `train` | `Trainer` (generation loop), `WorkerPool` (processes, pipes, stall watchdog), `Protocol` (wire format), `TransitionCodec`, `TrainOptions`, `Checkpoint` (save/resume), `MetricsHistory` (per-generation CSV + end-of-run trend), `Episode`, `Worker` (worker side), `SeedPool` (generalisation schedule) |
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
