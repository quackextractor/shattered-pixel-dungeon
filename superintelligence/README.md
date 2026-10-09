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
  the recovery path that used to exist (no method of that name is in the tree any more) refused a
  resting hero, correctly, because a *player* escapes rest by choosing another action - headless input
  has nobody to do that. Instrumenting the two stall guards settled it:
  36 of 36 stalls came from `LevelPipeline` and **zero** from the idle guard, at turns 10-51 rather than
  121. Any non-`REST` action now ends the rest. Stall share is now **0%**.
- **A second recording in one process was unreproducible.** `GameScene.pendingCellListener` is static,
  and `reset` never cleared it, so an item the previous episode left mid-aim was still armed when the
  next episode's first `settle()` ran - which reports `TARGETING` before any turn has been taken and
  turns every action into a target choice. 4 of 10 recorded runs diverged when verified in sequence and
  every one of them verified clean alone. New `resetcheck` gate.
- **Two further faults were hidden behind those**, unreachable while episodes ended at turn ~50. Headless
  has no GL context and every `TextureFilm` constructor dereferenced the null texture, so hunger damage -
  which touches a statically-initialised film - killed worker processes. And `HeadlessSprite.die`
  invoked the death callback to fake an animation, recursing `Hero.die` -> `Char.die` -> `sprite.die`
  until the stack gave out, which means `DEATH`, the ending the whole reward function is built around,
  was unreachable. See [`PLAN-reward-signals.md`](PLAN-reward-signals.md) sections 7-8.
- **`OPEN_INVENTORY` also acted on the world.** It was missing from `ActionMapper.apply`'s switch, so it
  fell through to `pickInteractCell()` + `handleCell()` - the path `INTERACT` takes. Opening the
  inventory therefore also moved the hero, attacked a mob, picked up a heap or took a transition. It was
  documented in place as a no-op, which is how it survived. **This reached training**, and it is why
  recordings made before the fix no longer reproduce. Section 10.
- **Nothing has reached depth 2.** An earlier version of this file claimed it had, on the strength of a
  trend line reading `depth 1.0..2.0`. That `2.0` was not measured: `Graph.bar` widens a flat series'
  scale so its division is defined, then prints the widened span as the range. `bestDepth` is 1 in every
  generation of every run, and all 147 recordings are `depth=1`. `graphcheck` now gates the axis.
- **What remains is neither a crash nor a stall.** Every episode runs the full 1500 turns with zero
  stalls, and `meanScore` is falling (186 -> 59 across 6 generations) as the critic fits longer episodes.
  But `bestDepth` is still 1 and nothing dies. `depthReward` of +10 against `turnCost` of 0.002 means
  descending pays 5,000 turns of idling, and `KILL` / `GOLD_GAIN` / `ITEM_PICKUP` still never fire
  (`TODO.md` 1.8).
- **The desktop viewer and the trainer now agree.** They did not, and it was not a viewer fault:
  four presentation draws — a sprite's random facing, the attack overlay's highlight target, and two
  Mages Staff particle effects — were spending the gameplay RNG stream, which only the rendered game
  makes. The viewer's stream ran twelve values from the trainer's, so every damage and defence roll
  after the first differed and recordings that verified exactly headlessly diverged in the viewer:
  **14 of 17**, deterministically. Worse, `RngTrace` reported identical draw counts at every step
  throughout, because `Random.Int` and `Random.shuffle` were advancing the generator without being
  counted — so the instrument that was supposed to catch it was blind to the draws that caused it.
`gradle :desktop:viewcheck` is now **green on all 17**, and stable across repeated runs. The four
   fixes are engine changes under `instructions.md` §12.3; the proposal record is
   `ENGINE-CHANGES.md` and they are committed. See `ISSUE-viewer-frame-drift.md`.

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
| Replay record / re-verify | Verified exact - 4/4 fresh processes, identical score, and a 32-recording sweep in one process |
| Rendered viewer reproduces a recording | **Verified** - `:desktop:viewcheck` is green on all 17 committed recordings, and stable across repeated runs. The four engine fixes this required are committed, with the record in `ENGINE-CHANGES.md` |
| Configuration externalised | Done - properties file plus `SPD_*` environment variables, flags overriding both; `configcheck` is a gate |
| A run inherits nothing from the previous one | Done - `RunState` clears the armed aim, the open dialog, the pending item and a dead hero's remains, before level generation |
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

# record across many seeds in one process, then prove each recording replays exactly
./gradlew :superintelligence:paritycheck
./gradlew :superintelligence:paritycheck -PparityArgs="--seeds 8 --heroes 4 --turns 1500"

# check the two advantage implementations agree and sampling behaves
./gradlew :superintelligence:gaecheck

# measure what a PPO update costs, and project it across sample rates
./gradlew :superintelligence:updatecost

# every correctness gate, one invocation, ~22 seconds
./gradlew :superintelligence:gates

# train

./gradlew :superintelligence:train --args="--workers 8 --generations 200"
```

**Nineteen gates, one invocation.** Each has had the thing it guards deleted, and each has been
required to fail - a check that cannot fail is not a check. Two reach faults the rest structurally
cannot: `resetcheck` proves a reset is a function of its arguments, which needs two episodes in one
process because with one there is nothing to leak from; and `paritycheck` proves a *recording* survives
a write, a read and a second episode, which is the only gate that lets the hero die repeatedly. That is
where process-spanning game state such as a dead hero's remains becomes visible - and it is how two of
32 recordings were caught not reproducing. Full table in
[`testing-guide.md`](testing-guide.md).

`probeClasspath` prints the runtime classpath, which is what the trainer uses to launch workers.

**Two-sided diffing, as one command.** `worldtrace` produces the headless side of a world snapshot and
the viewer's `-Dspd.worldTrace` produces the rendered side; `viewdiff` reads both and names the first
step and field that differ, plus the frames on which the world moved with no recorded step applied:

```sh
./gradlew :superintelligence:worldtrace -PworldArgs="<replay> <out> --no-cells"
./gradlew :desktop:replay --args="--file <replay>" -PspdWorldTrace=<out>.viewer.txt
./gradlew :superintelligence:viewdiff -PviewDiffArgs="<out> <out>.viewer.txt"
```

This is the only comparison in the suite where the two sides do not share a code path, which is why
it found a fault that `verify`, `playbackcheck` and `paritycheck` all agreed was not there.

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
| `env` | `SPDEnv` (reset/step), `ActionMapper` (action injection and masking), `LevelPipeline` (run start, floor transitions, actor scheduling), `RunState` (what a new run must not inherit), `EnvConfigBinder` (settings from file and environment), `WindowBridge`, `SlotAction` |
| `obs` | `ObservationEncoder`, spatial channel definitions, fixed inventory vector, hero scalars |
| `reward` | `RewardModel` (state diffing), `RewardTerm`/`RewardLedger` (per-term, per-floor breakdown), `Curriculum` |
| `policy` | `ScriptedPolicy`, the network-free heuristic used for smoke tests and worker bootstrap |
| `rl` | `Network` (CNN + LSTM + heads), `PPO` (the learner), `ShardedUpdate` (the parallel minibatch and its gradient reduction), `Policy` (masking, losses, GAE), `Transition`, `EpisodeCollector` (plays an episode, computes its advantages), `EpisodeRecord` (one episode's scalars + sampled observations) |
| `train` | `Trainer` (generation loop), `TrainerWorkers` (everything crossing a worker pipe), `WorkerPool` (processes, pipes, stall watchdog), `Protocol` (wire format), `TransitionCodec`, `TrainOptions`, `PpoHyperparameters`, `Checkpoint` (save/resume), `MetricsHistory` (per-generation CSV + end-of-run trend), `Episode`, `Worker` (worker side), `SeedPool` (generalisation schedule) |
| `replay` | `Replay`, `ReplayRecorder`, `ReplayIO` (write, read, verify), `ReplayCatalog`, `RngTrace`, `WorldSnapshot`, `WorldDiff` |
| `diag` | `RunReport`, `Graph`, `Ansi`, and the checks: `GradientCheck`, `ModeCoverageCheck`, `RestartCheck`, `GaeCheck`, `UpdateCostCheck`, `ConfigCheck`, `ParityCheck` |

## Configuration

Settings come from four places. Lowest priority first:

| Source | How |
| --- | --- |
| Compiled defaults | `EnvConfig` and `PpoHyperparameters` |
| Properties file | `--config <path>` |
| Environment | `SPD_*` - `rl.learning_rate` is `SPD_RL_LEARNING_RATE` |
| Command-line flags | `--max-turns`, `--workers`, ... - these win over everything |

```sh
# tune from a file
./gradlew :superintelligence:train --args="--config my-run.properties"

# or from the environment, which is how a container or a .env does it
SPD_RL_LEARNING_RATE=0.001 SPD_RL_SAMPLE_RATE=0.1 ./gradlew :superintelligence:train
```

[`superintelligence.properties`](superintelligence.properties) documents all 35 keys against their
reasons, and its values are the compiled defaults - so a run with no `--config` and no `SPD_*` variables
behaves exactly as it did before the file existed.

Two rules, both because the alternative is silent: a value that cannot be read is **refused** rather
than defaulted, and an unknown key **warns** naming itself and its file. A confidently-wrong
configuration is worse than one that declines to load.

`gradle :superintelligence:configcheck` gates all of it: that a value in a file really changes the
setting it names (the positive control, without which the rest would pass on a binder that reads
nothing), that every documented key is reachable, that the shipped file matches the compiled defaults
in both directions, and that malformed values are refused with the key and file named.

The learning values live in one place, `PpoHyperparameters`. They used to be duplicated - `learningRate`
on both `Network` and `PPO`, `epochs` and `minibatchSize` on `PPO` and again on `TrainOptions` - and two
of those copies are load-bearing: Adam's step size must agree between the network that steps and the one
that reports it, and the per-episode sample cap must agree between the trainer that requests it and the
worker that applies it. `PPO` keeps its public fields, so the update path is untouched.

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
- `Bones.clear()` - forgets a fallen hero's remains. Nothing in the game calls it; a normal playthrough
  wants the opposite. It exists because an environment playing many independent runs in one process
  cannot have the world depend on which hero died last.
- `Random.reseedBase(long)` - seeds the base generator in place, so a recording can be replayed in a
  second process.
- `RandomTrace`, `Actor.currentActor()`, `Mob.currentEnemy()/enemySeen()/alerted()`,
  `MovieClip.animationInFlight()`, `GameScene.answerableWindow()`, `PRandom.element()` - getters and
  observation-only additions.
- `Actor.all`, `Actor.chars`, `Level.mobs`, `Level.blobs` and the tie-breaks that consume them are
  insertion-ordered, so turn order is a function of the run rather than of identity hash codes.
- `CharSprite.link`, `AttackIndicator.checkEnemies`, `Wand.staffFx` and
  `MagesStaff.StaffParticle.update` draw from `PRandom`. These are presentation draws that only the
  rendered game makes, and they were spending the gameplay RNG stream.

The last one is not cosmetic bookkeeping. **Four presentation draws on the gameplay stream cost the
viewer 16 generator values per run that a headless run never spent**, which is why a recording could
verify exactly headlessly and diverge in the viewer. See `ENGINE-CHANGES.md` for the full inventory,
what each change cost, and what is still unfixed.
