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

- [ ] Measure a 30-generation run against the noise floor and state whether `meanScore`
      clears it. Nothing longer than ~21 generations survives `issues.md` 28's push crash
      often enough to trust, so segment and resume.
- [ ] Confirm the free-step budget is now the binding constraint rather than `stallLimit`.
      250 was chosen from a 10-generation run; the guard behind it was never re-measured.

## 2. Reward signals

- [ ] **Terms that never fire.** `KILL`, `GOLD_GAIN` and `ITEM_PICKUP` have never been
      observed paying. Either they cannot fire or the agent never reaches the state.
- [ ] **Exploration is not separable from descent.** The reward pays for seeing new tiles as
      well as for going deeper, so `bestDepth` cannot measure exploration even where it does
      measure something. `reward.profile=depth_only` exists for this and has not been run
      since the budget change.
- [ ] **The curriculum is inert.** `Curriculum.observe()` is never called, so `deepestSeen`
      stays 0 and `shapingScale()` returns 1 forever. A design question, not a bug - see
      section 5.

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
| 4.3 | IMPALA as the alternative learner | Not built. Listed as an option, not a plan. |

## 5. Known rough edges

Small things that are wrong but not blocking.

- **The reward curriculum is inert.** Written in full and applied to the ledger, but nothing
  calls `Curriculum.observe()`, so shaping stays at 1f and the dashboard prints a constant
  under `shaping=`.
- **Two javac lint warnings** in `superintelligence`, found by `gradle :superintelligence:javaclint`:
  an orphan javadoc in `Replay.java` and a possibly-lossy `double`-to-`int` cast in
  `ReplayIO.java:421`. Advisory only, so neither is fixed.

## 6. Stale references in the source documents

Worth knowing before `research.md` or `docs.md` is treated as a specification. They name
classes that have moved packages in v4.0.1, including `FogOfWar.java` and
`ShadowCaster.java`, which exist but not where the documents say.

Deliberate design departures were recorded as `TODO.md` D1-D8 and are now in
`docs.md`; `issues.md` 7 still cites D7, which is "targeting and inventory as separate
heads".

## 7. Open engine and harness faults

- [ ] **The agent's inventory is narrower than the game's.** `issues.md` 7 confirms the
      quoted ceiling of 96 slots is right and that bags are obtainable, but the env exposes
      fewer. Not closed, because the obvious fix is wrong: D7's claim that targeting and
      inventory share a head is what has to be revisited, not the slot count.
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
- [ ] **`issues.md` 28**, the policy push. Made chunked and retried, but with no gate: a
      worker spawned from a gate's JVM dies in `HeadlessServices.install`, so the case cannot
      be written as it stands.
- [ ] **The viewer diagnostics are tested but not gated.** `-Dspd.replayContinueOnDiverge`
      and `-PspdHidden` were verified by running them; neither is in `viewcheck`.

## 8. Closed since the last cycle

Recorded so the next reader does not re-derive them. The measurements are in `issues.md`.

- **A free action could be repeated for ever, and a talent point could never be spent.**
      `REST` advanced no engine time, and `OPEN_INVENTORY` -> `CANCEL` was a loop that never
      moved the world while charging every step against `env.free_step_budget`. Both closed;
      `Action.TALENT` makes the talent point spendable. `freeactioncheck` is the gate.
      `issues.md` 32.
