# Superintelligence TODO

Status of the work in [`docs.md`](docs.md) and [`research.md`](research.md), written against the
code as it stands. "Verified" means it was run and observed, not merely written.

Last updated: 2026-10-06, after the initial framework commit and a determinism audit.

---

## 0. P0 - the environment is not reproducible across processes

**Found by audit, after an earlier claim of exact reproduction was wrong.** Recorded first because
everything in section 1 depends on it, and because I previously reported this as working when it
was not.

**The symptom.** Recording a run and re-executing that recording in a fresh process usually
reproduces it, but not always. Six runs of the same seed and the same policy produced four
distinct step-by-step position traces.

```
426,390,426,462,426,390,426,427,426,391,426,461,426,460,426,390,...
426,390,426,462,426,390,426,427,426,391,426,461,426,460,426,...
426,390,
426,390,426,462,426,390,426,427,426,391,426,461,426,460,426,...
426,390,426,462,426,390,426,427,426,391,426,461,426,460,426,...
```

An earlier smoke test happened to reproduce 249/249 twice and I took that as proof. It was luck.

### 0.1 Fixed - scheduler tie-breaking depended on HashSet order

`Actor.all` is a `HashSet<Actor>` and `Actor` does not override `hashCode`, so iteration order is
identity-hash based and differs between JVM runs. `Actor.headlessStep()` selected among actors with
equal time by iteration order, so *who moved first* varied.

Fixed by adding `Actor.id()` - assigned in creation order, therefore stable - as the final
tie-break in `Actor.headlessStep()`. That removed one source; traces went from all-different to
mostly-identical, but did not close the issue.

### 0.2 Open - the base RNG generator is unseeded

This is the likely dominant remaining cause.

`Random.resetGenerators()` pushes a **no-argument** `new java.util.Random()` as the base
generator. `Dungeon.init()` calls `Dungeon.initSeed()`, then pushes `seed+1`, then calls
`Random.resetGenerators()` - which **discards that stack** and installs the unseeded base.

Consequently only level generation is deterministic: `Level.create()` wraps its whole build in
`Random.pushGenerator( Dungeon.seedCurDepth() )`. Everything after it - mob turns, hero actions, item
drops, combat rolls - draws from the unseeded base generator.

This was never a bug in the game. Within a single process the base generator is created once and
consumed sequentially, so a live playthrough is self-consistent. The game was simply never designed
to be reproducible *across* processes, which is exactly what replay verification requires.

**Proposed fix.** Seed the base generator from the run seed, so the whole process shares one
deterministic stream:

```java
//in Dungeon.init(), instead of Random.resetGenerators()
Random.resetGenerators();
Random.reseedBase( Dungeon.seed );   // new method: replace the base generator with a seeded one
```

Requires a new `Random` method because `resetGenerators()` pushes and the base cannot be popped
(`popGenerator` refuses at size 1). Must be audited for whether any *existing* behaviour depends on
the base generator being unseeded - it should not, since nothing can replay a live process.

### 0.3 Still to check after 0.2

- `level.mobs` is also a `HashSet<Mob>`. Any place where mob iteration order affects state or RNG
  consumption will still vary. Needs an audit of every `for (Mob ... : level.mobs)` loop.
- The four `HashSet`/`HashMap` fields on `Level` that are iterated during play.
- Re-run the six-process trace test until it yields exactly one distinct result.

### 0.4 Consequence

Until this is closed, **every claim that rests on reproducibility is unsupported**, including:
locked-seed run comparison, best-per-seed ranking, replay verification as a regression gate, and the
`SeedPool` generalisation schedule. The `verify` command currently reports divergence as a hard
error, which is correct behaviour - but until 0.2 lands it will fire intermittently on healthy runs.

---

## 1. The critical gap: nothing has learned anything

**The neural network has never been executed.** `PPO.rollout()` and `PPO.update()` are called from
nowhere. `train.Worker` loads weights into a `Network`, then hands control to `ScriptedPolicy`.

Everything in this repository is a testbed around a learning loop that does not run. The documents'
actual goal - an agent that plays - is unmet.

| # | Task | Size | Notes |
| --- | --- | --- | --- |
| 1.1 | Call `PPO.rollout()` in `Worker.runEpisode` in place of `ScriptedPolicy` | S | The worker already has the env, the network and the masks. |
| 1.2 | Confirm the forward pass produces finite logits and the critic a finite value | S | Never executed even once. A silent NaN would look like "no learning". |
| 1.3 | Confirm `Network.backward` produces non-zero gradients | S | Same - never executed. |
| 1.4 | Run one generation end to end and check `lastPolicyLoss` / `lastValueLoss` are sane | S | |
| 1.5 | Verify the seed gate: 1 locked seed until Goo (depth 5), then 10, then 100, then random | M | `SeedPool` and `Trainer.advanceSchedule` are written; the gate has never had real depths to act on. |

research.md:40 sets the first real milestone: *"Train the AI on a single seed until it can
consistently beat the first boss (Goo)."* Nothing has been trained, so nothing has been beaten.

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
| 3.2 | Desktop replay viewer - re-run a recording in the rendered game | research.md:45 | M |
| 3.3 | Live play with real-time reward/term monitoring | docs.md:10 | M |
| 3.4 | "type totals/subtotals" in the dashboard | docs.md:41 | S |

3.1 and 3.2 are the two places the documents explicitly ask for a graphical interface and I built
an ANSI console instead. `diag/` has the data; only the rendering is missing.

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

4.2: `Trainer` spawns worker JVMs over a binary stdin/stdout protocol and the code path is
exercised only single-worker. Concurrency bugs in `Trainer.pushWeights` and `WorkerHandle` are
untested, and `pushWeights` still contains a dead `byte[] payload = new byte[0]` initialisation
immediately overwritten. Buffer sizes grow with worker count and have not been measured.

---

## 5. Known rough edges

Small things that are wrong but not blocking.

- `Trainer.curriculumScale()` returns a hardcoded `1f`; it ignores the real curriculum. The console
  report prints a constant under the label `shaping=`.
- `PPO.approximateKL` takes `t`, `logits` and `mask` parameters it does not use.
- `Trainer.lastEpisodes` is declared mid-class rather than with the other fields.
- Some engine states end a rollout as `STALLED` early. Seed `HERO` terminates after 2 turns, where
  most seeds run the full budget - an encounter reaching a state the action space cannot answer.
  Coverage is uneven across seeds.
- `TargetHealthIndicator`, `AttackIndicator`, `QuickSlotButton` and `GameScene` gained null guards
  for headless operation. Each is correct with a renderer present, but they are now load-bearing for
  a code path most contributors will never run.
- The `network` field on `Worker` is currently only a parameter sink (see 1.1).

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