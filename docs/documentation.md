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
- [`superintelligence/CHANGELOG.md`](../superintelligence/CHANGELOG.md) - the module's own history and
  its own version line
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

**Nothing has won a run, and nothing has reached floor 2.** The machinery is correct - the network's
gradients check against central differences, the environment is reproducible across processes,
recordings replay exactly, and every one of the thirty-three gates is green - and the reward function
asks for the right thing. **The 30-generation measurement is done, and it is the first run whose
movement clears its own error bar without producing a descent:** `meanScore` moved **+134.49 +/- 52.4**
between the first and last fifteen generations, about 2.6 sigma, where the run it replaces moved +1.6
against a per-generation standard deviation of 98 to 138. **The objective did not move with it.**
`meanObjectiveScore` moved +65.69 +/- 57.9, which is not significant, and `depth>=2` is **0 of 960
episodes** with `bestDepth` 1 in 30 of 30 generations.

That distinction is the result worth reading. The policy got better at what the shaping pays for -
kills, pickups, a fall in `STALLED` - and none of that is a descent, which is why the generation
report now carries an **objective-only score** beside the score and a `depth>=2` share of
**episodes** rather than generations. A change has to clear **~100 points over 15 generations** - not
the +/-16 the repository had been quoting, which was measured on a different configuration. The
milestone is beating the first boss at depth 5 (`research.md:40`).

## Versioning

**Two version lines, and they are not comparable.** This matters because a reader who sees one badge
will otherwise assume the other means the same thing.

| Subject | Version lives in | Moves when |
| --- | --- | --- |
| Shattered Pixel Dungeon | upstream, and the `version-` badge in the root [`README.md`](../README.md) | this fork merges a new upstream tag |
| The Superintelligence module | [`superintelligence/CHANGELOG.md`](../superintelligence/CHANGELOG.md), and the `version-` badge in [`superintelligence/README.md`](../superintelligence/README.md) | work lands in this module |

**The module's series starts at 0** — it is at `0.4.0` while the game is at `4.0.2`. This is deliberate.
The module's history began at `4.1.0`, which put it in the same numeric neighbourhood as the game it
wraps, and two badges reading `4.x` and `4.0.x` in one readme invite a reader to treat them as one
line with a fork applied. Nothing about a reinforcement-learning harness is comparable to a game
release, so a shared-looking number is a false affinity. Every version moved by exactly −4.0, which is
how the renumbering was checked against a per-release major/minor/patch classification rather than
applied as an offset and hoped for; that classification is in `0.4.0`'s entry.

**Pre-1.0 is a statement about stability, not about the digits.** Under
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) a `0.x.y` release carries no compatibility
promise, so a minor bump here may still change behaviour a caller depended on — and one did: `0.3.0`
re-priced `STALLED` from zero to the death penalty and invalidated the recording corpus. What the `0.x`
line prevents is `major` happening by accident.

The split is not cosmetic in the first row. **The game's version is upstream's**, and this fork does
not release the game: its changes to `:core`, `:SPD-classes` and `:desktop` are the additive, guarded
ones listed under *Changes to the game* below. A repository-wide changelog claiming game releases would
be a second thing to be wrong about, on top of the version numbers themselves.

Consequently the root `README.md` carries both badges, and they are checked against two different
sources by `hooks/pre-commit`:

- the game's `version-` badge against the newest `v*` git tag, which *is* the game's version — so it
  follows an upstream merge without anyone editing it, and cannot drift from it;
- the module's badges — `superintelligence-` in the root readme, `version-` in its own — against the
  newest released heading in `superintelligence/CHANGELOG.md`.

Both are corrected and re-staged rather than refused. Before the split the hook read one changelog and
one badge, and left as it was it would have compared the game's badge against the module's changelog
and rewritten the game's readme with a module version — a wrong answer, committed by a check that
reported success.

## Quick start

```sh
# one run, headless, with a report
./gradlew :superintelligence:rollout --args="--seed PROBESEED --max-turns 400"

# record it, then prove the recording still reproduces
./gradlew :superintelligence:rollout --args="--seed PROBESEED --save run.replay"
./gradlew :superintelligence:verify --args="run.replay"

# regenerate the DROP-in-SLOT fixture the viewer gates play; it verifies before writing
./gradlew :superintelligence:dropfixture --args="dropfixture --save test-replays/duelist-drop.replay"

# regenerate the throw-that-presses fixture, the same way; it also refuses to write a run whose
# throw did not press anything
./gradlew :superintelligence:pressfixture --args="pressfixture --save test-replays/duelist-throw.replay"

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
| `rl` | `Network` (CNN + LSTM + heads, forward and backward), `NetworkWeights` (its parameters, Adam, gradients, checkpoint layers and moments), `PPO` (the learner), `ShardedUpdate` (the parallel minibatch and its gradient reduction), `Policy` (masking, losses, GAE), `Transition`, `EpisodeCollector`, `EpisodeRecord` |
| `train` | `Trainer` (generation loop), `TrainerWorkers` (everything crossing a worker pipe), `WorkerPool`, `Protocol`, `TransitionCodec`, `TrainOptions`, `PpoHyperparameters`, `Checkpoint`, `MetricsHistory`, `SeedPool` |
| `replay` | `Replay`, `ReplayRecorder`, `ReplayIO` (write, read, verify), `Quickslots` (the one place a
           recorded slot index is resolved against its bindings), `DropFixture`, `PressFixture`, `ReplayCatalog`, `RngTrace` |
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

Settings come from five places. Lowest priority first:

| Source | How |
| --- | --- |
| Compiled defaults | `EnvConfig` and `PpoHyperparameters` |
| Properties file | `--config <path>`; the `train` task passes `superintelligence/superintelligence.properties` when none is named |
| `.env` | `superintelligence/.env`, the same `SPD_*` names |
| Environment | `SPD_*` - `rl.learning_rate` is `SPD_RL_LEARNING_RATE` |
| Command-line flags | `--max-turns`, `--workers`, ... - these win over everything |

The shipped file's values are the compiled defaults, so reading it by default changes nothing until it
is edited. The trainer JVM's own heap (`worker.heap_min`, `worker.heap_max`, `worker.gc_pause_ms`)
resolves through the same tiers in `build.gradle`.

Two rules the layer holds to, both of which exist because the alternative is silent:

- **A value that cannot be read is refused, not defaulted.** Handing back a configuration nobody asked
  for is worse than declining to load, in a system whose whole value is that its settings are the ones
  it reports.
- **An unknown key warns, naming itself and its file.** A misspelled key tunes nothing, and an operator
  who mistyped one has no other way to find out. That includes an unknown `SPD_` name in `.env`, and a
  refused `.env` value names its `.env` entry rather than the properties file.

`superintelligence.properties` ships with every key documented against its reason, and `configcheck`
asserts it agrees with the compiled defaults in both directions - so a key cannot drift away from the
code that reads it unnoticed. `.env.example` beside it in `superintelligence/` is the environment-variable
half of the same thing, with each `SPD_*` name spelled out and the reward keys annotated for what they
buy; `.env` itself is git-ignored, and a run with neither file uses the compiled defaults.

### The reward, and the one ratio that decides it

Reward is measured by diffing observable state across a turn rather than by hooking the item tree
(see `superintelligence/REJECTED.md` for why, and what that costs). Two numbers carry most of the
objective:

| Key | Default | What it decides |
| --- | --- | --- |
| `reward.profile` | `default` | `default` pays every term. `depth_only` pays **only a descent, a win, and the four prices for stopping** - see below |
| `reward.depth_reward` | `1800.0` | What one floor deeper is worth |
| `reward.explore_reward` | `0.05` | What a newly-seen tile is worth |
| `reward.visit_reward` | `1.5` | First time the hero stands on a cell, per floor (episodic count bonus) |
| `rl.teacher_weight` | `1.0` | Imitation of `policy.DescentBot` on the student's own states; 0 turns it off |
| `rl.teacher_half_life` | `10.0` | Generations over which the imitation weight halves |
| `rl.demo_weight` | `1.0` | Imitation weight on `--demos` recordings (written by `goexplore`) |
| `reward.turn_cost` | `1.0` | What an engine turn costs |
| `reward.stall_penalty` | `3000.0` | What giving up costs. **Worse than dying** |
| `reward.death_penalty` | `2500.0` | What dying costs |
| `reward.turn_limit_penalty` | `2000.0` | What running out of a floor costs |
| `reward.no_progress_penalty` | `12.0` | A step that advanced no engine time |
| `env.free_step_budget` | `120` | Steps on a floor that may advance no engine time before the episode is called stalled |
| `reward.kill_reward` | `150.0` | Per mob killed, from `Statistics.enemiesSlain` |
| `reward.sneak_attack_reward` | `300.0` | Per surprise attack, from `Statistics.sneakAttacks` |
| `reward.talent_reward` | `400.0` | Per talent point spent, from `Hero.talentPointsSpent`. **Nothing can spend one yet** - see below |
| `reward.damage_penalty` | `40.0` | Per HP lost. A full floor-1 bar costs 80% of a death |

**`reward.profile=depth_only` switches the function down to the objective**, which is a temporary
override rather than a re-pricing: no weight changes, terms are silenced at the ledger, and `default`
restores everything. Under it the **only route to a positive score is reaching a new floor or winning**,
and the only negatives left are the per-turn cost, the charge for a step the engine resolved for free,
running out of turns, dying and stalling.

It exists because a function that also pays for exploration cannot be used to *measure* exploration:
under `default` a run can post a good score without ever leaving floor 1, which is indistinguishable from
a run that learned to descend. Every silenced term is still counted, so a run report shows the events
that paid nothing. The profile travels to the workers over the trainer's params frame and is recorded in
the replay header, so a run's score and a recording's score cannot disagree about what priced them.
`rewardcheck` asserts it in both directions.

**`depth_reward` against `explore_reward` is the objective.** A regular floor holds several hundred
walkable tiles (`CHANGELOG.md` measured 419 of 1040 sampled floors exceeding 48 on at least one side,
up to 81), so exploration is a large number by the time a floor is finished. At the ratio this project
shipped for most of its life - 10 against 0.05 - mapping a floor without ever taking the stairs paid
*more* than descending, and every run learned to wander. Which is what `bestDepth` being 1 in every
generation of every run had been measuring.

The terminal outcomes are ordered, and the order is the property rather than the values: giving up is
worse than dying, dying is worse than running out of a floor, and running out of a floor is worse than
clearing one. `rewardcheck` asserts the ordering, not the constants, so it survives them being retuned.

**A stall is bounded twice, and the second bound exists because the first one has a hole in it.**
`env.stall_limit` counts consecutive steps where the hero's cell and health are unchanged - so **one move
resets it**, which is what a free-action loop does. Measured on a trainer recording: 502 of 1215 steps
advanced no engine time, 41% of that episode's cost, almost all of it `OPEN_INVENTORY` then `CANCEL` out of
`INVENTORY` with a move between pairs, and the position guard never reached its hundred.
`env.free_step_budget` counts those steps as a total on the floor, and reaching it ends the episode as a
stall - so the bound costs more than dying, which is the ordering above.

**The surprise attack is the largest combat payout on purpose.** A kill is paid for damage dealt, which
depends on the weapon and on rolls. A surprise attack is paid for arriving unseen - `Mob.surprisedBy`
skips the defender's defence skill outright, so the same hit lands for far more - and it is the only
combat outcome that costs the hero nothing. Paying the kill alone teaches trading blows with whatever is
in reach, and trading blows is what the recorded deaths look like: the hero does not lose a fight, he
runs out of room.

### One reward is live that nothing can earn yet

`reward.talent_reward` pays 20 for a talent point, the second-largest payout in the function. **The hero
levels up — `Mob.destroy` calls `Hero.earnExp`, which raises level, attack and defence — and can never
spend the point.** `Hero.upgradeTalent` is public and works; the only path to it in the game is
`TalentButton` → `TalentsPane` → `WndHero`, none of which exists headlessly, and `WindowBridge` answers
only `WndOptions`.

It is not an endgame gap: `Talent.tierLevelThresholds` is `{0, 2, 7, 13, 21, 31}`, so the **first point
arrives at level 2** — two rats on floor 1. And it is the largest combat lever in the game, because
`hasTalent(...)` is false everywhere until a point is spent, and it is threaded through the accuracy,
defence and speed rolls.

The reward is wired and gated anyway, so that the action has something to pay when it lands. The gate
drives it by spending points through the engine directly — the same technique the death case uses to
produce a death without waiting for the mob cluster — which is what distinguishes "wired and proven"
from `KILL`'s "declared, plumbed and never emitted". `TODO.md` §2 is the missing action.

## Gates

thirty-three gates, one invocation:

```sh
./gradlew :superintelligence:gates
```

| Gate | What it refuses to let happen |
| --- | --- |
| `gradcheck` | An analytic gradient that disagrees with a central difference |
| `gaecheck` | GAE that disagrees with the brute-force discounted sum, **or** one update that moves p(chosen) the wrong way for the advantage's sign, **or** a PPO clip that stops the side its ratio has not passed |
| `collectcheck` | A recording the collector cannot replay, **or** a rebinding step recorded against the world the step produced |
| `checkpointcheck` | A policy that does not survive disk, or a bad checkpoint that loads |
| `statecheck` | A transition from a real collected episode that does not replay to its rollout's value under the recurrent state it was decided under, **or** a target-head step that records an index other than the one sent |
| `parallelcheck` | A real `PPO.update` at 2 or 4 threads that differs from 1 thread in parameters or metrics, **or** a shard that does not own its own `Network`, **or** a throwing shard that hangs the update |
| `rewardcheck` | Ending an episode being cheaper than dying, **or** an action that consumes several engine turns being charged for one, **or** `depth_only` paying anything outside the objective while `default` stops paying a term, **or** a reward term with no type, or type subtotals that do not reconstruct the score |
| `transitioncheck` | A floor change the environment does not service, or an agent's first action on the new floor being swallowed |
| `fallcheck` | A chasm fall the environment drops, a free landing, or a fall that leaves a hand-off behind for the next episode |
| dialogcheck | An answer that cannot reach a dialog the game opened, a dialog taken away when the game declined it, or one reported a turn late |
| `presscheck` | A dropped item that does not fire the trap or flatten the grass it landed on, **or** level generation pressing a cell it is still building |
| `mobspritecheck` | An item that creates a mob leaving it with no sprite, or outside `Actor.chars`, **or** the stand-in factory being reachable with the headless flag off, **or** a mob that casts its sprite getting a stand-in it cannot be cast to, **or** a mob added with a delay acting before it |
| `replaycheck` | A catalog that misgroups, misranks or misresolves, **or** mislabels what it did |
| `rolloutcheck` | A recording with no usable seed |
| `modecheck` | An action mode the environment cannot reach |
| `restartcheck` | A restart that does not rebuild the same first floor, through a copy of the viewer's restart sequence |
| `resetcheck` | A reset that does not isolate an episode from whatever ran before it |
| `graphcheck` | A chart reporting a range it did not observe |
| `actioncheck` | A movement action that does not move the way it is named, **or** a throw charged differently from `Item.cast` |
| `slotcheck` | A recorded slot index resolving to a different item, or a restore wiping a binding no index can name |
| `verifycheck` | Verification that cannot detect an altered recording, **or** verification that certifies one that cannot be played |
| `observecheck` | Reading the world drawing from the gameplay RNG, **or** a draw path the RNG trace cannot see, **or** a public static `Random` method the audit has never seen |
| `configcheck` | A configuration binder that ignores its input, **or** an example file that names a different value from the compiled default |
| `paritycheck` | A recording that will not replay exactly, across seeds in one process |
| `freeactioncheck` | A rest that spends no engine time, or a menu loop that can be repeated with the world standing still |
| `bpttcheck` | A recurrent gradient that never crosses a timestep, or a critic asked for returns in the thousands |
| `curriculumcheck` | A shaping fade that is written and wired but inert, or a `shaping=` column that reports a constant |
| `samplercheck` | Sampling that shatters the recurrent chunk, or a grid flattened straight into the dense trunk |
| `seedcheck` | A resumed run that replays generation 0's seed sequence from the top |
| `doccheck` | Documentation that is unreadable as text, a gate that is not reachable, a gate count that disagrees with the list, **or** a Java path in a document that names a file which is not there |
| `stallcausecheck` | A stall guard that is unreachable or that reports the wrong guard |
| `pushcheck` | A policy push written in one call rather than chunked, a failed write retried, or one not refused by name |
| `javaclint` | Any compiler warning at all, under `-Xlint:all -Werror` |

Plus `playbackcheck` in `:desktop`, which drives the rendered viewer with no window and no scene, and
`viewcheck`, which plays the whole corpus through the real viewer and needs a display.

`presscheck` was deliberately outside that list while its divergence was open — a thrown item did not
trigger the trap it landed on, because `Level.drop` pressed the cell only behind a
`scene() instanceof GameScene` test no headless run could satisfy. It is fixed and in `gates`;
`CHANGELOG.md` has the measurement, and `duelist-throw.replay` is the recording that carries a recorded
press through both environments.

`playbackcheck` also plays **the committed corpus** headlessly, which is the headless half of
`viewcheck` and the only one of the two that can be a gate. That half was added because every other
case there builds its own fixture, and a freshly recorded run does not end on a rest - which is how a
real viewer-versus-trainer divergence went unnoticed (see below).

Every gate is mutation-tested: each one has had the thing it guards deleted, and the gate has been
required to fail. A check that cannot fail is not a check, and several of these exist only because the
property they protect had already been broken.

**A gate that passes because the thing it would catch is absent is the same failure**, and that one has
a measured instance. `viewcheck` was green on seventeen recordings while `verify` was green on nine the
viewer refused to play at step 1, and the reason both were correct about their own question is that the
corpus contained no `DROP` in `SLOT` at all: 310 SLOT-mode steps, every one `USE/SLOT`, because
`ScriptedPolicy` never chooses `DROP`. `duelist-drop.replay` closes it, regenerated by

```sh
./gradlew :superintelligence:dropfixture --args="dropfixture --save test-replays/duelist-drop.replay"
```

which **verifies the recording before writing it**. Both `viewcheck` and `playbackcheck` read that
directory already, so the fix cost one file rather than a second corpus.

Closing the wire is not the same as showing it, though, and the next thing this turned up was that
`trainer-replays/` was **10 of 10 unplayable** - every file in it predates the fix, so picking anything
from that section of the catalog ended in a halt. `APR-SLJ-DXM` was checked in beside them (1514
steps, 245 slot-bearing of which 49 are `DROP`), and the catalog labels each recording rather than the
section:

```
GFW-GHG-BTT               -116.2  d1   1,260   not watchable, gen 3
APR-SLJ-DXM                -80.6  d1   1,500   gen 1
```

The nine cannot be repaired: they carry `hp=-1 turn=-1.0 inventory=""` on every step, so the data was
never written and only a new run records it. `trainer-replays/` is now **empty and untracked** - it is
gitignored and a training run writes there by default - so what the wire dropped is recorded here, in
`CHANGELOG.md` and in `CHANGELOG.md` 0.7.0 rather than only in the files.

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
- **`observecheck` now audits `Random` itself**, not only the paths it knows. It reflects over
  `Random.class.getDeclaredMethods()` and fails naming any public static method outside a 36-name
  audited set. **The first run found 13 the gate had never covered**, which is the entire argument for
  it: enumerating what the gate knows is an invariant about the present, and the four presentation
  draws that spent the gameplay stream while every count agreed got through exactly that hole.
- **`transitioncheck`** looks at what the hero *did* on arrival rather than only at where he landed. A
  hero arrives on a new floor where everything is at time 0, so the game's own scheduler gives him
  exactly one act before the player can act at all - and `Hero.act()` is where the floor's sleeping mobs
  notice him. The environment used to hand control to the agent first, so the agent's first action on
  every new floor was spent being interrupted by a rat, and the recording recorded a turn that moved
  nobody. A recording cannot catch that: a step whose action was thrown away still records a step. The
  case that does has to end with a real action and assert the hero moved, which in turn needs a fixture
  where something is in range to interrupt - so the fixture is searched for at run time rather than
  hard-coded, and failing to find one is a reported failure. The gate also drives the ascent, which is
  the one direction no other gate exercised.
- **`fallcheck`** covers the floor change that carries no `LevelTransition`. A chasm fall is a
  different shape from a stair transition and nothing reached it: `Chasm.heroFall` writes
  `InterlevelScene.mode` and asks for a scene switch, and `LevelPipeline.handleTransition` tested
  `curTransition == null` first - which is true on *every* fall, because a fall is the one transition
  that has none. So it answered "not a level transition", cleared the switch request and dropped the
  fall, and `Transition.FALL` and `handleFall()` were unreachable. The gate drives a real fall through
  `Level.occupyCell`, asserts the landing is a floor with a living hero on a restarted clock and a
  health bill, and asserts the hand-off leaves nothing behind for the next episode - which matters
  because `InterlevelScene.mode` is a process static that a real game resets by building a fresh
  `InterlevelScene` for every descent, and nothing headless does.
- **`dialogcheck`** covers the other half of the chasm: the agent could not *choose* to fall. Walking
  at a chasm raises the game's own "jump?" prompt, and the environment was handed it and then had the
  answer thrown away — the dialog gates its option and its hide on `elapsed > 0.2f`, a counter its own
  `update()` increments and `PixelScene.update()` is the only thing that ever called, so headlessly the
  option could not fire; and `WindowBridge.selectOption` cleared the window on any press, so a handler
  that declined had its dialog removed anyway. `WindowBridge.age()` advances it once per environment
  step, which is once per frame — the same approximation `ReplayPlayer` makes about a recorded step —
  and a parked window now lives in a `Group`, so the game's own `hide()` closes it. A rendered game
  blocks cell input the instant the window appears, and `settle` likewise reports a dialog opened
  *during* the drain rather than a turn later. Measured on `DIALOGCHECK-A`: the answer takes 13 steps, and
  **one crossing costs -15.00** — the 12 refused steps are free, but the accepting step resumes the
  hero's interrupted move and that turn is scored, against a descent at `+1800.00`.
- **`presscheck`** covers the third fault on that path, which is not about chasms at all. `Level.drop`
  presses the cell a thrown or dropped item lands on - which is how a stone sets off a trap or flattens
  grass - and it did so only behind `scene() instanceof GameScene`, a test a renderer-bypassed process
  can never satisfy because it has no scene at all. Every throw was inert: an armed `WornDartTrap` was
  still armed after a `Food` was dropped on it on four seeds out of four. `Level.drop` is the one place
  that needed changing - `Item.onThrow` and `Item.doDrop` both call it - and the scene test survives,
  because the two drops it exists to exclude (`AlchemyScene` crafting onto the cell the hero stands on,
  `InterlevelScene.resurrect` onto the cell he died on) both need a renderer and cannot exist headlessly.
  The gate asserts both halves: a drop fires a trap and flattens grass, and level generation - which runs
  with no current level - still presses nothing.

## Replay and determinism

A replay records decisions, not consequences. Playback re-applies them through the same `ActionMapper`
a live run uses and compares, after every step, against what the recording carries: hero position, HP,
engine time and inventory.

**It compares against the recording, not against a second run.** Re-executing a replay through the same
headless code only proves the engine is deterministic - both sides share every headless-specific fault.
That is not hypothetical: a run whose hunger clock was frozen by the `intro` setting replayed perfectly,
identical position at all 1500 steps, while the hero was starving in the real game.

Verify also diverges when a step is replayed in a different mode from the one recorded, and when the run
ends before the recording does - a clean prefix is not a pass. The header carries the four limits that
decide where a run ends (`turn_limit_total`, `stall_limit`, `free_step_budget`, `actor_step_limit`),
read as the `EnvConfig` defaults when absent. An unknown hero class is refused, not played as a Warrior;
a malformed file is an `IOException` naming it; a recording is written to a temp file and moved into
place.

Divergence is a hard error, not a warning. The moment a replay stops reproducing, every locked-seed
comparison in the trainer becomes meaningless, and a run that looks fine is worth less than one that
stops loudly.

### A recorded slot index is not self-describing

A slot index only means something alongside the quickslot bindings in force when it was chosen.
`ActionMapper.refreshSlots` places bound items into their bound slots *first*, then fills the gaps from
the backpack in order, so index 1 names one item under one set of bindings and a different one under
another. A binding is written `Class:slot@index`, the item's backpack position, so two items of one class do not
swap; the older `Class:slot` form still reads as first-of-class. Every step that names a slot therefore
carries them, captured **before** the step — an equip or
unequip in the step itself rebinds a slot, so afterwards describes a different world.

`DROP` is the step where that matters, because it is the one action that unbinds its own item: the item
leaves the backpack, stops being reachable from the iterator a capture walks, and its binding is gone
from the file before anyone reads it. It is also the step the corpus did not contain, which is how the
fault survived. See the gates section.

Two consequences that are easy to get backwards:

- **An empty binding set is a state, not a missing field** — and the discriminator has to be a
  *signature*, not an absence. A hero who drops the only item bound to a quickslot has none left, and
  every later slot step records nothing while resolving perfectly from backpack order. Asking "does any
  step carry bindings?" cannot tell that from *the field was lost*, and refusing those refuses working
  recordings — which arrived as an intermittent gate failure, since it only fires on runs where a random
  episode happened to carry nothing.

  So the question is asked of the fields the *same* wire carried: the broken one sent four of nine step
  fields and left the rest at their sentinels, so health, engine time and inventory are absent together,
  on every step. Nine trainer recordings carry `hp = -1 turn = -1.0 inventory = ""` throughout, and that
  is what identifies them. `Replay.wireDroppedStepFields()` is the whole of it.
- **Bindings on equipped items are the game's to keep.** `capture` walks the backpack, so a quickslot
  bound to something equipped is in no recording and cannot be restored. Four of the five hero classes
  bind their starting weapon that way, and wiping it was harmless only until the hero unequipped it —
  after which it sat in the trainer's world still bound and in the viewer's unbound, and every index
  named something else.

### Watching a recording

`superintelligence/replay-viewer.bat` plays a recording in the real game with a HUD over it; `--record`
writes into `trainer-replays/`. The keys are:

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
stopped before the hero performed it. Three of the committed recordings end in death, and all
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

### The catalogue, and which run a recording came from

With no argument, `superintelligence/replay-viewer.bat` prints every recording it can find and asks
for a number. The
listing has three kinds of section: `TEST CORPUS` is the checked-in reference set (the corpus directory, not
whichever directory was scanned first), `TRAINER OUTPUT` is
everything a run wrote that carried no group, and one `GROUP <ID>` per `--replay-group`. Every section is
ranked by score within its hero class and numbered in one sequence, so the number beside a row is the
number typed back.

A group is read from the recording's **header**, not from the directory the file sits in - so a recording
moved out of its folder still says which run wrote it, and a run's output is one directory that can be
copied or deleted whole. Sections order as corpus, ungrouped output, then groups by name: a fixed order,
so two listings of the same disk number the same recordings.

`superintelligence/train.ps1 -Name` supplies the group, so the script does it without a flag to remember.
The id becomes a path segment and a heading, so it is refused rather than escaped - letters, digits,
`.`, `_` and `-`, up to 64 characters.

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
  hero's remains - and watching a recording leaves no remains file behind for the player's next real run
  to pick up. `transitioncheck` and `playbackcheck` gate both halves.
- A floor change is handed back the way the game hands it back. The hero takes the one act the game
  gives him on arrival, in both directions, so the agent's first action on a new floor is not spent
  being interrupted by whatever noticed him. `transitioncheck` gates it in both directions.

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
- **Five reward terms have no emission site.** The cause is structural and is written up in `TODO.md`
  §D2: a state diff cannot see "an item was created in the alchemist", because the alchemy pot is a
  terrain tile and the result is one item delta indistinguishable from a pickup. `KILL` was the odd one
  out — it had its plumbing (`Statistics.enemiesSlain` was snapshotted, `RewardLedger.countKill()` was
  written) and no call site, which is worse than absent: the ledger showed a term that could never move.
  It is wired now, along with `SNEAK_ATTACK` from `Statistics.sneakAttacks`.

## Changes to the game

The renderer bypass required small, additive, guarded changes to `:core` and `:SPD-classes`. Almost all of
them are a no-op when a renderer is present, and each is listed with its reason in
[`superintelligence/README.md`](../superintelligence/README.md#changes-to-the-game) and
[`ENGINE-CHANGES.md`](../superintelligence/ENGINE-CHANGES.md). Added for this work:

- **`Bones.clear()`** - forgets a fallen hero's remains. Nothing in the game calls it; a normal
  playthrough wants the opposite. It exists because an environment playing many independent runs in one
  process cannot have the world depend on which hero died last.
- **`GameScene.answerableWindow()`** - a getter that prefers the scene's live window over the headless
  dialog slot. `show()` fills the slot only when there is no scene, so a caller reading the slot alone
  saw no dialog in the rendered game, and a recorded `MENU` step resolved against nothing.
- **`PRandom.element`** - mirrors `Random.element` for the presentation stream, so a caller choosing
  presentation can be moved across without reimplementing the indexing.
- **`GameScene.headlessGameplay()`, and one widened term in `Level.drop`** - **the one entry that changes
  behaviour rather than tolerating a missing renderer.** `drop` presses the cell a thrown or dropped item
  lands on, so a stone can set off a trap or flatten grass, and it did so only when the current scene is
  a `GameScene`. A renderer-bypassed process has no scene, so every throw was inert. The flag is set by
  `HeadlessGame.install()` and by nothing else, so **a game with a renderer never reads it and the
  guard's answer there is unchanged** - which is what distinguishes it from the version that deleted the
  scene test, which altered alchemy and resurrection in a game that is not this module's.
  `Levitation.detach` (press the landing cell) and `HighGrass.trample` (re-observe after trampling in
  view) take the same term; the second was the cause of `ZRD-CVK-QYT`'s divergence at step 627.
