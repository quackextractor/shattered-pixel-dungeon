# Superintelligence TODO

Open work only. Completed items are in git history and `CHANGELOG.md`; findings and the
measurements behind them are in `issues.md`.

**Old section numbers.** This file was renumbered, and older documents still cite the old
numbers. Where they went:

| old | now |
| --- | --- |
| 0.1, 0.2, 0.3, 0.5, 0.6, 0.9, 0.11, 0.12 | fixed; see `CHANGELOG.md` |
| 0.7 rare viewer divergence, not reproduced | 7, below |
| 0.8 inventory narrower than the game's | 7, below |
| 0.10 `duelist-mid` stalls after a zero-cost loop | `issues.md` 13 |
| 0.0.0-0.0.3 viewer faults | fixed; see `CHANGELOG.md` |
| 1.1, 1.2, 1.5, 1.9, 1.12, 1.13, 1.15 | fixed; see `CHANGELOG.md` |
| 1.3, 1.4, 1.8 | 1, below |
| 1.6 reward function that pays for exploring | 2, below |
| 1.11 talent point cannot be spent | 7, below |
| 2.1 the agent learned to stall | fixed; see `CHANGELOG.md` |
| D1-D8 deliberate deviations | `docs.md` |

## 1. The critical gap: nothing has learned anything

The goal is to descend several floors, or win. Neither has happened.

**Measure before believing anything.** `bestDepth` is a coin flip between 1 and 2 on an
identical config and fixed seed (`issues.md` 27), so every "reached depth 2" claim in this
repository before 2026-10-14 was a coin landing. `meanScore` is the only metric that moves;
it varies +/-16 across repeats of a 15-generation run, and a change must clear that band to
mean anything.

**Where it stands after the free-step budget fix** (`issues.md` 29, 30): episodes run ~670
turns instead of ~122, the stall rate is 79% instead of 99%, and `bestDepth` reaches 2 in 7
of 15 generations against 2 of 67 before. That is the pre-regression regime (104 of 150 generations, in git history), so it is a regression recovered rather than progress. No run has yet been
measured against the band to say whether it is now improving.

- [x] Measure a 30-generation run against the noise floor and state whether `meanScore`
      clears it. Nothing longer than ~21 generations survives `issues.md` 28's push crash
      often enough to trust, so segment and resume. **Measured, and it does not clear it.**
      30 generations, 8 workers x 4 episodes, 4 update threads, seed 20261016,
      `train.random_seed_rate=0`, every other default unchanged, 12 min 41 s, completed
      where `issues.md` 28's push killed runs at ~22.

      | | generations 0-14 | generations 15-29 |
      | --- | --- | --- |
      | `meanScore` mean | **-3,535.7** | **-3,534.1** |
      | `meanScore` sd | 137.5 | 98.5 |
      | `meanScore` range | **567.2** | **375.4** |

      **The movement is +1.6 points against a per-generation sd of 98 to 138.** That is not a
      trend; it is noise, and it is two orders of magnitude smaller than the noise. `bestDepth 2`
      in **9 of 30** generations and `meanDepth` peaks at 1.062. `valueLoss` is O(1)
      (0.14-0.90) and `clipFraction` falls 0.552 -> ~0.05 while entropy climbs 3.66 -> ~4.5, so
      the update is doing something; the objective is not moving.

      The band `issues.md` 27 measured (+/-16 over 15 generations) came from a *different* config
      and does not transfer. What this run establishes for *this* config is the sd above, and it
      is 6 to 8 times larger than the band the repository had been quoting. **Any future
      comparison has to clear ~100 points over 15 generations, not 16.**

      Evidence: `out/evidence/train-noise-floor-30.log`, `out/evidence/noise-floor-30.csv`.
      `issues.md` 37 - one of the two recordings this run wrote does not reproduce.
- [ ] Confirm the free-step budget is now the binding constraint rather than `stallLimit`.
      250 was chosen from a 10-generation run; the guard behind it was never re-measured.

## 2. Reward signals

- [ ] **Terms that never fire in a run.** `KILL`, `GOLD_GAIN` and `ITEM_PICKUP` have never been
      observed paying in a *run*. `KILL` is wired and gated - `rewardcheck` places a
      level-appropriate mob, fights it and requires the payout - so the question is whether an
      untrained policy ever gets there, not whether the term can move.
- [ ] **Exploration is not separable from descent.** The reward pays for seeing new tiles as
      well as for going deeper, so `bestDepth` cannot measure exploration even where it does
      measure something. `reward.profile=depth_only` exists for this and has not been run
      since the budget change.
- [x] ~~**The curriculum is inert.**~~ **Closed, and it was never true.**
      `SPDEnv.onFloorTransition` calls `curriculum.observe(depth)` and pushes the result into
      the ledger, and `curriculumcheck` measures a real run reaching depth 6 at a shaping scale
      of 0.6064 in both. Removing the `observe` call fails two of its five cases. What is true
      is narrower and is worth stating: **the fade starts at depth 3 and no run in this
      project's history has passed floor 2**, so the scale has been 1.0 on every episode and
      the shaping terms have never actually been faded. That is a statement about the agent, not
      about the wiring. See section 5.

## 3. Missing subsystems

| # | Task | Document | Size |
| --- | --- | --- | --- |
| 3.1 | libGDX trainer UI in the `desktop` module | research.md:16, docs.md:10 | L |
| 3.3 | Live play with real-time reward/term monitoring | docs.md:10 | M |
| 3.4 | Type totals in the dashboard | docs.md:41 | S |

3.2, the desktop replay viewer, is done.

## 4. research.md sections not addressed

| # | Section | Status |
| --- | --- | --- |
| 4.1 | Garbage collection and memory pooling | Unaddressed. The documents ask for an audit of the core game loop and pooling of volatile objects; not started. |
| 4.2 | 1,000 simultaneous simulations | Never run at scale. The pool sizes to the machine at 20 workers. |
- **A depth-2 run, verified.** 20 generations, 8 workers, 4 update threads, seed 20261016, every
      default unchanged: estDepth 2 in **8 of 20 generations**, meanDepth 1.05, and
      	rainer-replays/bptt-stairs/CLL-ZXD-MTZ.dat reproduces exactly. One run, and it does
      not clear the noise floor, so it is a direction and not a result. issues.md 36.
| 4.3 | IMPALA as the alternative learner | Not built. Listed as an option, not a plan. |

## 5. Known rough edges

Small things that are wrong but not blocking.

- **The shaping fade has never actually faded anything.** `Curriculum.observe()` is called on every
  floor change and `curriculumcheck` measures it moving - **0.6064 at depth 6** - so the wiring is
  live and the gate forces it to stay that way. What is inert is the *effect*: the fade starts at
  depth 3, and no run in this project's history has passed floor 2, so every episode has priced
  every shaping term at 1.0. The fix, if there is one, is an agent that descends; retuning the fade
  would change what is being faded rather than make anything fade.
- **Three reward terms had no emission site; two still do, and both for a measured reason.**
  `CRAFTED`, `CRAFTED_MEAT_PIE` and `MENU_NOOP` are declared and unwired. Brewing is
  `AlchemyScene.craftItem`, which needs a renderer; and a gate measured that **every** sub-mode
  action either advances the engine clock or leaves the sub-mode, so the state `MENU_NOOP` names
  does not exist. An emission site that can never fire is `KILL`'s original sin, so the case is a
  gate on the *absence* instead: it goes red the day a sub-mode action does nothing.
  `CURSE_REMOVED` and `EQUIP_TOO_STRONG` are wired, mutation-tested, and pay 1.50 and -2.00.
  What is still true is that **their weights are Java literals**, like `CURSE_EQUIPPED` and
  `CURED_DEBUFF` beside them, rather than keys in `superintelligence.properties`.
- **Five files were over the project's own 500-line limit.** **Closed.** Every file in
  `:superintelligence` is under 600 logical lines. See section 8.

## 6. Stale references in the source documents

Worth knowing before `research.md` or `docs.md` is treated as a specification. They name
classes that have moved packages in v4.0.1, including `FogOfWar.java` and
`ShadowCaster.java`, which exist but not where the documents say.

Deliberate design departures were recorded as `TODO.md` D1-D8 and are now in
`docs.md`; `issues.md` 7 still cites D7, which is "targeting and inventory as separate
heads".

## 7. Open engine and harness faults

- [ ] **The agent's inventory is narrower than the game's.** **Closed**: `env.max_slots` is 96,
      the game's own ceiling, against 32. `issues.md` 34.
- [x] **`REST` advances no engine time.** Measured at 323 of 326 rests. `ActionMapper.java`
      says it needs the drain's rest handling decided first, and that it is not a one-line
      fix. Undecided, and it is a design call rather than an oversight.
      **Closed**: it is one line, and the reason it was not is that `Actor.now()` is set by
      the scheduler rather than by the hero - see `issues.md` 32 for the measurement that
      showed `spendConstant` alone does nothing.
- [x] **The talent point can never be spent.** `talentReward` pays 400 and the level-up path
      works, but there is no action that spends the point. `rewardcheck` proves it pays.
      **Closed**: `Action.TALENT` and `EnvMode.TALENT` reach `Hero.upgradeTalent`, and
      `freeactioncheck` drives the whole path through `SPDEnv.step`. See section 8.
- [ ] **`issues.md` 26**, the viewer spending 1 HP the recording does not contain. Cells are
      the one channel the viewer does not record, so the damage source is still unlocated.
- [ ] **`issues.md` 28**, the policy push. Made chunked and retried, and **now gated** -
      `pushcheck`, 6 cases, mutation-tested both ways. A gate cannot spawn a worker, so the case
      holds the write loop's own contract rather than a spawned process's behaviour.
- [ ] **The viewer diagnostics are tested but not in `gates`.** **Closed**: `viewcheck` runs
      both, whether or not the corpus is green. `-Dspd.replayContinueOnDiverge` applies **100**
      steps to `duelist-death-last.replay` against **95** without it; `spd.hidden` reads as
      `false` without the property and `true` with it, checked without ever constructing a
      window. `-PspdHidden` is still not how you set the latter: it lands on the supervisor,
      and the children are what open the window.

## 8. Closed since the last cycle

Recorded so the next reader does not re-derive them. The measurements are in `issues.md`.

- **A free action could be repeated for ever, and a talent point could never be spent.**
      `REST` advanced no engine time, and `OPEN_INVENTORY` -> `CANCEL` was a loop that never
      moved the world while charging every step against `env.free_step_budget`. Both closed;
      `Action.TALENT` makes the talent point spendable. `freeactioncheck` is the gate.
      `issues.md` 32.
- **The recurrent gradient was truncated at length one.** No gradient crossed a timestep and
      `LSTM.W_hh` learned nothing. The update now backpropagates contiguous runs of up to 16
      steps. `bpttcheck` is the gate. `issues.md` 33.
- **The critic was asked to fit returns in the thousands,** and the gradient that produced
      was squashed by clipping on the trunk the actor shares. Targets are normalised and the
      loss is Huber. `issues.md` 33.
- **A descent was unreachable rather than unrewarded.** Potential-based shaping towards this
      floor's stairs, and a beacon channel that carries where the hero last saw them.
      `issues.md` 34.
- **The inventory was silently truncated at 32 of 96 slots.** `issues.md` 34.
- **Twenty-six settings were Java literals**, and `.env` was read by nothing. `issues.md` 35.
- **The spatial grid was a byte per cell for one bit of information,** which made it 90% of every
      transition and therefore of the buffer and the pipe. Bit-packed now: 50,688 bytes to 6,336,
      lossless, and gaecheck holds both the round trip and the ratio.
- **`INTERACT` could not name the cell it acted on.** The step's secondary index is now a
      direction; index 0 keeps its old meaning, so the corpus reproduces unchanged.
- **`Trainer.java` was over the project's own 500-line limit** at 1,021 lines, and the module
  carried a javadoc comment on almost every declaration. `train/TrainerMain` took the entry
  point and configuration loading (Trainer is 467), and `stripinlines --javadoc` removed
  13,366 comment lines across 114 files in one reviewable change. **The five diagnostic files
  that were still over are split too**: `diag/RewardCheck` 1,354 became a runner plus
  `RewardTerminalCheck` (352), `RewardDescentCheck` (260), `RewardCombatCheck` (307),
  `RewardShapingCheck` (275) and the shared `RewardFixtures` (208);
  `diag/ReplayCatalogCheck` 783 became a runner plus `ReplayCatalogListingCheck`,
  `ReplayCatalogGroupingCheck` and `ReplayCatalogFixture`; `diag/CollectCheck` 615 became a
  runner plus `CollectWireCheck` and `CollectTrace`; `diag/ConfigCheck` 542 became the cases
  plus `ConfigDefaults`; `Main` 506 became a dispatcher plus a `cli` package.
- **The shaping fade is wired, and the `shaping=` column is not a constant any more.** The
  fade itself was always live - `curriculumcheck` measures 0.6064 at depth 6 - but
  `Trainer.curriculumScale()` returned a literal `1f`, so the report showed a number the run
  had never used. It is now the mean over the generation's episodes of the scale each one
  priced its shaping terms at.
- **The trainer JVM's heap is read from `SPD_WORKER_HEAP_MIN` / `_MAX` / `SPD_WORKER_GC_PAUSE_MS`**
  instead of literals in `build.gradle`, which was the one file deciding a setting without being
  one. `configcheck` holds both directions.
- **`pushcheck`**, 6 cases, the first gate to reach the policy push. A gate cannot spawn a
  worker - `HeadlessServices.install` is process-wide - so the case is written against the
  write loop's own contract rather than against a spawned process.
- **The two viewer diagnostics are gated by `viewcheck`**, 2 checks: continue-on-divergence
  measures 100 steps against 95 on the one recording that diverges, and `spd.hidden` is read
  without constructing a window at all.
