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

**Nothing has won a run, and nothing has reached floor 5.** The machinery is correct - the network's
gradients check against central differences, the environment is reproducible across processes,
recordings replay exactly, and every one of the twenty gates is green - and the reward function now
asks for the right thing. Measured, over twelve generations: **deaths fell 100% to 19%, mean turns rose
377 to 1335**, and `bestDepth 2` has been observed once. **Depth does not trend yet.**

That last sentence is the one that matters. The three reward defects that kept depth at 1 were real and
are fixed, and the run that shows survival improving is the first evidence in this project that a
change to the objective moved behaviour in the direction it was supposed to. It is not evidence that the
ratio is right. A 40-generation run is the measurement that would settle it, and it is `TODO.md` 1.4.

The milestone is beating the first boss at depth 5 (`research.md:40`).

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
| `replay` | `Replay`, `ReplayRecorder`, `ReplayIO` (write, read, verify), `Quickslots` (the one place a recorded slot index is resolved against its bindings), `DropFixture`, `ReplayCatalog`, `RngTrace` |
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
code that reads it unnoticed. `.env.example` at the repository root is the environment-variable half of
the same thing, with each `SPD_*` name spelled out and the reward keys annotated for what they buy; `.env`
itself is git-ignored, and a run with neither file uses the compiled defaults.

### The reward, and the one ratio that decides it

Reward is measured by diffing observable state across a turn rather than by hooking the item tree
(see `superintelligence/TODO.md` §D2 for why, and what that costs). Two numbers carry most of the
objective:

| Key | Default | What it decides |
| --- | --- | --- |
| `reward.depth_reward` | `25.0` | What one floor deeper is worth |
| `reward.explore_reward` | `0.01` | What a newly-seen tile is worth |
| `reward.stall_penalty` | `150.0` | What giving up costs. **Worse than dying** |
| `reward.death_penalty` | `100.0` | What dying costs |
| `reward.turn_limit_penalty` | `25.0` | What running out of a floor costs |
| `reward.kill_reward` | `12.0` | Per mob killed, from `Statistics.enemiesSlain` |
| `reward.sneak_attack_reward` | `22.0` | Per surprise attack, from `Statistics.sneakAttacks` |
| `reward.talent_reward` | `20.0` | Per talent point spent, from `Hero.talentPointsSpent`. **Nothing can spend one yet** — see below |
| `reward.no_progress_penalty` | `0.05` | A step that advanced no engine time |
| `reward.damage_penalty` | `2.5` | Per HP lost. A full floor-1 bar costs half a death |

**`depth_reward` against `explore_reward` is the objective.** A regular floor holds several hundred
walkable tiles (`TODO.md` §0.8 measured 419 of 1040 sampled floors exceeding 48 on at least one side,
up to 81), so exploration is a large number by the time a floor is finished. At the ratio this project
shipped for most of its life - 10 against 0.05 - mapping a floor without ever taking the stairs paid
*more* than descending, and every run learned to wander. Which is what `bestDepth` being 1 in every
generation of every run had been measuring.

The terminal outcomes are ordered, and the order is the property rather than the values: giving up is
worse than dying, dying is worse than running out of a floor, and running out of a floor is worse than
clearing one. `rewardcheck` asserts the ordering, not the constants, so it survives them being retuned.

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
from `KILL`'s "declared, plumbed and never emitted". `TODO.md` 1.10 is the missing action.

## Gates

Twenty gates, one invocation, ~22 seconds:

```sh
./gradlew :superintelligence:gates
```

| Gate | What it refuses to let happen |
| --- | --- |
| `gradcheck` | An analytic gradient that disagrees with a central difference |
| `gaecheck` | Two advantage implementations that disagree |
| `collectcheck` | A recording the collector cannot replay, **or** a rebinding step recorded against the world the step produced |
| `checkpointcheck` | A policy that does not survive disk, or a bad checkpoint that loads |
| `statecheck` | A sampled observation that does not replay to its rollout's value |
| `parallelcheck` | A parallel update that differs from the serial one |
| `rewardcheck` | Ending an episode being cheaper than dying |
| `transitioncheck` | A floor change the environment does not service, or an agent's first action on the new floor being swallowed |
| `replaycheck` | A catalog that misgroups, misranks or misresolves |
| `rolloutcheck` | A recording with no usable seed |
| `modecheck` | An action mode the environment cannot reach |
| `restartcheck` | A restart that does not rebuild the same first floor |
| `resetcheck` | A reset that does not isolate an episode from whatever ran before it |
| `graphcheck` | A chart reporting a range it did not observe |
| `actioncheck` | A movement action that does not move the way it is named |
| `slotcheck` | A recorded slot index resolving to a different item, or a restore wiping a binding no index can name |
| `verifycheck` | Verification that cannot detect an altered recording, **or** verification that certifies one that cannot be played |
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

### A recorded slot index is not self-describing

A slot index only means something alongside the quickslot bindings in force when it was chosen.
`ActionMapper.refreshSlots` places bound items into their bound slots *first*, then fills the gaps from
the backpack in order, so index 1 names one item under one set of bindings and a different one under
another. Every step that names a slot therefore carries them, captured **before** the step — an equip or
unequip in the step itself rebinds a slot, so afterwards describes a different world.

`DROP` is the step where that matters, because it is the one action that unbinds its own item: the item
leaves the backpack, stops being reachable from the iterator a capture walks, and its binding is gone
from the file before anyone reads it. It is also the step the corpus did not contain, which is how the
fault survived. See the gates section.

Two consequences that are easy to get backwards:

- **An empty binding set is a state, not a missing field.** A hero who drops the only item bound to a
  quickslot has none left, and every later slot step records nothing while resolving perfectly from
  backpack order. `Replay.recordsQuickslots()` asks the question of the recording rather than of the
  step, because a recording with bindings somewhere and none at this moment is playable, and one with
  none anywhere predates the field and is not.
- **Bindings on equipped items are the game's to keep.** `capture` walks the backpack, so a quickslot
  bound to something equipped is in no recording and cannot be restored. Four of the five hero classes
  bind their starting weapon that way, and wiping it was harmless only until the hero unequipped it —
  after which it sat in the trainer's world still bound and in the viewer's unbound, and every index
  named something else.

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
