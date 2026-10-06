# Superintelligence TODO

Status of the work in [`docs.md`](docs.md) and [`research.md`](research.md), written against the
code as it stands. "Verified" means it was run and observed, not merely written.

Last updated: 2026-10-06, after the gradient repair, a second determinism audit, and the first
successful multi-hero reproducibility sweep.

---

## 0. P0 - RESOLVED - the environment is not reproducible across processes

**Fixed.** Recording a run and re-executing it in a fresh process now reproduces exactly.

Verified: 120 rollouts - 4 seeds x 5 hero classes x 6 repeats - produce 1 distinct score per
seed/hero pair, and a recorded 499-step run verifies 4/4 in fresh processes.

A second pass found more identity-hash iteration after the first fix: `Char.buffs(Class)` handed
callers a `HashSet`, `Random.chances(HashMap)` chose secret room contents off a `Class`-keyed
`HashMap`, `Mob.chooseEnemy` resolved ties by iteration order, and `CursingTrap` / `VaultLevel` /
`Hero` used `Collections.shuffle`, which ignores the seeded generator entirely. All made
insertion-ordered or switched to `Random.shuffle`.

One trap worth recording: `GameSettings.getString(key, def, maxLength)` treats an over-long stored
value as corrupt and overwrites it with the default, and `SPDSettings.customSeed()` reads with a
20 character cap. A 23 character seed was therefore silently discarded and the run continued on a
*random* seed - which looked exactly like residual nondeterminism and cost real time to
rediscover. `LevelPipeline.startRun` now verifies the seed round-tripped and fails loudly.

The symptom when broken was that recording a run and re-executing it usually reproduced, but not
always - six runs of one seed produced four distinct position traces. An earlier smoke test had
reproduced 249/249 twice and I took that as proof. It was luck, and it took a dedicated audit to
find.

Three causes, all now fixed:

**0.1 The base RNG generator was unseeded.** `Random.resetGenerators()` pushed a no-argument
`new java.util.Random()`, and `Dungeon.init()` called it *after* pushing the seeded stack,
discarding it. Only level generation was deterministic, because `Level.create()` wraps its build in
`Random.pushGenerator( Dungeon.seedCurDepth() )`. Everything after it - mob turns, combat rolls,
item drops - drew from an unseeded generator.

This was never a game bug: within one process the base generator is created once and consumed
sequentially, so a live playthrough is self-consistent. The game was simply never designed to be
reproducible across processes, which is what replay verification needs.

Fixed by adding `Random.reseedBase(long)`, which reseeds the base generator in place without
disturbing anything pushed on top of it, and calling it from `Dungeon.init()` with the run seed.

**0.2 An unseeded generator inside level generation.** `EntranceRoom.placeEarlyGuidePages` pushed
an unseeded generator, deliberately - its comment reads *"so meta progression doesn't affect
levelgen"*. Correct intent, but it meant the first guidebook page landed on a different tile in every
process, which alone made floor 1 irreproducible.

Seeded from the floor's own seed with a fixed offset. Still isolated from meta progression, still
its own generator, now deterministic.

**0.3 HashSet iteration order in the turn scheduler and over actors.** `Actor.all`, `Actor.chars`,
`Level.mobs` and `Level.blobs` were all hash-based collections keyed on identity hash codes, so their
iteration order differed between JVM runs. `Actor.headlessStep` broke time ties on iteration order,
so *who moved first* varied; `Level.mobs` iteration order varied wherever it fed a game decision.

`Actor.all` and `Actor.chars` are now `LinkedHashSet`, `Level.mobs` is a `LinkedHashSet` and
`Level.blobs` a `LinkedHashMap`, so all four iterate in insertion order. Membership semantics are
unchanged. `Actor.headlessStep` additionally breaks remaining ties on `Actor.id()`, which is
assigned in creation order and therefore stable.

### Remaining risk

- `Mob` and `Char` still do not override `hashCode`. Nothing found iterates them through a hash
  collection during play, but that is an invariant future changes could break. Worth a comment at
  the `Level.mobs` declaration.
- `ColorMath.random` is used for particle colours only. Harmless, because headless never creates a
  particle.
- Determinism has been verified at 400 turns on a handful of seeds. Not verified across all 26
  floors, boss levels, or the shop/alchemy paths, which are exactly where exotic code lives. A
  long-horizon soak across many seeds is the right next check.

---

## 1. The critical gap: nothing has learned anything

**The network now runs, and its gradients are correct.** The forward pass produces finite logits
and a finite critic value, every layer receives a non-zero gradient, and an Adam step moves the
parameters. This was not true before: the backward pass had five independent defects - missing
activation derivatives in `Conv2D` and `Dense`, an LSTM cell reading post-update state, a hidden
gradient copied before the heads filled it, an unsized LSTM input-gradient buffer, and heads whose
own gradients were never accumulated. All of them are fixed and covered by `gradcheck`.

`gradle :superintelligence:gradcheck` finite-difference checks the analytic gradients against
central differences on 112 sampled parameters across all seven layers, and exits non-zero on a
mismatch. It is confirmed to fail when a derivative is deliberately removed. Run it before
trusting any training run.

`PPO.rollout()` and `PPO.update()` are still called from nowhere. `train.Worker` loads weights
into a `Network`, then hands control to `ScriptedPolicy`. Everything in this repository is still a
testbed around a learning loop that does not run.

| # | Task | Size | Notes |
| --- | --- | --- | --- |
| 1.1 | Call `PPO.collect()` in `Worker.runEpisode` in place of `ScriptedPolicy` | S | The worker already has the env, the network and the masks. Collection is capped and safe to resume. |
| 1.2 | Run one generation end to end and check `lastPolicyLoss` / `lastValueLoss` are sane | S | |
| 1.3 | Verify the seed gate: 1 locked seed until Goo (depth 5), then 10, then 100, then random | M | `SeedPool` and `Trainer.advanceSchedule` are written; the gate has never had real depths to act on. |

**`PPO.collect()` is capped and verified.** `rolloutCap` (default 2048) bounds collection at ~98MB,
because a step is ~49KB and `turnLimitTotal` is 40000 - uncapped, one long episode is ~1.9GB
against a 1536m worker heap. A cap leaves the env mid-episode and resumes it next call. Verified
across 20 cases (4 seeds x 5 caps): with no update mid-episode every cap reproduces whole-episode
collection exactly, including cap=1. An update mid-episode deliberately resets the recurrent state -
a weight change is an information boundary - so the same seed keeps reproducing regardless of where
updates land.

**Worker-to-trainer data flow is unresolved.** The trainer calls `PPO.update()` on its own buffer,
but workers currently return only an episode summary and a replay, not transitions. Either
transitions have to reach the trainer for a pooled update, or each worker updates locally and the
updated weights have to be returned. This has to be decided before 1.1 means anything.

**Headless coverage is the next likely source of crashes.** Running the real policy surfaced three
that the scripted one never touched: blobs had no emitter (15 blob types NPE in `evolve()`),
`GameScene.cancel()` dereferenced a null `cellSelector`, and `GameScene.spellSprite()` read
`scene.spells` unguarded. All three are fixed, but they are a class of bug, not three isolated
ones - a random policy explores action space the scripted one never did, and there are almost
certainly more.

**Unverified: hero class appears not to affect the score.** Every hero class produced an identical
score and turn count on the same seed. `GamesInProgress.selectedClass` is applied in
`Dungeon.init`, so the class does reach the hero, and the score is probably driven by turns and
depth rather than anything class-specific. Not confirmed either way.

research.md:40 sets the first real milestone: *"Train the AI on a single seed until it can
consistently beat the first boss (Goo)."* Nothing has been trained, so nothing has been beaten.

**Seed text is capped at 20 characters.** `GameSettings.getString(key, def, maxLength)` discards
an over-long stored value and `SPDSettings.customSeed()` reads with a 20 character cap, so a
longer seed is silently dropped and the run falls back to a random one. `LevelPipeline.startRun`
now verifies the seed survived the round trip and fails with an explanation. Keep generated seed
text inside the cap.

---

## 2. Reward terms that never fire

Declared in `reward.RewardTerm`, but with no emission site. Each is a documented requirement.

| Term | Document | Status |
| --- | --- | --- |
| `CRAFTED_MEAT_PIE` | docs.md:24, research.md:11+35 (called out specifically) | dead |
| `CRAFTED` | research.md:11 | dead |
| `CURSE_REMOVED` | docs.md:23 | dead |
| `EQUIP_TOO_STRONG` | docs.md:31, research.md:12 | dead |
| `KILL` | - | dead; `RewardLedger.countKill()` is written and never called |
| `MENU_NOOP` | research.md:57 (shop-loop guard) | dead |

**Dead configuration.** `EnvConfig.allowAlchemy` and `EnvConfig.allowTrading` are read by nobody.
Alchemy and shops are reachable only because `Hero.handle` happens to resolve them.

These are all casualties of the state-diffing decision (see Deviations, D2). A diff cannot see
"an item was created in the alchemist", because the alchemy pot is a terrain tile and the result is
one item delta indistinguishable from a pickup. Fixing them needs real observation points in the
craft, equip and scroll/potion paths - i.e. the hooks research.md:8 actually asked for.

---

## 3. Missing subsystems

| # | Task | Document | Size |
| --- | --- | --- | --- |
| 3.1 | libGDX trainer UI in the `desktop` module | research.md:16, docs.md:10 | L |
| 3.3 | Live play with real-time reward/term monitoring | docs.md:10 | M |
| 3.4 | "type totals/subtotals" in the dashboard | docs.md:41 | S |

**3.2 is DONE.** Desktop replay viewer built: `gradle :desktop:replay --args="--file <replay>"`.
Recorded actions go through `ActionMapper` to `Hero.handle`, the same call the cell selector makes,
so action resolution is identical to a human playing. HUD shows step, seed, score, depth, turns,
speed and divergence. Needed two engine hooks: `Game.lockCellInput` and `Game.setSceneClass`.
Enters via `InterlevelScene` because its transition to `GameScene` is hardcoded, so a subclass would
never be entered. See `PLAN-replay-viewer.md`.

Known gap: every existing recording is `WORLD` mode only, so the menu / slot / targeting action path
is written but never executed. Needs a recording that loots or opens a shop.

`diag/RunReport` already emits a per-term table and per-floor totals. "Subtotals" (a grouped
category rollup, eg. all combat terms together) is not implemented.

---

## 4. research.md sections not addressed

| # | Section | Status |
| --- | --- | --- |
| 4.1 | Garbage collection and memory pooling (research.md:60) | **Unaddressed.** |
| 4.2 | 1,000 simultaneous simulations (research.md:16, docs.md:42) | Never run at scale. |
| 4.3 | IMPALA as the alternative learner (research.md:26) | Not built. Listed as an optional alternative; decide whether it is still wanted now that the PPO path exists. |

4.1 in detail: the documents ask for an audit of the core game loop and pooling of volatile classes
(`FloatingText`, `Speck`). Zero references to either exist. I do pool `Transition` and reuse all
observation buffers in my own code, which measurably reduced allocation, but the game loop itself is
unaudited. This is the most likely thing to fail at high worker counts - see 4.2.

4.2: `Trainer` spawns worker JVMs over a binary stdin/stdout protocol. The path is now exercised at
two workers for two generations - handshake, params push, episode frames, weight push, replay write -
after the trainer was split into `Trainer` / `WorkerPool` / `Protocol` / `TrainOptions` / `Episode`.
What remains untested is `WorkerPool`'s parallel weight push under a real 20-worker pool, and whether
the per-worker buffers behave at that count.

The dead `byte[] payload = new byte[0]` initialisation in `pushWeights` is gone; it was replaced
earlier.

---

## 5. Known rough edges

Small things that are wrong but not blocking.

- `Trainer.curriculumScale()` returns a hardcoded `1f`; it ignores the real curriculum. The console
  report prints a constant under the label `shaping=`.
- `PPO.approximateKL` takes `t`, `logits` and `mask` parameters it does not use.
- `PPO.oldLogProbabilityFor` returns `t.oldLogProbability` and ignores its second argument.
- Some engine states end a rollout as `STALLED` early. Seed `HERO` terminates after 2 turns, where
  most seeds run the full budget - an encounter reaching a state the action space cannot answer.
  Coverage is uneven across seeds.
- `TargetHealthIndicator`, `AttackIndicator`, `QuickSlotButton` and `GameScene` gained null guards
  for headless operation. Each is correct with a renderer present, but they are now load-bearing for
  a code path most contributors will never run.
- **`PPO.collect()` and `PPO.rollout()` have no callers,** and neither has anything else in the
  rollout half of `PPO`: `rolloutCap`, `episodeInProgress` and `recurrentStateStale` exist only to
  serve them. `PLAN-data-flow.md` step 2 replaces them with `rl/EpisodeCollector`; the cap survives
  as a bound on retained observations rather than on collected steps.
- **`Worker`'s `network` field is still only a parameter sink** (see 1.1).

---

## 6. Deliberate deviations

These were choices, not oversights. Each has a reason and a cost. Revisit any of them if the reason
stops holding.

### D1 - Single convolution layer, not a convolutional stack

**Was:** research.md:30 implies a CNN over the grid, plural channels.
**Now:** one 4x4/stride-2 convolution over all 21 planes, then fully connected.

*Reason:* dungeon rooms are not the same shape, so translation invariance across repeated motifs is
worth less here than the relationship between hero, walls and stairs. One layer gives local spatial
features at a fraction of the parameters.
*Cost:* less spatial generalisation. A real stack may learn "gap in wall" better.

### D2 - State diffing instead of hooks in the item and buff trees

**Was:** research.md:8 - *"placing hooks directly into the game's core logic packages"*, with
`Belongings.java`, `ScrollOfIdentify.java`, `Potion.java`, `Hunger.java` named.
**Now:** `RewardModel` diffs observable state between turns.

*Reason:* no edits to hundreds of item classes, and it measures what an action *achieved* rather
than which code path it took - a potion healing through a wand and a scroll healing through an
upgrade both read as "hero gained health".
*Cost:* **this is why every crafting, curse-removal and equip-rejection term is dead.** The diff
cannot distinguish alchemy from a floor pickup. If those rewards matter, D2 must be partly reverted
- see section 2.

### D3 - Truncated BPTT in the PPO update

**Was:** research.md:31 - an LSTM "strictly necessary" for memory.
**Now:** the LSTM is present and trained, but gradients are truncated at length one.

*Reason:* an exact through-time gradient needs every timestep's pre-activation state retained until
the update. At this sequence length that is gigabytes per worker.
*Cost:* the trunk, heads and critic get exact gradients; the LSTM gets exact gradients within a step
and none across steps. Whether this is enough to actually remember a dropped potion three rooms back
is an open empirical question, and it is the single assumption most likely to make the memory layer
fail.

### D4 - Worker processes, not threads

**Was:** docs.md:42 and research.md:16 imply 1,000 concurrent simulations.
**Now:** one run per JVM, pooled by `Trainer`.

*Reason:* not a preference. The game's simulation state is almost entirely static - `Dungeon.hero`,
`Dungeon.level`, `Actor.now`, `Level.visited`, the whole actor registry. Two environments cannot
coexist in one process.
*Cost:* ~1.5 GB and a JVM per worker. 1,000 workers is not practical on one machine; that needs
multiple hosts and a scheduler change.

### D5 - Console dashboard, not a libGDX UI

**Was:** research.md:16 and docs.md:10/41 both ask for a trainer interface.
**Now:** ANSI in a terminal, honouring `NO_COLOR` and non-tty output.

*Reason:* it works everywhere including CI logs, and it was buildable before the engine was.
*Cost:* the graphical trainer is not delivered - see 3.1.

### D6 - `verify`, not `replay`, for re-executing a recording

`ReplayIO.play()` was renamed `ReplayIO.verify()`, and the `replay` subcommand and Gradle task are
now `verify`. Nothing is drawn and nobody watches; the operation exists to catch the moment a
recorded path stops reproducing.

*Reason:* `play` implies a human watching the game, which is 3.2 and does not exist.
*Note:* the `Replay` *format* keeps its name. A recorded run that can be re-executed is a replay by
any normal definition, and the file magic is `SPD-REPLAY`.

### D7 - Targeting and inventory as separate heads

**Was:** research.md:52 poses this as an open question - target every turn, or pause?
**Now:** the environment pauses (`EnvMode.TARGETING`, `SLOT`).

*Reason:* predicting a target on the ~95% of turns that are not aiming spends capacity and invites
spurious correlations. Keeping slot out of the flat action enum means output width does not change
with the hero's carrying capacity.
*Cost:* a step is not always one game turn, which complicates any tooling that assumes it is.

### D8 - Aim targets limited to the eight neighbours

*Reason:* shots resolve against a `Ballistica` collision cell, so a shot at range has to start from
one of these directions anyway.
*Cost:* the agent cannot express "throw at that specific far cell" without a two-step path.

---

## 7. Stale class names in the source documents

Worth knowing before either document is treated as a specification. They reference classes that do
not exist in this v4.0.1 checkout:

| Document | Referenced | Reality |
| --- | --- | --- |
| research.md:5 | `FogOfWar.java` (in `tiles`) | Does not exist. FOV lives in `Level.updateFieldOfView` + `mechanics/ShadowCaster`. I read `Level.heroFOV` directly. |
| research.md:5 | `ShadowCaster.java` (in `mechanics`) | Exists, in the right place, but is reached through `Level`, not called directly. |
| research.md:5 | `InputHandler.java`, `ControllerHandler.java` (in core) | Not in core. They live in `com.watabou.input` in `SPD-classes`. Bypassed either way. |
| research.md:55 | `Alchemy.java` | Does not exist. The pot is `Terrain.ALCHEMY`; the UI is `scenes/AlchemyScene`. |
| research.md:5 | `tiles` package | No `tiles` package in core. |

The *intent* of every reference is achievable. The literal instruction is not always expressible.