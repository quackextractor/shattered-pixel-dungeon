# Shattered Pixel Dungeon - Superintelligence

| Badge | Version |
| --- | --- |
| [![Version](https://img.shields.io/badge/version-0.10.0-blue.svg)](CHANGELOG.md) | 0.10.0 |
| [![Game](https://img.shields.io/badge/game-4.0.2-blue.svg)](../README.md)

A headless reinforcement-learning framework for Shattered Pixel Dungeon, implementing the design
described in [`docs.md`](docs.md) and [`research.md`](research.md).

**Two versions, two subjects.** The `version-` badge is this module's - `0.10.0` - and its history is in
[`CHANGELOG.md`](CHANGELOG.md). The `game-` badge is Shattered Pixel Dungeon's - `4.0.2` - which is
upstream's and moves only when this fork merges upstream. Neither moves because of anything recorded in
the other's changelog, and the module's series starts at **0** specifically so the two cannot be read as
one line. This module is pre-1.0: a `0.x.y` minor bump may still change behaviour a caller depended on.

The game runs as a pure simulation - no window, no renderer, no audio - so runs can be rolled out
thousands of turns per second, each scored by a configurable reward function, with the best run per
seed recorded and re-verified.

**A recording the trainer writes can now be watched, and that is a measured claim rather than a plan.**
It was false, and `verify` said otherwise: the trainer pinned a step's quickslot bindings *after* the
step, so a `DROP` — the one action that unbinds its own item — named a different item on playback than
it did on the recording; `ReplayIO.verify` resolved recorded slot indices against the live bindings
rather than the recorded ones and skipped the comparison entirely when the field was empty, which is
why nine unplayable recordings each reported `[OK]`; and `viewcheck`'s verdict was "did not diverge",
so a player that halted for any other reason scored a pass. All four are fixed, the corpus now contains
a `DROP` in `SLOT` (`duelist-drop.replay`, the thing 17 recordings and 310 SLOT steps did not), and two
real training runs were verified and played in the real viewer end to end:

```
APR-SLJ-DXM.dat, 1514 steps   [OK] verify reproduced 1514   [OK] viewcheck played clean
```

`trainer-replays/` is **empty and untracked**, and that is deliberate. It is where the trainer writes by
default and it is in `.gitignore`, so a run's output lands in one place the catalog already reads and a
reboot does not take it - while nothing accumulates in version control. The seven recordings that were
committed there once are untracked, the 185 that earlier runs had scattered across temp directories are
deleted, and every claim this file made about what that directory contains has been corrected below.
See [`CHANGELOG.md`](CHANGELOG.md) 0.9.0.

**A run's recordings are its own group, and it keeps every episode.** `--replay-group <id>` puts a run's
output in a subdirectory of `trainer-replays/`, stamps `group=<id>` into every header, and gives the
catalogue a `GROUP <ID>` section of its own - so "what did this run do" is answered with that run and not
with every run on the machine. `train.ps1 -Name` supplies the group, so the script does it without a flag
to remember. Empty is no group, which is the default and what every recording written before groups
existed is. Separately, **`--record-every N` defaults to 1**: the trainer used to send one episode in
three, which made "best per seed" mean best of whatever happened to be sent, and a run's single
floor-2 episode was not in the catalogue because it was one of the two thirds nobody sent. A run also
keeps its single **worst** episode alongside the best per seed. See `CHANGELOG.md`.

**A run's weights, checkpoints and metrics go to `out/run/`**, which is `superintelligence/out/` - gitignored
apart from a tracked `.gitkeep`, and *not* the system temp directory, which is emptied on a schedule nobody
controls. Evidence worth keeping goes in `superintelligence/out/evidence/` in the session that produced it.
`AGENTS.md` **Artefacts** is the rule; gate scratch (`spd-<gate>` under temp) is the exception.

## Read this first

**Nothing has been trained yet. The machinery is correct; the reward still does not ask for
anything.** Five things were missing; four are fixed and the fifth is the milestone itself. **The
30-generation measurement `TODO.md` 1 has been asking for is now done, and it does not clear the
noise floor:**

| | generations 0-14 | generations 15-29 |
| --- | --- | --- |
| `meanScore` mean | **-3,535.7** | **-3,534.1** |
| `meanScore` sd | 137.5 | 98.5 |
| `meanScore` range | **567.2** | **375.4** |

30 generations, 8 workers x 4 episodes, 4 update threads, seed 20261016,
`train.random_seed_rate=0`, every other default unchanged, 12 min 41 s, and it completes past the
generation ~22 where the policy push used to kill it. **The movement is +1.6 points against a
per-generation standard deviation of 98 to 138** - noise, and two orders of magnitude smaller than
the noise. `bestDepth 2` in **9 of 30** generations; `meanDepth` peaks at 1.062; `valueLoss` is O(1)
and `clipFraction` falls 0.552 -> ~0.05 while entropy climbs, so the update is moving and the
objective is not. **The band this repository had been quoting (+/-16 over 15 generations) was
measured on a different config and is 6 to 8 times too tight for this one; a change has to clear
~100 points over 15 generations to mean anything.** One of the two recordings the run wrote does not
reproduce - `issues.md` 37. Evidence: `out/evidence/noise-floor-30.csv`,
`out/evidence/train-noise-floor-30.log`.

**Four things have changed since that run was measured, and none of them has been measured on a
training run yet.** The sampler now draws whole `rl.chunk_length` blocks instead of an independent
5% per step, so the recurrent chunk the update is built around actually occurs — `1,113` retained
steps in `70` contiguous runs of up to `16` over `20,000` steps. The trunk is **2.55x smaller** after
a second strided convolution: **1,403,316** parameters against **3,583,827**. Sub-mode entry is
masked unless the hero carries something to use, which took uniform-policy episodes from
**`STALLED` 14 of 40 to 2 of 40**. And the alchemy pot is reachable headlessly, with `CRAFTED` and
`CRAFTED_MEAT_PIE` paying. **The claim that the objective now moves is untested, and
`TODO.md` §1 says so.**

And the five things:

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
- **Depth 2 is reached, and reaching it is not a result.** An earlier version of this file claimed it had
  on the strength of a trend line reading `depth 1.0..2.0`. That `2.0` was not measured: `Graph.bar`
  widens a flat series' scale so its division is defined, then prints the widened span as the range.
  Since then the axis has been gated (`graphcheck`) and depth 2 really does occur - `bestDepth 2` in
  **9 of 30** generations of the 30-generation run above, and `meanDepth` peaks at 1.062. What is *not*
  established is that the agent descends rather than wanders: three repeats of one identical config put
  `bestDepth` at 2, 1 and 2, so `bestDepth` on its own is a coin flip (`issues.md` 27). **Judge on
  `meanScore` over a run long enough to clear the noise floor, not on `bestDepth`.**
  **There was a candidate mechanism, and it is now fixed.** Every run in this project's history used more
  than one update thread, and `ShardedUpdate` handed every shard the *same* `Network` rather than a copy
  - so the gradient applied was whatever survived the last `clearGradients()`, and every layer's forward
  scratch was shared between threads. Found by six generations of a 77-generation run reporting an
  entropy that is arithmetically impossible. **Fixed and gated** (`parallelcheck` is 5 cases; reinstating
  the fault fails the new one with *"4 shards hold 1 distinct networks"*). It is also **13% faster** -
  six threads writing 3.5M parameters into one object were contending for the same cache lines.
  **`CHANGELOG.md`, closed.** It could not be said whether the fixes helped, because `SeedPool` leaks
  10% random seeds by design and a repeat of the same config is therefore not the same run.
  **Measured** - three repeats of one
  15-generation config on a fixed seed, `issues.md` 27 - so the band is known: `gen0` agrees to 1.4
  points, `meanScore` over 15 generations moves +/- 16, and `bestDepth` lands on 1 or 2 at chance.
  **Judge on `meanScore` and require it to clear the band; `bestDepth` on its own is not evidence.**
- **What remains is neither a crash nor a stall - and that was wrong.** This used to say every episode
  runs the full 1500 turns with zero stalls. **Measured: 98-99% of every episode ends STALLED**, at
  `meanTurns` 122-140 against `EnvConfig.stallLimit = 120`, and it is already 99% at generation 0. Three
  repeats of one config put `bestDepth` at 2, 1 and 2 - so `bestDepth` is a coin flip and the 186 -> 59
  `meanScore` fall is inside the noise. See `issues.md` 27. `depthReward` of +10 against `turnCost` of
  0.002 means
  descending pays 5,000 turns of idling, and `KILL` / `GOLD_GAIN` / `ITEM_PICKUP` still never fire
  (`TODO.md` §2).
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
   `ENGINE-CHANGES.md` and they are committed. See `CHANGELOG.md`.
- **Descending now survives the whole journey, including climbing back up.** A hero who walks down the
  stairs was ending the episode on the spot: `Game.switchScene(...)` raises a request and returns, and
  the acknowledgement that turns it into "a scene wants to take over" is raised in `Game.step()`,
  reached from `Game.render()` — which never runs headlessly. That is why `bestDepth` was 1 in every
  generation of every run: the one action that would have moved the agent was ending it.
  Four more faults were only reachable because nothing descended. The last of them was the mirror image
  of the first, and `CHANGELOG.md`: the environment never gave the hero a turn on the new floor, so a
  mob waking up and seeing him for the first time interrupted the agent's first action there — the
  agent's first move on every new floor was spent being noticed by a rat. Both environments now agree,
  and `viewcheck` is 17 of 17 with `verifyall` green. See `CHANGELOG.md`.
- **A chasm fall was being dropped on the floor.** A fall is the only transition that carries no
  `LevelTransition` — `Chasm.heroFall` writes `InterlevelScene.mode` and asks for a scene switch, and
  nothing else — and `LevelPipeline.handleTransition` tested for a transition before it tested the
  mode, which is false on every fall and true on none of the others. So it answered "not a level
  transition", cleared the switch request and left the hero standing on the pit: `Transition.FALL` and
  `handleFall()` were unreachable code. Three more faults on the same path: the landing ignored
  `InterlevelScene.fallIntoPit`, the landing applied no fall damage (so a fall was free and strictly
  better than the stairs), and walking a hero *at* a chasm threw a `NullPointerException` out of
  `Chasm.heroJump` and out of the worker with it. **And the agent could not cross a chasm by choice** —
  the game's "jump?" prompt gated its own answer on `elapsed > 0.2f` on a clock only a rendered frame
  loop advances, and `WindowBridge` discarded the dialog on any press regardless, so the environment was
  asked the question and then had the answer thrown away. The prompt is now aged once per environment
  step (once per frame, the approximation `ReplayPlayer` already makes about a recorded step) and the
  game's own `hide()` closes it, so the agent asks, answers, and ends up a floor down — for 13 steps and
  -15.00, against a descent at +1800.00. And a levitation expiring over a chasm now drops the hero, which the
   game's `Levitation.detach` does not do without a renderer. `CHANGELOG.md`; `fallcheck` and
   `dialogcheck` gate it, 6 and 3 cases.
- **A thrown item did not trigger what it landed on, and nothing had ever found out** — the third fault
   on this path, filed as its own issue and now closed. `Level.drop` presses the cell an item lands on,
   which is how a thrown stone sets off a trap or flattens grass, and it did so only behind
   `scene() instanceof GameScene`: a renderer-bypassed process has no scene, so **every throw landed on a
   trap and did nothing to it.** Measured on four seeds, an armed `WornDartTrap` was still armed after a
   `Food` was dropped on it in all four. `Level.drop` is the only place that needed to change — `Item.onThrow`
   and `Item.doDrop` both call it and nothing else puts an item on a floor — and the scene test survives,
   because the two drops it exists to exclude (`AlchemyScene` crafting onto the cell the hero stands on,
   `InterlevelScene.resurrect` onto the cell he died on) both need a renderer and cannot exist headlessly.
   A dropped item now fires a trap and tramples grass, and level generation still presses nothing.
   `presscheck` gates it — 3 cases, in `gates` — and `test-replays/duelist-throw.replay` is the recording
   that makes a recorded press reproducible on both sides. `CHANGELOG.md`.


The update is 3.29x faster at `--update-threads 4`, so a 100-generation run is now a matter of
hours rather than most of a day. **What is left is to run one and find out whether it learns**
(`TODO.md` §1). See [`PLAN-data-flow.md`](PLAN-data-flow.md), whose steps are all resolved.

**The reward now prices the objective, and the price of giving up is above the price of dying.** Three
things were wrong with it, and each was a property rather than a size:

- **Exploration outbid a descent.** A regular floor holds several hundred walkable tiles — measured
  across 1040 floors, the largest is 81 tall and 419 exceed 48 on one side — so at the old 0.05 a hero
  that mapped a floor without ever touching the stairs earned *more* than descending did. That is the
  objective restated as a ratio, and it is why `bestDepth` had been 1 in every generation of every run.
  Now `reward.depth_reward=1800.0` against `reward.explore_reward=0.05`.
- **Killing paid nothing and being hit cost something.** `KILL` was declared, `RewardLedger.countKill()`
  was written and never called, and `prevKills` was snapshotted and never compared. Walking past
  everything was optimal, which is the shape every recorded death has: the hero does not lose a fight,
  he runs out of room. Now emitted from `Statistics.enemiesSlain`.
- **`STALLED` cost exactly what dying cost**, so quitting and losing were one decision with two labels —
  and the useful label is the one that survives. It is now worse than dying (3000 against 2500). Separately,
  `TURN_LIMIT` was `-turnCost * 100`, a fifth of a point against a +10 descent, so idling out a floor was
  a *positive-value strategy*. And a step that advances no engine time — equipping and unequipping cost
  nothing in the real game too — is now charged, which is the `duelist-mid` loop at a price.

**Surprise attacks are the largest combat payout**, read from `Statistics.sneakAttacks`, which
`Mob.defenseProc` increments exactly when `Mob.surprisedBy` holds. A kill is paid for damage dealt, which
depends on the weapon and on rolls. A surprise attack is paid for *arriving unseen*, and it is the one
combat outcome that costs no health at all: `Mob.surprisedBy` skips the defender's `defenseSkill`
outright. Paying the kill alone teaches trading blows with whatever is in reach.

**A free-action loop is bounded, not merely charged.** `HRT-YYP-KGC` spent **502 of 1215 steps advancing
no engine time - 41% of that episode's whole cost** - almost all of it `OPEN_INVENTORY` followed by
`CANCEL` out of `INVENTORY`, with a real move between pairs. `env.stall_limit` cannot see it: it counts
consecutive steps where the hero did not move, so **one move resets it**. `env.free_step_budget=120`
counts those steps as a total on the floor and ends the episode as a stall when it reaches the budget.
Measured: 120 free steps ended the loop as `STALLED` at **-4622.00 against dying's -2500.00**.

**An item that creates a mob now leaves it usable with no renderer.** A Scroll of Mirror Image killed two
workers in a training run - `GameScene.add( Mob, float )` gives a mob a sprite and a place in `Actor.chars`
only when a scene exists, and `Char.move` dereferences the sprite unconditionally. `mobspritecheck` is the
gate: 3 cases, in `gates`, and the third asserts that with `headlessGameplay` off the stand-in factory is
never consulted, so a game with a renderer cannot reach the branch.

`rewardcheck` grew from 9 cases to 13, and the new ones assert **properties** — that the four terminal
outcomes are ordered, that descending beats idling out the floor, that exploration cannot outbuy a
descent, that a free step costs more than a real one — rather than comparing constants. It is 20 cases
now; the three `0.10.0` added are in the next section.

**`reward.profile` can switch the reward function down to the objective alone.** `default` pays every
term. `depth_only` pays nothing but a descent, a win, and four prices for stopping — the per-turn cost,
the charge for a step the engine resolved for free, running out of turns, dying and stalling — so
**the only route to a positive score is a new floor or a win**, and every incidental term is still
counted while paying zero.

It is a temporary override and nothing is re-priced: every weight still applies when it is on, terms are
silenced, and `default` restores the whole function. It exists because the objective cannot be *measured*
through a function that also pays for exploration — a run can score well without ever leaving floor 1, and
that is indistinguishable from a run that learned to descend.

The profile travels the wire rather than being re-read by each process, so a trainer and its workers
cannot disagree about what a run was worth, and it is recorded in the replay header, so a recording
written under it verifies under it. `rewardcheck` asserts the property in both directions: that
`depth_only` pays nothing outside the objective over a run that provably fires 80 incidental events, and
that `default` still pays all 30 terms.

**Measured, and it stays a diagnostic.** 30 generations at 16 workers under `depth_only`: `bestDepth 2` in
**5 of 31 generations, all before generation 12**, **100% of episodes dead from generation 4**, mean score
improved by 4,460 and mean turns fell from 795 to about 300. The policy learned to **die more cheaply**,
which is arithmetic rather than mystery - with nothing but the objective paying, dying at turn 300 costs
-2,800 against a stall's -3,120, so death is the cheapest outcome a policy that cannot yet descend can
reach. Under `default` the same hardware put `bestDepth 2` in 104 of 150 generations. See `issues.md` 20.

**Measured, and honestly: survival improved and depth did not.** A 12-generation run moved deaths
100% → 19% and mean turns 377 → 1335, and `bestDepth 2` appeared once in a smoke test. Depth does not
trend yet; a 40-generation run is the measurement that would settle it. See [`TODO.md`](TODO.md) §1.8.

**Nothing has won a run.** The milestone from `research.md:40` is beating Goo on depth 5.

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
| Level pipeline, floor transitions, chasm falls | Verified - descending and ascending, in both directions, with the hero's landing act (`transitioncheck`, 9 cases). **A chasm fall was being dropped entirely** - it is the one transition that carries no `LevelTransition`, and `handleTransition` tested for one first, so `Transition.FALL` and `handleFall()` were unreachable and a hero put into a chasm stayed where he was. **And the agent could not choose to fall at all**: the game's own "jump?" prompt gated its answer on a clock only a rendered frame loop advances, and the environment threw the answer away. `fallcheck` is a gate, 6 cases, and `dialogcheck` is a gate, 3 more |
| Action space, action masking, menus, targeting | Verified |
| Observation encoder (spatial planes + inventory + hero scalars) | Verified, and the inventory is the whole of it - `env.max_slots` is **96**, the game's own ceiling (a 20-slot backpack plus four 19-slot bags). It was 32, which silently truncated two thirds of what the hero can carry; the truncation was invisible rather than refused, because the head is a fixed-width window and everything past it simply was not in the observation. This widens the network's input, so it invalidates checkpoints trained at 32 |
| Replay record / re-verify | Verified exact - 4/4 fresh processes, identical score, and a 32-recording sweep in one process. `verify` also resolves a recorded slot index against its recorded quickslot bindings, so it and the viewer cannot disagree about a recording and both be right, and it now compares the gameplay RNG draw count and fingerprint each step carries **before** it compares position, so a divergence says whether the two sides had already parted in the generator. `env.record_rng_fingerprint` |
| **A recording the trainer writes is watchable** | **Verified end to end, repeatedly** - the trainer pinned a step's bindings *after* the step, so a `DROP` named a different item on playback; `verify` skipped the comparison when the field was empty and reported nine unplayable recordings as `[OK]`; `viewcheck` scored a pass for any halt that was not a divergence. All fixed. **The nine remain unwatchable and are not kept**: they were written before the wire carried the field, nothing can recover data that was never written, and keeping them made the picker nine dead ends out of ten. `trainer-replays/` is a gitignored local directory, empty in the repository |
| **The catalog was wrong about which files play** | **Fixed** - the trainer resolved its default replay directory to `superintelligence/./trainer-replays` and the catalog's own default spelled the same directory without the `.`, so every trainer recording was scanned twice and **the duplication decided the `not watchable` marker**: the listing called 1 of 9 unwatchable, the other 8 clean. Deduplication is now by canonical path, gated by a case that spells one directory two ways. `replaycheck`, 19 cases |
| **A run's recordings are its own group** | **Done** - `--replay-group <id>` writes `trainer-replays/<id>/`, stamps `group=` into every header and gives the catalogue a `GROUP <ID>` section. The group is read from the **header**, not the folder, so a moved recording still says which run wrote it; the scan now walks subdirectories, which is the half that matters, because without it a grouped run writes into a directory the picker reads as empty. `rejected/` is the one directory skipped - it holds what a rebuild could not reproduce. `train.ps1 -Name` supplies it |
| **A file name did not resolve** | **Fixed** - the listing refused a bare seed several runs had recorded and said "pass a full path"; it now says type the file name, and that works too. `byName` searched the seed-deduplicated list, and a file is not a seed, so `IPW-QMA-AFY-r3.dat` answered *no such recording*. Both the search list and the footer are gated |
| **A thrown item does what it lands on** | **Verified** - `Level.drop` pressed the landing cell only behind a `scene() instanceof GameScene` test no headless run can satisfy, so every throw landed inert: an armed `WornDartTrap` was still armed after a `Food` was dropped on it on four seeds out of four. `Level.drop` is the only chokepoint - `Item.onThrow` and `Item.doDrop` both call it - so the fix is one added term and the scene test survives. A dropped item now fires a trap and tramples grass, level generation still presses nothing, `presscheck` is a gate with 3 cases, and `duelist-throw.replay` is the recording that carries a recorded press through both environments. `CHANGELOG.md` |
| Rendered viewer reproduces a recording | **19 of 20** - `:desktop:viewcheck` requires every step to have played rather than merely "did not diverge", and the one that fails is `duelist-death-last.replay`, which is `issues.md` 26: the viewer applies 1 HP the recording does not contain, at step 95. **Narrowed and not closed** - the viewer records cells now, and all 1,003 of them are identical on both sides at every step around the divergence, so it is neither the terrain, a trap, a plant, a heap, a transition, the fog, the visited set nor a mob, and the hero's own buffs are identical too. Evidence in `out/evidence/issue-26/`. The other nineteen play every step |
| Configuration externalised | Done - properties file plus `SPD_*` environment variables, flags overriding both; `configcheck` is a gate, **14 cases and 87 keys**. Every documented key is compared against its compiled default *and* proven to change the setting it names; an unmapped key is a failure, not a skip, and `.env.example` must name every one of them with the compiled default's value. **The trainer JVM's own heap and GC pause come from `SPD_WORKER_HEAP_MIN`, `SPD_WORKER_HEAP_MAX` and `SPD_WORKER_GC_PAUSE_MS`** rather than literals in `build.gradle`, which was the one file deciding a setting without being one |
| A run inherits nothing from the previous one | Done - `RunState` clears the armed aim, the open dialog, the pending item and a dead hero's remains, before level generation - and the viewer does the same at both ends of a playback, so watching a recording no longer leaves `bones.dat` in the player's profile |
| Gradient check vs central differences | Verified - `gradcheck` passes, and fails when a derivative is removed |
| Zero compiler warnings | **Done** - `javaclint` compiles the module with `-Xlint:all -Werror` and is **in `gates`**. It was reporting six: a `this` escape in `PPO`'s field initialiser, a possibly-lossy `double`-to-`int` compound assignment in `ReplayIO.verify` that was accumulating the per-step reward into an `int` **nothing ever read**, two static members reached through an instance expression, a redundant cast, and the deprecated `Graphics.getRawDeltaTime`. `ShardedUpdate` now takes the `PPO` per call rather than storing one |
| Update cost measurement | Measured - `updatecost`, 11.3 ms/sample, projects across sample rates |
| Diagnostics dashboard (colour-coded floors, graphs) | Console only; live, with a per-generation history behind it |
| Per-generation metrics history (CSV, end-of-run trend) | Done - `metrics.csv` per generation, `diag.Graph` at end of run |
| PPO clip fraction (`clip=`) | Fixed - was reporting `P(\|N(0,1)\|>0.2)`=0.8415; now the real ratio-clip fraction, measured 0.047-0.737 |
| Reward model, per-term ledger, curriculum fade | Partial - `KILL` and `SNEAK_ATTACK` are emitted from the engine's own counters; `CURSE_REMOVED` and `EQUIP_TOO_STRONG` are wired and mutation-tested. **`CRAFTED` and `CRAFTED_MEAT_PIE` are now emitted**, and `MENU_NOOP` is the one term left unwired for a measured reason: `env.AlchemyAction` brews over the game's own `Recipe.findRecipes` so `AlchemyScene` and its renderer are not on the path, and `modecheck` measures that every sub-mode action either advances the clock or leaves the mode, so `MENU_NOOP`'s state does not exist. The curriculum is wired and measured: `SPDEnv.onFloorTransition` calls `Curriculum.observe(depth)` and pushes the result into the ledger, `curriculumcheck` measures **0.6064 at depth 6** against **1.0 at depth 3** and **0.0 from depth 10**, and removing the `observe` call fails two of its five cases. The fade **starts at depth 3 and no run has passed floor 2**, so the scale has been 1.0 on every episode so far - that is a statement about the agent, not about the wiring |
| Episodes can be had at all | Done - `REST` was a one-way door (0 idle-guard stalls, 36 pipeline stalls), headless had no texture and every `TextureFilm` dereferenced it on hunger damage, and every death blew the stack so `DEATH` was unreachable. `rewardcheck` cases 7 and 8. |
| Terminal rewards are honest | Done, and ordered - `STALLED` was -5.0 (ending an episode beat losing and the agent learned to), then `-deathPenalty` (quitting and losing became one decision with two labels). Now stall -150 < death -100 < turn limit -25 < a cleared floor +25, asserted as an ordering rather than as constants. `rewardcheck` is a gate, 29 cases |
| **Killing pays; approaching pays more** | Done - `KILL` was declared and never emitted, so fighting was worth nothing while being hit cost something. `reward.kill_reward` 150.0, and `reward.sneak_attack_reward` **300.0** above it, read from `Statistics.sneakAttacks` - the one combat outcome that costs no health, because `Mob.surprisedBy` skips the defender's defence outright |
| **A free action is not free** | Done - equipping and unequipping advance no engine time at all, and a policy alternated them for 120 consecutive steps on one `Rapier`. `reward.no_progress_penalty` 0.05, **priced rather than prevented**, so the stall guard still ends recordings where it did and the corpus stays valid |
| **A free action cannot be repeated for ever** | Done - `OPEN_INVENTORY`, `USE` and `DROP` are the only entries into a sub-mode and all three cost nothing, and `CANCEL` is the only way out of two of them, so the pair is a loop that advances no engine time at all while charging every step against `env.free_step_budget`. `REST` was the worst of it, at 323 free steps in 326. After a step that leaves a sub-mode without moving the clock, the next `WORLD` mask refuses all three **for exactly one step** - the actions stay legal, the agent just cannot chain them without spending a turn in between. Measured over 40 uniform-policy episodes: `meanTurns` 626.8 -> **800.7**, ended `STALLED` 21 of 40 -> **15 of 40**, `REST` free steps 323 of 326 -> **1**. `freeactioncheck` is the gate, 7 cases, mutation-tested both ways. `CHANGELOG.md` |
| **A free action is not always offered** | Done - an equip toggle is a legal use and spends no engine time at all, so the mask that offered `USE` whenever the hero carried anything was satisfied by a backpack holding one unequipped weapon. `env.sub_mode_progress_only` masks `USE` when every carried item is a bare equip toggle and `OPEN_INVENTORY` when there is nothing to use at all; `DROP` is untouched. Measured over 40 uniform-policy episodes at fixed seeds, same code, only the flag differing: ended `STALLED` **14 of 40 → 2 of 40**, `deepestDepth` 1 → **2**. `stallcausecheck` proves both halves - `FREE_STEP` is reachable with the flag off and unreachable with it on. Evidence: `out/evidence/stallbreakdown-sub-mode-mask-{tight,loose}.txt` |
| **A two-turn action is not a one-turn price** | Fixed - `TURN_COST` was charged per *step*, and `Hero.search` costs **two** engine turns, so a search was half price and the agent spent 189 of 940 steps and **half the run's engine time** on an action that finds nothing after the first sweep. Charged by the engine clock now, floored at one turn so idling cannot become free. `SEARCH` fell to 39 steps / 78 turns on the same seed |
| **Drops are charged** | Fixed - the pickup/drop pair had been correct since `issues.md` (closed; see CHANGELOG.md), but **`IDENTIFIED` was still one-sided**: it paid +0.5 for carrying an already-identified item and refunded nothing when dropped, so `DROP`-then-`INTERACT` was a repeatable +0.5. Measured on `IPW-QMA-AFY`: `IDENTIFIED +27.50` against an inventory round trip that was itself **net -5.00**. Now a signed diff, and the gate checks that no *other* term pays for the round trip |
| **A turn costs something** | Fixed - `reward.turn_cost` 0.002 → **1.0**, with every other term rescaled onto the same footing. A floor's 1500-turn budget had cost **-3.00** against a **+25** descent, so idling was nearly free. Same seed before and after: 1508 steps / deepest 1 → **392 steps / deepest 2**. That run was the first to reach depth 2 in this project, and depth 2 has been reached in `bestDepth` in 9 of 30 generations since - which, as above, is not yet a result |
| **The hero levels but cannot spend the point** | **Done** - `TALENT_UPGRADE` at `reward.talent_reward` 400.0, wired from `Hero.talentPointsSpent` and gated. Spending is UI-only in the game (`TalentButton` -> `TalentsPane` -> `WndHero`), so there was no headless action at all and the point could never be spent. `Action.TALENT` opens `EnvMode.TALENT`, where the slot head enumerates `env.TalentPicker.available(hero)` in the game's own order, and `freeactioncheck` drives the whole path through `SPDEnv.step` and asserts the reward pays. The first point arrives at **level 2**: two rats on floor 1 |
| Actions report what happened | Done - `WAIT`/`REST`/`SEARCH` were recorded as `INVALID_ACTION`, and the term carried no weight, so nothing observed it |
| CNN + LSTM network, PPO agent | **The recurrent gradient is no longer truncated at length one.** Every sampled step used to be backpropagated in isolation with dPrev and dCell cleared, so nothing crossed a timestep and LSTM.W_hh received no gradient at all for holding information forward. The update now groups the batch into contiguous runs of up to `rl.chunk_length` (16) steps and backpropagates each run in reverse, and the sampler that decides which steps reach the trainer now draws whole blocks of that length rather than an independent draw per step, so a run of the length the setting names actually occurs. Contiguity travels with the sample - the worker stamps an episode id and a step index, and a gap or a change of episode starts a new run. Measured: a two-step chunk's LSTM gradient differs from the sum of its steps' by **63.7%**, and reverting the accumulation drops that to **0.00000**. `bpttcheck` is the gate, 7 cases, and the decisive one is constructed so the answer is forced. `samplercheck` holds the sampling half: at `rl.sample_rate=0.05` over 20,000 steps it retains 1,113 steps in 70 contiguous runs with the longest at 16 and no interior gaps, against a Bernoulli sampler whose runs are all of length one |
| **The critic is asked for a number it can reach** | Raw returns ran to -3000 for a stall and +1800 for a descent, `valueLoss` measured 12,432-31,323, and the gradient that produced flowed into the trunk the actor shares - where global clipping squashed the policy's update. Value targets are now normalised by a running scale, the residual is clipped and the loss is Huber. A three-generation run reports `value`=0.62, 1.06, 0.56 where the old figures were four orders of magnitude larger |
| **A descent is reachable** | RewardTerm.STAIR_POTENTIAL pays gamma*Phi(s') - Phi(s) with Phi the negative breadth-first distance to this floor's exit. Floor 1 is several hundred walkable tiles and a random walk onto the exit succeeds about 0.1% of the time, so across 5,360 episodes meanDepth was 1.001 - a descent was not merely unrewarded but unreachable. The term telescopes over an episode, so a round trip pays nothing however many times it is repeated: measured, 4 steps closing the gap pay **+8.12**, 4 steps opening it cost **-5.82**, and a round trip nets **2.30**. `reward.stair_potential_weight` = 0 turns it off |
| **The hero knows where the stairs were** | obs.GridChannel.EXIT_BEACON lights the floor's stairs once the hero has stood where they can be seen. TRANSITION only lights while they are in view, so a hero that walked past and away had no channel carrying the fact - exactly what the agent needs and least often has in front of it, and exactly what the recurrent gradient now has to carry. Not lit from generation: that would hand over the objective for free |
| **Every setting is a setting** | configcheck is 14 cases and 87 keys, and its last case reads `.env.example`: every documented key must appear as an SPD_ variable and every value in it must match the compiled default. That case found `SPD_RL_UPDATE_THREADS=4` against a compiled default of 1 and seven missing keys - the "example describes a run the module does not run" fault the changelog already records once. Sixty-nine values that were Java literals are now documented settings |
- **A sub-mode entry is not always offered.** `env.sub_mode_progress_only` masks `USE` when every carried item is a bare equip toggle and `OPEN_INVENTORY` when there is nothing to use at all. Measured on 40 uniform-policy episodes at fixed seeds: `STALLED` **14 of 40 -> 2 of 40**, `deepestDepth` 1 -> 2. `stallcausecheck` proves both halves.
| CNN + LSTM network, PPO agent | **1,403,316 parameters**, down from 3,583,827: a second strided convolution takes the trunk's grid input from 12,696 features to 3,200, so the single 12,696x256 matrix that held 3.25M of the network is 1.17M. `updatecost`: forward 4.95 ms, forward+backward 11.07 ms per sample. **PPO runs on real worker data** |
| Weight save / load, resume a run | Done - `--save` / `--resume` / `--checkpoint-every`, atomic writes, Adam moments and step count included, **and the seed schedule**: the pool seed, the schedule seed, the pool size and the number of draws made all travel in `Checkpoint.Meta`, so a resumed run continues the sequence instead of replaying generation 0's. `checkpointcheck` is a gate, 9 cases - one of them asserts that a flag with no value after it is **refused naming itself**, because every flag reads `args[i + 1]` and `train.ps1 -Resume` used to append a bare `--resume`. `seedcheck` is a gate, 5 cases |
| Bad checkpoint handling | Done - foreign, truncated, trailing-byte and wrong-config files all refused, the last naming the field |
| Parallel minibatch update | Done - `--update-threads N`, measured 3.29x at 4 threads, 3.47x at 8. `parallelcheck` is a gate |
| Cross-sample gradient leak | Fixed - `dCell` was not cleared, so every gradient depended on the previous sample |
| Value head range | Fixed - was tanh-bounded to [-1,+1] against a death penalty of 100 |
| LSTM state with each transition | Fixed - the replay ran under an unrelated sample's state |
| Trained anything yet | **No.** Never left floor 1; milestone is depth 5 (`research.md:40`). **The update path was itself faulty** - all N update threads shared one `Network` - and that is fixed and gated as of `CHANGELOG.md`; no run has been measured since |
| Parallel worker processes, seed schedule | Running; exercised to 8 workers x 25 generations |
| The policy push survives a Windows pipe | **Gated** - a whole 14.3 MB blob in one `write` killed runs at generation ~22 with `Insufficient system resources exist to complete the requested service`. The write is chunked (`train.push_chunk_bytes`), retried (`trainer.push_retries`), and `pushcheck` holds the loop's contract: 6 cases, mutation-tested both ways. The gate asserts the write loop, not a spawned worker - a worker cannot be spawned inside a gate's JVM, but it never needed to be |
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

# every correctness gate, one invocation, ~23 seconds
./gradlew :superintelligence:gates

# regenerate the DROP-in-SLOT fixture the viewer gates play. It verifies before writing, so a
# fixture that no longer plays cannot be committed in place of one that does.
./gradlew :superintelligence:dropfixture --args="dropfixture --save test-replays/duelist-drop.replay"

# regenerate the throw-that-presses fixture, the same way, and refusing to write a run whose
# throw did not press anything.
./gradlew :superintelligence:pressfixture --args="pressfixture --save test-replays/duelist-throw.replay"

# train

./gradlew :superintelligence:train --args="--workers 8 --generations 200"

# train, and file this run's recordings under a group so the catalogue lists it alone.
# Letters, digits, '.', '_' and '-', up to 64; anything else is refused rather than escaped.
./gradlew :superintelligence:train --args="--workers 8 --replay-group depth-only-60"
```

**`superintelligence/train.ps1` is the workflow**: `./train.ps1`, `./train.ps1 -Profile depth_only
-Generations 60 -Name depth-only-60`, `./train.ps1 -Resume`. It names the artefacts, prints the gradle
command it is about to run, and passes `-Name` as the run's replay group.

**Thirty-three gates, one invocation.** Each has had the thing it guards deleted, and each has been
required to fail - a check that cannot fail is not a check. Two reach faults the rest structurally
cannot: `resetcheck` proves a reset is a function of its arguments, which needs two episodes in one
process because with one there is nothing to leak from; and `paritycheck` proves a *recording* survives
a write, a read and a second episode, which is the only gate that lets the hero die repeatedly. That is
where process-spanning game state such as a dead hero's remains becomes visible - and it is how two of
32 recordings were caught not reproducing. A third, `transitioncheck`, covers the path that was
unreachable until descending worked: a hero's first action on a new floor used to be eaten by a mob
waking up, and every recording in the corpus that descended described a run that could not happen. A
fourth, `fallcheck`, is the same argument one floor further: **a chasm fall was never serviced at
all**, because it is the only transition that carries no `LevelTransition` and the pipeline tested for
one first - so `Transition.FALL` and `handleFall()` were unreachable code, and nothing in the project
had ever put a hero into a chasm to find out. A fifth, `dialogcheck`, covers the half that made the
chasm *unreachable*: the environment was handed the game's own "jump?" prompt and then had the answer
thrown away, so the agent could be told yes by the game and still never cross. A sixth, `presscheck`,
covers a third fault on the same path with the same cause: `Level.drop` pressed the cell an item lands
on only behind a scene test no headless run can satisfy, so every throw was inert and nothing in the
project had ever thrown an item at a trap. A seventh, `freeactioncheck`, covers the arithmetic that
ended 100% of episodes: a rest that spent no engine time, and a menu loop that could be repeated
without the world moving. An eighth, `pushcheck`, is the first to reach the trainer's side of a worker
pipe at all: a whole policy blob written in one call killed runs at generation ~22 with
`Insufficient system resources exist to complete the requested service`, and the mechanism that fixed it
was a chunked, retried write with no gate over it - because a gate cannot spawn a worker in another
process, but it can assert the write loop's own contract. A ninth, `curriculumcheck`, asks whether the
shaping fade does anything, and measures it: **1.0 to depth 3, 0.0 from depth 10, 0.6064 at depth 6.**
The thirtieth, `javaclint`, compiles the module with `-Xlint:all -Werror`; it was a task to remember
rather than a gate to fail, and six warnings lived in the tree for as long as that was true. Three more
have arrived since. **`samplercheck`** covers the pair that decides whether anything learns at all:
at `rl.sample_rate=0.05`, the retained steps must group into runs as long as `rl.chunk_length`
says, and a grid flattened straight into the dense trunk fails it. **`seedcheck`** covers a resumed
run: a draw must be a function of its index, so a pool resumed after 120 draws continues at 121
rather than restarting at generation 0. **`doccheck`** covers this file: no markdown under the module
may carry a control byte — a NUL makes `grep` read it as binary and the pre-commit hook's badge check
then skips it — every gate must be reachable, and this paragraph's count must be the real one.

**A gate that passes because the thing it would catch is absent is the same failure as a gate that
cannot fail**, and the corpus was one for a long time: 17 recordings, 310 SLOT-mode steps, and not one
`DROP` in any of them. `collectcheck` and `verifycheck` now cover the wiring that made that matter -
whether a rebinding step is recorded against the world it was chosen in, and whether a recording whose
bindings cannot be resolved is refused rather than verified. And a fault confined to the trainer cannot
be caught by the rendered gate at all, because the viewer never had it - `viewcheck` stays green when
`Level.drop`'s headless half is reverted, and `duelist-throw.replay` is what goes red. Full table in
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
optimiser-step numbering so the two runs' `metrics.csv` rows do not collide. **The seed schedule
continues too**: a checkpoint carries the pool seed, the schedule seed and the number of draws made,
so a resumed run opens on the seeds the uninterrupted run would have reached at that point rather
than replaying generation 0's. `seedcheck` is the gate, 5 cases, and the decisive one resumes a
pool mid-sequence and compares the next 80 draws against the uninterrupted run's.

```sh
# 50 generations, then 50 more
./gradlew :superintelligence:train --args="--generations 50"
./gradlew :superintelligence:train --args="--generations 50 --resume superintelligence/out/run/weights.bin"
```

`gradle :superintelligence:weightsdiff --args="<file>"` reports how far a checkpoint is from a freshly
initialised policy — mean and max absolute weight difference. `checkpointcheck` asserts that a policy
survives disk exactly, Adam moments included, and that bad checkpoints are refused.

## Layout

| Package | Responsibility |
| --- | --- |
| `headless` | libGDX/noosa shims: silent audio, classpath file resolution, inert `Game`, headless `Graphics`, sprite that resolves animations instantly |
| `env` | `SPDEnv` (reset/step), `ActionMapper` (action injection and masking), `LevelPipeline` (run start, floor transitions, actor scheduling), `RunState` (what a new run must not inherit), `EnvConfigBinder` (settings from file and environment), `WindowBridge`, `SlotAction`, `AlchemyAction` (brewing, without `AlchemyScene`) |
| `obs` | `ObservationEncoder`, spatial channel definitions, fixed inventory vector, hero scalars |
| `reward` | `RewardModel` (state diffing), `RewardTerm`/`RewardLedger` (per-term, per-floor breakdown), `Curriculum` |
| `policy` | `ScriptedPolicy`, the network-free heuristic used for smoke tests and worker bootstrap |
| `rl` | `Network` (two convolutions + LSTM + heads), `PPO` (the learner), `ShardedUpdate` (the parallel minibatch and its gradient reduction), `Policy` (masking, losses, GAE), `Transition`, `EpisodeCollector` (plays an episode, computes its advantages), `ContiguousChunkSampler` (retains whole chunks rather than independent steps), `EpisodeRecord` (one episode's scalars + sampled observations) |
| `train` | `Trainer` (generation loop), `TrainerWorkers` (everything crossing a worker pipe), `WorkerPool` (processes, pipes, stall watchdog), `Protocol` (wire format), `TransitionCodec`, `TrainOptions`, `PpoHyperparameters`, `Checkpoint` (save/resume), `MetricsHistory` (per-generation CSV + end-of-run trend), `Episode`, `Worker` (worker side), `SeedPool` (generalisation schedule) |
| `replay` | `Replay`, `ReplayRecorder`, `ReplayIO` (write, read, verify), `Quickslots` (the one place a recorded slot index is resolved against its bindings), `DropFixture` and `PressFixture` (the DROP-in-SLOT and throw-that-presses recordings the viewer gates play), `ReplayCatalog`, `RngTrace`, `WorldSnapshot`, `WorldDiff` |
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

[`superintelligence.properties`](superintelligence.properties) documents all 84 keys against their
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

The recurrent gradient is **not** truncated at length one, and an earlier version of this section said
it was. The update groups the batch into contiguous runs of up to `rl.chunk_length` (16) steps and
backpropagates each run in reverse without clearing between steps, and the sampler now draws whole
blocks of that length so a run of the full length actually occurs. What remains an approximation is the
length of that window: a 16-step run is not a whole episode, so an advantage whose support runs past 16
steps - and `1/(1-gamma*lambda)` is about 17 - is cut at the boundary rather than carried to it. The
trunk, the heads and the critic get exact gradients; the LSTM gets an exact gradient within a step and
across each run of up to 16, and none beyond that.

Two other approximations are deliberate and worth naming. The observation grid is a fixed
`env.grid_width` x `env.grid_height` window centred on the hero, so a floor larger than that window is
partly outside it - measured across 1,040 floors, 419 exceed 48 tiles on at least one side. And the
reward terms are diffs of observable state rather than hooks in the game's own code, which means a
potion healed through a wand and a scroll healed through an upgrade both read as "hero gained health".

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
- `GameScene.headlessGameplay()` - declares that this process is simulating gameplay with the renderer
  bypassed. `Level.drop`'s press is guarded on a `GameScene` being the current scene, so a headless
  process never pressed the cell a thrown or dropped item landed on and every throw was inert. The flag
  is set by `HeadlessGame.install()` and by nothing else, so a game with a renderer never reads it and
  the guard's answer there is unchanged. It is the one entry below that widens a condition rather than
  tolerating a null.

The last one is not cosmetic bookkeeping. **Four presentation draws on the gameplay stream cost the
viewer 16 generator values per run that a headless run never spent**, which is why a recording could
verify exactly headlessly and diverge in the viewer. See `ENGINE-CHANGES.md` for the full inventory,
what each change cost, and what is still unfixed.
