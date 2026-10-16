# Changelog - Superintelligence

All notable changes to the **Superintelligence module** are documented in this file.

This module is versioned independently of the game it wraps. The game is
[Shattered Pixel Dungeon](../README.md).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- **`RewardTerm.Type`, and a colour-coded type table in the run report.** `docs.md` asks the summary
  interface for "floor-by-floor point gains and losses, type totals/subtotals, and the overall run
  score"; the report printed floors and then thirty individual terms with nothing grouping them.
  Every term now carries one of eight types - `OBJECTIVE`, `EXPLORATION`, `COMBAT`, `PROGRESSION`,
  `ITEMS`, `SURVIVAL`, `COSTS`, `TERMINAL` - `RewardLedger` answers `typeTotals()` and `typeCounts()`,
  and `RunReport` prints a `type totals` block between the floor table and the term table with the
  event count and the subtotal in green above zero and red below. `rewardcheck` is **31 cases** and
  both new ones are mutation-tested: one term's type set to null fails the first, and removing the
  block fails the second.

### Fixed
- **`research.md` and `docs.md` named 26 classes at paths that do not exist.** `FogOfWar.java` and
  `ShadowCaster.java` moved to `tiles/` and `mechanics/` in an upstream merge, and the remaining
  references were bare file names that a reader cannot locate a package from. One of them,
  `SPDAction.java`, **was never a class in this repository**: the action the brief calls `SPDAction`
  is `env.ActionMapper`, which issues `HeroAction` itself. Every reference in `research.md` is now
  package-relative, and the two that name a class across a module boundary carry their fully-qualified
  name as well.
- **`doccheck` never checked that a documented class is a class.** It now resolves every Java path
  and fully-qualified class name in `research.md`, `docs.md`, `README.md`, `ENGINE-CHANGES.md` and
  `docs/documentation.md` against `core/src/main/java`, `SPD-classes/src/main/java`,
  `desktop/src/main/java` and `superintelligence/src/main/java`, and fails naming any that does not
  resolve. **48 references across 5 documents, 4,371 source files.** Mutation-tested: changing
  `tiles/FogOfWar.java` to `scenes/FogOfWar.java` fails it and names the path. `doccheck` is
  **4 cases**, from 3.
- **Sampling no longer defeats the recurrent chunk it is supposed to feed.**
  `EpisodeCollector` drew an independent 5% Bernoulli sample per step while `PPO.update` groups
  samples into contiguous runs of `rl.chunk_length` and resets the recurrent state at a gap. At
  p = 0.05 two adjacent steps survive with probability 0.0025 and a 16-step chunk with 0.05^16, so
  outside the 20-step tail every sampled step was backpropagated as a chunk of one and `LSTM.W_hh`
  learned nothing across a timestep. `rl.ContiguousChunkSampler` now draws a block start at
  `rate / chunkLength` and retains `chunkLength` consecutive steps per draw. Measured by
  `samplercheck`: **1,113 retained steps group into 70 contiguous runs, longest 16, no interior
  gaps**, over 20,000 steps. The retained fraction converges on `rate / (1 + rate)` and can never
  exceed the rate, which the gate asserts as a band rather than a tolerance. The collector's chunk
  length travels with the params frame — **`Protocol.VERSION` is 5** — so a trainer and its workers
- **`./gradlew :superintelligence:verify --args="<file>"` did not work**, and `README.md`
  documents that exact form. `withSubcommand` prepended the task name only when the first argument
  began with `--`, so a bare path reached `Main` as the *command* and printed the usage. It now
  prepends unless the first argument already **is** the command, which is what its own comment said
  it did - so `--args="<file>"`, `--args="verify <file>"` and `--args="--seed x"` all work.
- **The pickup trace moved out of `ActionMapper`.** It was 537 lines against a 500-line rule for
  production code, and the block that took it over was `spd.pickupTrace` instrumentation. It is now
  `env.PickupTrace` with one predicate, `ActionMapper` is 468, and no other production file in
  `:superintelligence` is over 500. `diag/GaeCheck` is 580 and is left alone: test code, where the
  same rule allows 600.
  cannot disagree about it.
- **`gradcheck` no longer fails a parameter whose gradient is below the noise floor.** The noise
  band is the measured disagreement between two step sizes, and a gradient whose magnitude is
  smaller than that band cannot be judged by relative error at all. The band now also carries an
  absolute floor, `8 x |loss| x 2^-23 / step`, which is the error a central difference inherits
  from a float32 loss. Measured: **128 parameters across 8 layers, 48 within 0.02, 80 in the noise
  band**. Removing the `tanh` derivative still fails 13 entries at relative errors of 0.24 to 1.50.
- **A resumed run replays generation 0's seed sequence.** `Checkpoint` restored the generation, the
  Adam moments and the optimiser step count; `SeedPool` was built fresh from the trainer seed, so
  the second half of a run replayed the first half's seeds. A draw is now a pure function of
  `(schedule seed, draw index)` — the pool is filled from one derived seed and each draw seeds a
  local generator from the other mixed with its own index — and a checkpoint carries four more
  fields: the pool seed, the schedule seed, the draws made and the pool size. **`Checkpoint.FORMAT`
  is 2**, so a checkpoint written before this is refused by name rather than half-read. Gated by
  `seedcheck`, 5 cases.
- **`.env.example` described a run the module does not run.** `SPD_RL_UPDATE_THREADS=4` against a
  compiled default of 1, and seven `env.*` keys missing entirely. `configcheck` now reads the
  example file: every documented key must appear as an `SPD_` variable **and** every value in it
  must match the compiled default.
- **`replayprobe` did not work.** It is registered through `cliTask`, which prepends the task name
  and relies on `Main` knowing it, and `Main` had no case for it — the task printed the usage and
  exited non-zero.
- **`README.md` could not be read as text.** Two control bytes (0x08 and 0x00) sat where backticked
  names should have been, so `grep` treated the file as binary, the pre-commit hook's badge check
  skipped it and reported *"Binary file ... matches"* without fixing anything, and the surrounding
  prose read `l.chunk_length`, `pttcheck`, `alueLoss` and `eward.stair_potential_weight` with
  their first characters gone.
- **`ENGINE-CHANGES.md` §7 and this changelog were wrong about `Mob.holdAllies`.**
  `Random.shuffle(candidatePositions)` is what the tree has, and it landed in commit `b4f717992`,
  whose message is about `rolloutcheck`. Both documents recorded the change as applied and
uncommitted. The external code review that asked for it to be committed found it already committed.

### Added
- **A second, strided convolution between the grid and the dense trunk.** The trunk's input was the
  12,696 features the first convolution produced, flattened, so **3.25M of a 3.58M-parameter
  network sat in one matrix** and every parallel update reduced 14.3 MB per thread per minibatch to
  add them up. `Network` now runs a 32-filter stride-2 convolution over the first one's output,
  taking the trunk's grid input to **3,200** features. Measured by `updatecost`: **1,403,316
  parameters**, forward 4.95 ms, forward+backward 11.07 ms per sample. `StepCache` carries both
  convolutions' pre-activations, so a chunked backward pass uses each step's own ReLU mask rather
  than the last one computed.
- **`env.sub_mode_progress_only`.** `USE` and `OPEN_INVENTORY` were offered whenever the hero
  carried anything whose default action was not drop or throw, and an equip toggle satisfies that
  test while spending no engine time at all. A hero whose backpack held one unequipped weapon could
  reach the sub-mode, toggle, leave and do it again, every step charged against
  `env.free_step_budget`. Measured on 40 uniform-policy episodes at fixed seeds, same code, only
  the flag differing: **ended `STALLED` 14 of 40 → 2 of 40**, `deepestDepth` 1 → 2. `DROP` is
  untouched and setting the key false restores the previous mask exactly. `stallcausecheck` proves
  both halves: `FREE_STEP` is reachable with the loose mask and unreachable with the shipped one.
- **The alchemy pot is reachable headlessly, and `CRAFTED` and `CRAFTED_MEAT_PIE` fire.** The
  blocker had been recorded as "brewing is `AlchemyScene.craftItem`, which needs a renderer" — true
  of the scene, false of the outcome. Everything `craftItem` does to the *world* is detach the
  ingredients, spend the pot's energy, brew, collect or drop the result, bump
  `Statistics.itemsCrafted` and restore the quantity; the rest is emitters, a sound, a save and a
  widget refresh. `env.AlchemyAction` does exactly that over the game's own `Recipe.findRecipes` and
  `Recipe.cost`. `Action.ALCHEMY` and `EnvMode.ALCHEMY` expose it: the world mask offers the pot
  only when the hero is adjacent to it and something is brewable, and the slot head enumerates the
  recipes in the game's own order. `CRAFTED` is diffed off `Statistics.itemsCrafted` like `KILL`
  and `SNEAK_ATTACK`; `CRAFTED_MEAT_PIE` is noted by the pot itself, because the game has no
  counter for a meat pie and there is no state to diff. Measured by `modecheck` on a fixture that
  turns a cell beside the hero into a pot and gives it a `Pasty`, a plain `Food` and a
  `MysteryMeat`: **energy 20 → 14, CRAFTED paid 25.0, CRAFTED_MEAT_PIE paid 100.0**.
  `Action.size()` is 19, so a checkpoint trained before this does not load into it.
- **A recording says how much randomness each step consumed.** `Replay.Step` carries `baseDraws`
  and `baseFingerprint` from `RandomTrace`, under `env.record_rng_fingerprint` (default on: one
  increment and two multiply-xors inside a record call every draw path already makes).
  **`Replay.VERSION` is 4** and **`ReplayCodec.VERSION` is 4**. `ReplayIO.verify` compares them
  first, before position, and names the step and the delta; a second check catches a stream that
  re-converged on the count. A recording written before this carries -1 and is not compared, so the
  committed corpus still verifies.
- **The viewer records cells.** `ReplayPlayer` built its `WorldSnapshot` with `.cells(false)`, so a
  rendered trace carried no terrain, trap, plant, heap, transition, fog, visited-set or
  line-of-sight record. `replay.ViewerWorldSnapshotBuilder` owns the decision now. Measured on
  `duelist-death-last.replay`: **1,003 cell records per step on both sides, 0 differing at steps
  93, 94, 95 and 96** — so `issues.md` 26's extra 1 HP is neither the terrain nor a trap, a plant,
  a heap, a transition, the fog, the visited set nor a mob. Evidence in `out/evidence/issue-26/`.
- **Forty-seven reward weights and curriculum bounds are settings rather than Java literals.**
  `reward.curse_removed_reward` 1.5, `reward.equip_too_strong_penalty` -2.0,
  `reward.identified_reward` 0.5, `reward.curse_equipped_penalty`, `reward.cured_debuff_reward`,
  `reward.eaten_early_penalty`, `reward.menu_noop_penalty`, `reward.level_up_reward`,
  `reward.strength_gain_reward`, `reward.gold_lost_factor`, the four `reward.item_value_*`,
  `reward.boss_slain_factor`, `reward.max_hp_gain_cap`, `reward.curriculum_fade_start_depth` 3,
  `reward.curriculum_fade_end_depth` 10, `reward.craft_reward`, `reward.craft_meat_pie_reward`,
  `trainer.push_retries`, and the boolean `env.sub_mode_progress_only` and
  `env.record_rng_fingerprint`. Every value is unchanged; what changed is that a run can be told
  what they are and a config file can change them.
- **Three gates, all in `gates`:**
  - `samplercheck`, 7 cases. Contiguous block sampling that hits the rate, a tail retained whatever
    the rate, a cap honoured exactly, 0 and 1 as real settings, the trunk downsampled, and a
    collector that keeps whole blocks at 16 while one built at 1 keeps none.
  - `seedcheck`, 5 cases. A draw is a function of its index, the same seed draws the same sequence,
    two trainer seeds differ, a resumed pool continues the interrupted sequence, and a checkpoint's
    `Meta` carries the schedule.
  - `doccheck`, 4 cases. No markdown under the module carries a control byte, every gate is
    reachable either through `Main`'s dispatch or a `mainClass` of its own and every `cliTask` has a
    case in `Main`, `README.md`'s gate count matches the `gates` task, and **every Java path a
    document names resolves against the four source roots**.

### Changed
- **`gates` is 33 checks and `verifyall` is 34**, from 30 and 31. `configcheck` is 14 cases and 86
  keys. `verifycheck` is 9. `collectcheck`'s wire case carries the RNG fingerprint and requires it
  on every recorded step.
- **The module carries no comments at all.** The post-commit hook has stripped `//` since it was
  installed; 18 block and javadoc comments across 15 files, the GPL header copied into
  `replay/WorldSnapshot.java`, and every `//` in `build.gradle` are now gone as well — **1,086 lines
  removed, 10 added**. Game files under `:core` and `:SPD-classes` keep their licence headers:
  those are upstream's legal notice rather than this module's prose.
- **`README.md` says one thing about depth.** It carried "Nothing has reached depth 2" beside
  "`bestDepth 2` in 9 of 30 generations" beside "the first depth-2 run in this project's history".
  It now says depth 2 is reached, that three repeats of one identical config put `bestDepth` at 2, 1
  and 2, and that `bestDepth` on its own is a coin flip.
- **`README.md`'s "Known approximation" is about what it is actually truncated on.** It said the
  recurrent gradient is truncated at length one while the status table two screens above said it is
  not. It now states the window: `rl.chunk_length` steps against an advantage support of
  `1/(1-gamma*lambda) ≈ 17`, plus the fixed-width observation window and the state-diffing reward.
- **`README.md`'s stale counts corrected against the code:** `configcheck` 14 cases and 87 keys (was
  11/64 in one place and 10/63 in another), `rewardcheck` 29 (was 13), the properties file 87 keys
  (was 35), `reward.kill_reward` 150.0 and `reward.sneak_attack_reward` 300.0 (were 12.0 and 22.0).

### Measured
- **`issues.md` 37 reproduced on a fresh run, and localised.** A two-worker, two-episode,
  two-generation run with `train.record_every=1` wrote two recordings. One reproduces twice,
  identically. The other does not, identically on both runs:

  ```
  [ERROR] replay diverged at step 1421 of 1422
          rng baseDraws diverged at step 1421: replayed 745, recorded 747 (delta -2)
          action SEARCH slot 0 mode WORLD
  ```

  Before this cycle that recording would have reported a position, health or clock divergence and
  nothing about the stream. It now says the two sides had already parted in the generator, that
  **the recording drew more** - so the worker is the side making the extra draws - and that the
  step reaching it is a `SEARCH`, which `issues.md` 33 has already measured as the most
  engine-time-expensive action in the game. `baseDraws` is cumulative and does not say which call
  site, so nothing is diagnosed; `rngtrace` with `-Dspd.rngTrace` is the next measurement and is
  not taken. Evidence in `out/evidence/issue-37-smoke/`.

### Not done
- **`rolloutcheck` is still the one flaky gate, re-measured at 1 in 8 with the new comparison and unchanged.**"n"n  Seven of eight consecutive runs green, so recording `baseDraws` has not made it flakier. The one"n  failure now reports `rng baseDraws diverged at step 57: replayed 290, recorded 298 (delta -8)`"n  instead of a different cell at step 54, because `ReplayIO.verify` checks the stream first. That is"n  a direction and not a diagnosis: the count is cumulative and says nothing about which call site made"n  the extra draws. `issues.md` 18 records it.

- **`Dense.matvec` is not vectorised with `jdk.incubator.vector`.** The incubator module works on
  this JDK - `SPECIES_PREFERRED` reports 8 lanes here - so this is not a toolchain problem. The dense
  layer the review's sketch targets is **17.0% of the forward pass** by multiply-accumulates
  (`trunk`, 4,567 x 256, against 6,875,456 for the whole forward), and forward is 4.95 ms of an
  11.07 ms update, so the whole of it is **7.6% of an update**. The part worth vectorising is the
  convolution at **79.9%**, whose `Conv2D.forward` inner loop strides by `col.cols` over a row-major
  im2col buffer - the worst layout for a vector unit - and winning there means restructuring
  `Conv2D` rather than replacing a scalar loop in `Dense`. Four build sites would need the module
  flag, including `javaclint`, whose `-Xlint:all -Werror` meets the incubator module's mandatory
  warning. The arithmetic is in `REJECTED.md`.
- **The eleven sound-pitch draws stay on `Random`.** Ten of them, counted; the eleventh is a
- **The eleven sound-pitch draws stay on `Random`.** Ten of them, counted; the eleventh is a
  `Random.oneOf` over sound ids and not a pitch. One line each, and moving them changes how many
  values a footstep consumes, which invalidates all twenty committed recordings — including
  `duelist-death-last.replay`, the fixture `issues.md` 26 is diagnosed against. They are symmetric
  across both environments, so they cannot separate them and cannot cause a divergence. See
  `REJECTED.md`.
- **`MENU_NOOP` stays unwired**, for the reason already measured rather than a new one: every
  sub-mode action either advances the engine clock or leaves the sub-mode, and `modecheck` now
  covers all seven modes and the claim still holds.
- **The observation grid is still a fixed 48x48 window**, so 419 of 1,040 measured floors are
  clipped on at least one axis. Ego-centring it is an encoder change that invalidates every
  checkpoint and belongs with the next measurement.

### Not proposed for commit, and measured anyway
- Nothing. `Mob.holdAllies` was the only such entry and it is committed — see Fixed.
- **A dense signal for the objective: potential-based shaping towards this floor's stairs.**
  `gamma*Phi(s') - Phi(s)` with `Phi` the negative breadth-first distance to the exit,
  `reward.stair_potential_weight` 2.0. Floor 1 is several hundred walkable tiles and a random walk
  onto the exit succeeds about 0.1% of the time, so across 5,360 episodes `meanDepth` was 1.001 -
  a descent was not merely unrewarded but unreachable. The shaping term telescopes over an episode,
  so a round trip pays nothing however many times it is repeated, and the only way to earn is to
  arrive. Measured by `rewardcheck`, which is 26 cases: 4 steps closing the gap pay **+8.12** and 4
  steps opening it cost **-5.82**, and a round trip nets **2.30** where a farmable reward would net
  the gross. Zero turns it off.
- **`obs.GridChannel.EXIT_BEACON`**, a memory plane that lights the floor's stairs once the hero has
  stood where they can be seen. `TRANSITION` only lights while they are in view, so a hero that walked
  past and away had no channel carrying the fact - which is exactly what the agent needs and least
  often has in front of it, and exactly what the recurrent gradient now has to carry. Not lit from
  generation: painting it immediately would hand over the objective for free.
- **`Action.TALENT` and `EnvMode.TALENT`**, so a talent point can be spent headlessly. Spending was
  UI-only (`TalentButton` -> `TalentsPane` -> `WndHero`), so `RewardTerm.TALENT_UPGRADE` - 400.0, one
  of the largest payouts in the reward function - could never fire, and the first point arrives at
  level 2. `env.TalentPicker` enumerates what the game would offer, in the game's order.
- **`gradle :superintelligence:freeactioncheck`**, a gate, 7 cases, mutation-tested both ways.
- **`gradle :superintelligence:bpttcheck`**, a gate, 7 cases. Its first case is constructed so the
  answer is forced: the loss sits on the second step only, so the sum of the isolated steps is exactly
  the isolated gradient of the second. Comparing two gradient *norms* was tried first and was measured
  to let the mutation through.
- **`viewcheck` gates the two viewer diagnostics that were verified by running them and gated by
  nothing.** `-Dspd.replayContinueOnDiverge` and `spd.hidden` are both in `issues.md` 29 with a
  measured answer and no gate, so either could stop working without anything going red.
  `spd.replayContinueOnDiverge` is checked on the one recording that diverges - **100 steps applied
  against 95 without it**, and dropping the flag fails the check with both runs reporting 95.
  `spd.hidden` is checked **without ever opening a window**: a new `--probe-config` mode calls
  `ReplayLauncher.windowConfig()` and halts, so the property is read and printed with no
  `Lwjgl3Application` constructed. A gate that took over the desktop to prove a flag was wired is a
  gate nobody runs. Both run whether or not the corpus is green, so a corpus failure cannot hide one.
- **A gate for the policy push that issues.md 28 fixed and could not gate.** It asserts that no
  single write carries more than `ipc.push_chunk_bytes`, that the bytes arrive identical, that a
  payload of exactly one chunk is one write, that an empty payload writes nothing, that a stream
  failing twice is retried rather than abandoned, and that a stream failing for ever is refused
  naming what failed and how much. Reinstating the unchunked write fails the first case with the
  measured size; dropping the retry fails the fifth. `trainer.push_retries` is now a setting rather
  than a private constant, which is what lets the fifth case run in 250 ms.
- **`gradle :superintelligence:curriculumcheck`**, a gate, 5 cases, and the first measurement of
  whether the shaping fade does anything. `curriculumcheck` says **1.0 up to depth 3, 0.0 from depth
  10**, strictly between them in between, monotone across forty floors; that a generation's `shaping=`
  is the mean of its episodes' own scales (**0.6787** over depths 1, 2, 3, 7, 12); and - driving real
  descents through `Level.activateTransition` - that a run reaching **depth 6** carries a shaping
  scale of **0.6064** in both `SPDEnv.curriculum()` and `RewardLedger`. Removing
  `curriculum.observe(depth)` from `SPDEnv.onFloorTransition` fails two of the five.
- **A `.env` file is read by the module.** It sits between the properties file and the process
  environment, which is where the documentation has always said it sits; it used to sit nowhere, so a
  developer who skipped the shell one-liner ran with settings they did not believe they were using.
- **Twenty-six settings that were Java literals.** Worker JVM heap and GC pause, the pipe and weight
  push chunk sizes, the episode tail length, the gradient clip and Adam's three constants, the value
  loss weight and residual ceiling, the random-seed leak rate, the checkpoint and record intervals, and
  the dialog aging step. `configcheck` is now 13 cases and 64 keys.
- **`env.max_slots` is 96, the game's own ceiling.** It was 32, which silently truncated two thirds of
  what the hero can carry; the truncation was invisible rather than refused, because the head is a
  fixed-width window and everything past it simply was not in the observation. This widens the network's
  input, so it invalidates checkpoints trained at 32.
- **`--replay-group <id>`: a run's recordings are its own group, in the catalogue and on disk.**
- **A gate for `Main.rollout`,** the command's own option handling rather than the collector
  underneath it. `issues.md` 31 recorded that `rolloutcheck` guards the path training uses and that
  `Main.rollout` is a separate path with no gate over it - which is where a real regression landed
  before, an empty-seed guard that rejected both the flag *and its absence*, making random seeds
  unreachable. `rolloutcheck` drives `cli.RunCommand.execute` in process, so what is under test is
  the command's empty-`--seed` refusal, the resolved seed reaching the recording, the `--save` round
  trip and `--max-turns` reaching the environment. The command body moved out of `Main` into
  `cli.RunCommand`, which is what made it callable at all.
- **`javaclint` is in `gates`.** It was a task a developer had to remember to run, so the six warnings
  it had been reporting survived; a lint that is not a gate is a lint that does not happen.
- **`configcheck` reads `superintelligence/build.gradle`**, which is the one file in the module that
  decides a setting without being one. See the first entry under Fixed.

### Not proposed for commit, and measured anyway
- **`Mob.holdAllies` shuffles with `Collections.shuffle`**, which ignores the game's seeded generator.
  It is the one site §3 of `ENGINE-CHANGES.md` records as missed. The fix is one line -
  `Random.shuffle(candidatePositions)` - and it is **applied and uncommitted**, because
  `instructions.md` §12.3 keeps engine *logic* changes out of a commit until they are proposed.
  Measured on the change: `paritycheck` 32 recordings over 8 seeds replay exactly twice in one
  process, all 30 gates green, and `:desktop:viewcheck` unchanged at **19 of 20** with the same single
  failure as before - `duelist-death-last.replay`, which is `issues.md` 26. What it changes in a game
  is the gameplay RNG stream on any floor that holds an ally, which is the risk §12.3 names.

### Fixed
- **Three reward terms that were declared, plumbed and never emitted now fire.** `docs.md` asks
  for two of them and `RewardTerm` declared five.
  - **`CURSE_REMOVED`** pays **1.50** when a carried, known-cursed item stops being cursed *without*
    leaving the backpack. The "without leaving" half is what separates it from a drop: the diff
    compares the cursed-carried count against the carried count, and a drop moves both while an
    uncurse moves only the first.
  - **`EQUIP_TOO_STRONG`** pays **-2.00**. The game does **not** refuse an equip for insufficient
    strength - `Armor.STRReq` and `Weapon.STRReq` are an encumbrance and `Hero.drRoll` charges two
    points per point of shortfall - so the state to count is the shortfall itself, which is also
    exactly what `docs.md` lists under what the agent should be punished for.
  - **`MENU_NOOP` is left unwired, and the reason is measured rather than guessed.** A gate drives
    sub-mode actions and records that **every one either advanced the engine clock (0 of 60) or
    left the sub-mode (60 of 60)** - a refused slot action falls back to `WORLD` rather than sitting
    in the mode it was issued from. The state the term names does not exist in this environment, and
    an emission site that can never fire is `KILL`'s original sin. The case goes red the day a
    sub-mode action does nothing, which is the day the term can be written.
  - **`CRAFTED` and `CRAFTED_MEAT_PIE` are left unwired for the same reason.** Brewing is
    `AlchemyScene.craftItem`, which needs a renderer, and the headless environment has none -
    `ENGINE-CHANGES.md` records the same fact for `AlchemyScene`'s item drop. There is no state diff
    to take, so there is no emission site to take.
  `rewardcheck` is **29 cases** and both new sites are mutation-tested: zeroing `CURSE_REMOVED`
  fails with *"recorded 1 time(s) paying 0.0"*, zeroing `EQUIP_TOO_STRONG` fails with *"paid 0.0"*.
- **`shaping=` under the generation report was a constant with a label on it.** `Trainer.curriculumScale()`
  returned `1f`. The curriculum itself was wired - `SPDEnv.onFloorTransition` calls
  `curriculum.observe(depth)` and pushes the result into the ledger - so the dashboard reported a
  number the run had never used. It is now the mean over the generation's episodes of the scale each
  one actually priced its shaping terms at, and `curriculumcheck` measures it. **The fade was never
  inert, and it is still at 1.0 for any run that has not passed floor 3**, which is every run in this
  project's history. `TODO.md` 2 and 5 said the opposite of `README.md`; `README.md` was right.
- **A gate whose failures went to a list nobody read.** `CurriculumCheck` had its own `failures`
  list and statically imported `RewardFixtures.fail`, which appends to *that* class's list, so the
  two descent cases failed into a sink `main` never read and the gate printed `[OK] 5 checks passed`
  over them. Every gate this cycle touched now reports through one `GateReport`, so there is a single
  list in the module rather than one per file that has to be the right one.
- **Two places decided one run's heap and one of them was unreachable.** `worker.heap_min`,
  `worker.heap_max` and `worker.gc_pause_ms` are documented settings, they reach every worker JVM,
  and `superintelligence.properties` compares them against the compiled defaults - but the trainer
  process itself is launched by a Gradle task whose `-Xms256m`, `-Xmx1536m` and
  `-XX:MaxGCPauseMillis=200` were literals, so setting them changed the workers and not the process
  that pushes weights to them. The build script reads `SPD_WORKER_HEAP_MIN`, `SPD_WORKER_HEAP_MAX`
  and `SPD_WORKER_GC_PAUSE_MS` now, with the compiled default as the fallback, and `configcheck`
  holds the contract in both directions: each name must appear, and each literal must not.
- **21 of 64 documented settings were never proven reachable, and the check said all 64 were.**
  `configcheck` compares each key in `superintelligence.properties` against the compiled default, and
  `compiledValue` knew about 43 of them; the other 21 took `continue` on a null, which is a skip and
  not an agreement. The reachability case did the same, so `worker.heap_max`, `rl.chunk_length`,
  `reward.stair_potential_weight`, `ipc.push_chunk_bytes`, Adam's two betas, the four value-loss keys
  and the rest were counted inside a green that read "all 64 documented keys change the setting they
  name". All 21 are mapped and given a sentinel now, and **a key with no compiled value is a
  failure** in both cases rather than a line skipped. This is the seventh instance this project has
  recorded of a green that was a claim about less than it appeared.
- **Five files over the project's 500-line limit**, split by functional boundary, which is what the
  limit is for. `diag/RewardCheck` (1,354) is a runner plus `RewardTerminalCheck`,
  `RewardDescentCheck`, `RewardCombatCheck`, `RewardShapingCheck` and the shared `RewardFixtures`;
  `diag/ReplayCatalogCheck` (783) is a runner plus `ReplayCatalogListingCheck`,
  `ReplayCatalogGroupingCheck` and `ReplayCatalogFixture`; `diag/CollectCheck` (615) is a runner plus
  `CollectWireCheck` and `CollectTrace`; `diag/ConfigCheck` (542) is the cases plus `ConfigDefaults`;
  and `Main` (506) is a dispatcher plus a `cli` package - `CliOptions`, `Cli`, `RunCommand`,
  `VerifyCommand`, `RngTraceCommand`, `FixtureCommand`. Every file in `:superintelligence` is now
  under 600 logical lines.
- **`gradcheck` reported finite-difference noise as a wrong gradient.** The relative error was measured
  against a denominator that can be arbitrarily small, so a gradient of 1.2e-4 read to an absolute
  accuracy of 2.8e-5 was reported as 22% wrong. The noise is now **measured** - the same central
  difference at two step sizes, whose disagreement is the noise - and an entry passes when the analytic
  gradient agrees to within it. A genuine defect reproduces at every step size: removing a `tanh`
  derivative fails 5 entries with relative errors of 0.23 to 0.50 against noise of 0.001 to 0.02.
- **The recurrent gradient was truncated at length one.** Every sampled step was backpropagated in
  isolation with `dPrev` and `dCell` cleared, so no gradient crossed a timestep and `LSTM.W_hh`
  received nothing at all for holding information forward - the whole point of a recurrent layer in a
  partially observed grid game. The update now groups the batch into contiguous runs of up to
  `rl.chunk_length` (16) steps and backpropagates each run in reverse without clearing between steps.
  Measured on the gate's fixture: a two-step chunk's LSTM gradient differs from the sum of its steps'
  by **63.7%**, and reverting the accumulation drops that to **0.00000**.
- **The critic was asked to fit returns in the thousands.** Raw returns ran to -3000 for a stall and
  +1800 for a descent, `valueLoss` measured 12,432-31,323, and the gradient that produced flowed into
  the trunk the actor shares - where global clipping then squashed the policy's update. Value targets
  are now normalised by a running scale (`rl.normalise_values`), the residual is clipped, and the loss
  is Huber. A batch of -3000 and +1800 now normalises to at most 1.0.
- **A stale recurrent state in the replay.** Each transition carried the state the *rollout* decided
  under, and the update replayed it through weights that have moved since generation 0. A chunk now
  replays its own forward pass in order, so the chain is the network's own.
- **`REST` advanced no engine time, 323 times out of 326.** The rest turn is taken by the rest branch
  of `Hero.act`, and the drain can return before the hero is picked again, so a rest moved nothing. It
  now spends `Actor.TICK` through `spendAndNext`, which clears readiness, and hands control back rather
  than leaving the hero resting, because that branch never calls `ready()`.
- **A menu could be opened and cancelled for ever without the world moving.** `OPEN_INVENTORY`, `USE`
  and `DROP` are the only entries into a sub-mode and all three cost nothing, and `CANCEL` is the only
  way out of two of them, so the pair is a loop that advances no engine time at all - and every step of
  it is charged against `env.free_step_budget`, so 100% of episodes ended `STALLED` at turn ~120 of
  the 1500 granted. After a zero-time exit the next `WORLD` mask refuses all three for exactly one step.
- **An item that creates a mob left it with no sprite, and killed the worker - now committed and gated.**
- **A free-action loop was charged and not bounded, and the charge did not stop it.**
- **A descent pays for a new floor, not for going down a stair.**
- **Six compiler warnings, and nothing was holding the module to zero.** `javaclint` compiles the
  module with `-Xlint:all -Werror` and reported a `this` escape in `PPO`'s field initialiser, a
  possibly-lossy `double`-to-`int` compound assignment in `ReplayIO.verify`, two static members
  reached through an instance expression in `SPDEnv` and `Quickslots`, a redundant cast in
  `Trainer.writeParams`, and the deprecated `Graphics.getRawDeltaTime`. Two of them were worth more
  than a lint: the `PPO` one is a field holding a reference to its own constructor's argument, and the
  `ReplayIO` one was an accumulator of the per-step reward that **nothing ever read**, so `verify` had
  been summing a total and discarding it since the wire was introduced. `ShardedUpdate` now takes the
  `PPO` per call rather than storing one, which is the same fix without a lazy initialiser.
- **A run now shows a progress bar, and a script means nobody types a path.**
- **The viewer catalogue no longer goes through gradle.**

### Changed
- **`rewardcheck` is 29 cases**, from 23.
- **The spatial grid is bit-packed on the wire.** Every plane is 0 or 1, so a byte per cell held eight
  times the information it carried, and the grid was 90% of a transition: 50,688 bytes down to
  6,336 at 22 planes over 48x48. Lossless, and `gaecheck` holds both halves - that it round-trips
  exactly, and that it is still eight times smaller, so a packing that quietly stopped compressing
  would fail.
- **`Protocol.VERSION` is 4.** The transition frame carries the episode id and step index, and the
  params frame carries the tail length; a version-3 pair would read the episode id as an action index,
  which is a plausible action on every step and so trains on noise rather than throwing.
- **`gates` is now 30 checks and `verifyall` is 31**, with `pushcheck`, `curriculumcheck` and
  `javaclint` added. Every document that counted them has been corrected: `README.md` said
  "twenty-six", `testing-guide.md` said 24, and `docs/documentation.md` said twenty-four and named
  24 of them.
- **`configcheck` is 13 cases and 64 keys,** up from 10 and a count of 43 that was printed as 64.
- **`.env.example` was describing pre-rescaling values** - `depth_reward` 25 against the compiled
  1800, `turn_cost` 0.002 against 1.0, `death_penalty` 100 against 2500, `max_slots` 32 against 96 -
  so copying it configured a run the module does not run. Every value now matches the compiled default.

### Measured
- **`rolloutcheck` is flaky, and re-measuring it moved both the rate and the signature.**
  Eight consecutive runs: **1 failure in 8**, against the 1-in-30 this entry recorded before. The
  failing case is the original random-seed one, not one of the four added since, and the divergence
  is **on position** - the hero at a different cell from step 54 of 60 - where `issues.md` 26 and 37
  are both one-turn or one-point deficits. `checkAnAbsentSeedFlagIsAccepted` also runs a random-seed
  episode but only asserts steps came back, so it cannot fail this way; the exposure is nonetheless
  at least double what it was, because the gate now runs two random-seed episodes rather than one.
  **This makes `gates` itself flaky**, which is recorded rather than smoothed over: a red `verifyall`
  a ninth of the time trains people to re-run it, and a re-run that comes back green is what these
  gates exist to break. Until it is diagnosed a green `gates` is a claim about the run it just did.
  Eight runs cannot separate 1-in-8 from 1-in-30 anyway - the interval runs from 1 in 25 to 1 in 3.
- **The 30-generation run `TODO.md` 1 has asked for since the beginning.** 8 workers x 4 episodes,
  4 update threads, seed 20261016, `train.random_seed_rate=0`, every other default unchanged,
  12 min 41 s, **and it completes** - past the generation ~22 where `issues.md` 28's policy push
  used to kill a run.

  | | generations 0-14 | generations 15-29 |
  | --- | --- | --- |
  | `meanScore` mean | **-3,535.7** | **-3,534.1** |
  | `meanScore` sd | 137.5 | 98.5 |
  | `meanScore` range | **567.2** | **375.4** |

  **+1.6 points of movement against a per-generation standard deviation of 98 to 138.** That is not
  a trend. `bestDepth 2` in **9 of 30** generations, `meanDepth` peaks at 1.062, `valueLoss` runs
  0.14 to 0.90 and `clipFraction` falls 0.552 to ~0.05 while entropy climbs - so the update is
  moving and the objective is not. The band `issues.md` 27 quotes (+/-16 over 15 generations) was
  measured on a different configuration and does not transfer; **a change on this config has to
  clear ~100 points over 15 generations to mean anything.** Evidence:
  `out/evidence/train-noise-floor-30.log`, `out/evidence/noise-floor-30.csv`.
- **A recording this trainer wrote does not reproduce.** `issues.md` 37, new: of the two
  recordings a generation keeps, the 1186-step one reproduces to the last decimal and the 599-step
  one diverges at step 534 by one engine turn, deterministically, with position and health
  agreeing at every step. Recorded, not guessed at.
- **`shapingScale` is a column in `metrics.csv`.** It was on the generation report and nowhere
  else, so the one number a run's shaping was priced at could not be compared across runs.

### Fixed
- **A path given on the command line resolved against the wrong directory, and the log named
  the right one.** `--out`, `--save`, `--config` and the `--rng-trace-*` outputs were resolved
  against the process's working directory, which is the module directory under `gradle` and the
  shell's under a plain `java -cp`. Measured: a run logged
  `superintelligence\out\run\metrics.csv` and wrote
  `superintelligence\superintelligence\out\run\metrics.csv`; and `verify` on a path that existed
  reported `[ERROR] <path>`, which is `NoSuchFileException`'s message with nothing to say the file
  was missing. A relative path now resolves from the repository root whichever way the process was
  started, every path the trainer *reports* is canonical and absolute, and `configcheck` holds
  both in 2 of its 13 cases.
- **`shaping=` under the generation report was a constant with a label on it.** 20 generations, 8 workers, 4 update threads, seed 20261016, every
  default unchanged: `bestDepth` 2 in **8 of 20 generations** and `meanDepth` 1.05, against `meanDepth`
  1.001 across 5,360 episodes before. `trainer-replays/bptt-stairs/CLL-ZXD-MTZ.dat` (ROGUE,
  generation 19, 438 steps, depth 2) **reproduces exactly**, so the descent is a real trajectory rather
  than a reporting artefact. `valueLoss` runs 0.43 to 1.24 where it was 12,432 to 31,323, and the run
  completes where the policy push used to kill it at generation ~22.
  **This is one run and it does not clear the noise floor** - `TODO.md` 1 puts that at plus or minus 16
  points of `meanScore` over fifteen generations - so it is a move in the right direction and not yet
  a result. `issues.md` 36.
- **`stallbreakdown`, 40 episodes, uniform policy, fixed seed per episode, defaults unchanged, after
  the free-action fixes:**

  | | before | after |
  | --- | --- | --- |
  | `meanTurns` | 120.6 (`issues.md` 29) / 626.8 (free-step budget at 250) | **800.7** |
  | ended `STALLED` | 40 of 40 | **15 of 40** |
  | which guard | 100% `FREE_STEP` | 37.5% `FREE_STEP`, 0% `POSITION`, 0% `PIPELINE` |
  | `REST` steps advancing no engine time | 323 of 326 | **1** |
  | longest run of consecutive free steps in an `OPEN_INVENTORY`/`CANCEL` loop | 240 of 240 | **2** |

  Evidence: `superintelligence/out/evidence/stallbreakdown-after-fix1.txt`.
- **A run's artefacts go to `superintelligence/out/run/`**, not to the system temp directory.

### Not done

Tracked in TODO.md and issues.md, not here.
## [0.10.0] - 2026-10-13
### Added
- **`reward.profile`, with two values.**
- **`rewardcheck` grew from 17 cases to 20**
### Fixed
- **A recording written under a profile said it was written under the default one.**
### Changed
- **`Protocol.VERSION` is 3.**
- **A replay header records `reward_profile`.**
### Not done

Tracked in TODO.md and issues.md, not here.

## [0.9.0] - 2026-10-12
### Fixed
- **A thrown or dropped item never triggered what it landed on.**
- **The scene test is load-bearing and is still there.**
- **`test-replays/duelist-drop.replay` had been carried over from before the reward re-pricing, an
- **`ReplayCatalog` listed every trainer recording twice, and the duplication decided which files rea
- **A file name did not resolve, and the listing told you to type one.**
- **The trainer section's header claimed every recording was watchable, over nine rows that sai
- **`trainer-replays/` had 185 recordings in it and is now empty and untracked.**
### Added
- **`gradle :superintelligence:presscheck` is in `gates`.**
- **`test-replays/duelist-throw.replay`, and `gradle :superintelligence:pressfixture` to regenerate it.*
- **`replay.PressFixture`**
### Verified
- `verifyall` — `:superintelligence:gates` at **23** (with `presscheck`) and `:desktop:playbackcheck` a
- `:desktop:viewcheck` — **20 of 20**, 50.9 s at 8 children on an idle machine and 134.5 s on a loade
- The new fixture mutation-tested, and **the gate that refuses it is not the one you would guess**
- **The rest of the corpus is unaffected, and that is a measurement rather than an assumption.**
### Not done

Tracked in TODO.md and issues.md, not here.

## [0.8.0] - 2026-10-12
### Fixed
- **A chasm fall was never serviced.**
- **`InterlevelScene.mode` is a process static and nothing headless ever reset it.**
- **The landing ignored `InterlevelScene.fallIntoPit`.**
- **The landing cost nothing.**
- **Walking a hero *at* a chasm threw `NullPointerException` and took the worker with it.*
- **`Chasm.jumpConfirmed` and `InterlevelScene.fallIntoPit` joined `RunState`'s list**
- **`LevelPipeline.handleFall` no longer forces `Dungeon.branch = 0`.**
- **The agent could not answer the game's "jump?" prompt, and so could never walk into a chasm.**
- **A dialog the drain opened was reported to the agent a turn late.**
- **A hero left standing on a chasm falls, and the agent was the only party that could make tha
- **A thrown or dropped item never triggers what it landed on.**
### Added
- **`gradle :superintelligence:fallcheck`**, a gate, six cases.
- **`gradle :superintelligence:dialogcheck`**
### Not done

Tracked in TODO.md and issues.md, not here.

### Verified
- `verifyall` — `:superintelligence:gates` at 22 (with `fallcheck` and `dialogcheck`), `:desktop:playbackcheck`, both green
- `:desktop:viewcheck` — **19 of 19**, 134.3 s at 8 children.
- `fallcheck` mutation-tested five ways, each reverted after: reordering the `mode == FALL` tes
- `dialogcheck` mutation-tested four ways, each refused by 2, 3, 1 and 2 of its 3 cases: restoring th
- All seven committed trainer recordings still reproduce, and a fresh two-generation trainer run write
## [0.7.0] - 2026-10-11
- **The trainer recorded a step's quickslot bindings _after_ the step, so a `DROP` named a different ite
- **`verify` resolved a recorded slot index against whatever bindings the process happened to hol
- **`verify` reported a recording that cannot be watched as verified.**
- **`viewcheck` reported a recording that did not play as clean.**
- **`Quickslots.restore` wiped bindings a slot index can never name.**
### Added
- **`duelist-drop.replay` in `test-replays`, and `gradle :superintelligence:dropfixture` to regenerat
- **Four gate cases**
- **`replay` package: `DropFixture`, the recording the command writes.*
- **`Replay.wireDroppedStepFields()` and `Replay.recordsQuickslots()`**
### Fixed
- **The refusal rule added above was itself a false positive, and `rolloutcheck` found it by failin
- **`PlaybackCheck`'s "a recording with no resolvable slots" fixture stopped being unwatchable**
- **`ReplayCatalogCheck`'s `write()` helper wrote every step field at its sentinel**
- **`ReplayIO.readHeader`'s new body scan compared against a nine-field step and found none.**
### Verified
- `verifyall` — `:superintelligence:gates` at 20, `:desktop:playbackcheck` at 22, both green, an
- `:desktop:viewcheck` — **18 of 18**, 36.2 s at 8 children
- **End to end, on a fresh trainer run**
### Added
- **`APR-SLJ-DXM` in `trainer-replays` — a trainer recording that is watchable.**
### Changed
- **The catalog says per recording whether it can be watched, instead of one blanket warning for th
### Fixed
- **`TURN_COST` was charged per _step_, and a step is not a turn — so a search was half price.*
- **A recording whose death lands on its final step reported N-1 of N steps.** `ReplayPlayer.settle()`
### Added
- **Two `rewardcheck` cases and one `playbackcheck` case**
- **`duelist-death-last.replay` in `test-replays`**
### Measured, after the fix
### Changed
- **`trainer-replays/` now contains only recordings that can be watched.**
### Fixed
- **`viewcheck`'s child timeout was shorter than a full-length recording takes to play.**
### Fixed
- **`IDENTIFIED` was the one term that only ever paid for additions**
### Changed
- **`reward.turn_cost` is 1.0**, was 0.002.
### Measured
### Fixed
- **`IPW-QMA-AFY.dat` was destroyed by `d675e4815` and I reported it as an addition.**
### Changed
- **The reward scale is anchored on depth, because `talent_reward` had outbid it.**
- **Where a run's recordings go, and that they cannot overwrite each other.**
- **`recorded-at` column**
### Not done

Tracked in TODO.md and issues.md, not here.

### Verified
- `verifyall` - `:superintelligence:gates` at 20, `:desktop:playbackcheck` at 23, both green
- `:desktop:viewcheck` - **19 of 19** on the corpus, 42.8 s at 8 children, with `duelist-death-last.replay` added
- **All five recordings in `trainer-replays/` verify and play clean**
- Four fresh trainer recordings from the retrain, before the fix, all verify and play clean.
### Changed
- **The catalog separates the corpus from the training output.**
- **Recordings moved into the module and split by provenance.**
### Fixed
- **Replays crossing the worker pipe were carrying four of nine step fields.**
## [0.6.0] - 2026-10-10
### Added
- **`RewardTerm.TALENT_UPGRADE`, at `reward.talent_reward=20.0` — the largest single payout after 
- **`checkSpendingATalentIsWorthALot`**
### Known issue
- **Nothing can spend a talent point yet.**
## [0.5.0] - 2026-10-10
### Fixed
- **Exploration paid more than descending, which is why `bestDepth` had been 1 in every generation o
- **`KILL` was declared, plumbed and never emitted.**
- **`damage_penalty` was priced so low that dying carried no gradient at all.**
- **`STALLED` cost exactly what dying cost**
- **`TURN_LIMIT` was `-turnCost * 100`**, which is **-0.2** against a **+10** depth reward.
- **A free action was free.**
### Added
- **`RewardTerm.SNEAK_ATTACK`**
- **`RewardTerm.NO_PROGRESS`** — a step that advanced no engine time.
- **Four `rewardcheck` cases (9 → 13), each a property rather than a constant:*
- **`checkKillingIsWorthDoing`**
### Mutation-tested, including one that a constant-only check could not have caught
- **`.env.example` at the repository root**, and `.env` git-ignored.
### Measured
### Verified
- `verifyall` — `:superintelligence:gates` at 20, `:desktop:playbackcheck` at 21, both green.
- `:desktop:viewcheck` — **17 of 17** in 37.4 s at 8 children.
### Not done

Tracked in TODO.md and issues.md, not here.

### Measured
### Not done

Tracked in TODO.md and issues.md, not here.

## [0.4.2] - 2026-10-10
### Changed
- **`viewcheck` now forks 8 children by default instead of 1.**
- **`ViewCheck.parallelism()` had two comments that disagreed about which mode was fast**
### Fixed
- **`-PviewcheckJobs` was silently ignored.**
- **The `pre-push` viewer gate is no longer installed.**
### Documentation
- `testing-guide.md`, `FINDINGS-viewer-fidelity.md` and `PLAN-viewer-fidelity.md` all stated tha
## [0.4.1] - 2026-10-10
### Documentation
- **`issues.md` "Training" 13 investigated: the stall is real, but the hero is looping rather tha
- **`cleric-mid` is a second and unrelated `STALLED`, and conflating the two is what makes this look lik
- **The recommendation to regenerate the recordings is recorded as the wrong remedy, with the reason.*
- **Two defects found alongside it, recorded in `TODO.md` §0.8.**
### Changed
- `TODO.md` gains **§0.10** for the equip loop, and its header names it alongside the two §0.8 defects
- `issues.md` 13 now cites `TODO.md` 0.10 in its status line rather than an unnumbered "see below"
## [0.4.0] - 2026-10-10
### Changed
- **The changelog moved here, and the module's version is now its own, on its own line.**
- **The module's series now starts at 0 rather than at 4.**
- **The pre-commit hook checks two badges instead of one, and they are checked against two differen
### Fixed
- **The repository had a changelog it had no right to have.**
- **The pre-push hook still described a red viewer.**
### Verified
- `verifyall` - `:superintelligence:gates` at 20, `:desktop:playbackcheck` at 21, both green
- `:desktop:viewcheck` - 17 of 17 played clean in the real viewer, re-run for this release rather tha
## [0.3.3] - 2026-10-10
### Documentation
- **`issues.md` "Env" 6 closed on measurement: the 32x32 figure is wrong, and 48 is not too small.*
- **`issues.md` "Env" 7 investigated; the quote is right and the shipped width is not.*
### Changed
- `TODO.md` gains §0.8 (the inventory window, recorded not fixed) and its header states which suit
- The `README.md` status table row for the observation encoder no longer reads a bare "Verified"; it name
## [0.3.2] - 2026-10-10
### Fixed
- **Watching a recording left a dead hero's belongings in the player's own game.**
### Added
- **One more check in `playbackcheck`**
## [0.3.1] - 2026-10-10
### Fixed
- **A run that descended and then climbed back up diverged from its own recording.**
- **The "Known issue" carried in 0.3.0 is closed.**
### Added
- **Two more `transitioncheck` cases** (7 -> 9).
### Changed
- **`duelist-mid` regenerated**, and only that one.
- **`transitioncheck` added to the gates table**
## [0.3.0] - 2026-10-10
### Fixed
- **Descending ended the episode, so nothing had ever left floor 1.**
- **A hero could pick an item up and put it down forever, and the score would climb.**
- **`STALLED` costs what dying costs.**
- **`rewardcheck` asserted the opposite of its own name.**
- **A recording's `depth` header was the depth the hero died at, not the depth it reached.*
- **The viewer stalled on a descent instead of yielding.**
- **The viewer read a floor back out of a save it never wrote.**
- **`ViewCheck --one` was not muted.**
### Added
- **`gradle :superintelligence:transitioncheck`**, a gate, 7 cases.
- **`RewardTerm.ITEM_DROPPED`**, and a `rewardcheck` case for the exploit it closes.
- **`LevelPipeline.game()`**
### Known issue
- ~~**Ascending after descending still diverges, and `verifyall` is red because of it.**~
### Changed
- **The corpus is regenerated and no longer entirely depth 1.**
- **`viewcheck` is 16 of 17**
## [0.2.0] - 2026-10-10
### Added
- **The viewer's HUD can be moved, and now defaults to the top left.**
### Fixed
- **The viewer, five faults from `superintelligence/issues.md` "Viewer / recordings".**
- **The viewer HUD ran off the right edge of the screen.**
- **The viewer and the trainer diverged on `cleric-mid`, and nothing had noticed.**
### Added
- **The committed corpus is now a playback gate.**
- **Seven new checks in `playbackcheck`**
- **One more check in `playbackcheck`** (18 -> 19), covering the HUD wrap.
- **One more check in `playbackcheck`**
- **`gradle :superintelligence:viewdiff`**
- **`# generationSites=` in every RNG trace:**
- **`RandomTrace` frame and gate records:**
- **`PRandom.element`**
- **`viewcheck` gate**
- **`WorldSnapshot` and `WorldDiff`**
- **`FrameDelta`**
- **`Actor.currentActor()`, `Mob.currentEnemy()`, `Mob.enemySeen()`, `Mob.alerted()` an
- **Parallel minibatch update** (`--update-threads N`, default 1).
- **`rewardcheck`**
- **Termination reasons reported per generation.**
- **`parallelcheck`**
- **The LSTM state now travels with each sampled transition**
- **`statecheck`**
- **`replayprobe`** and **`valuescale`**, probes rather than gates.
- **`PLAN-replay-verification.md`**
- **`rngtrace`**
- **`rollout` with no `--seed` works again, and a random-seed recording is now verifiable.**
- **The rendered game and the headless environment draw from different positions in the RNG stream.*
- **`playbackcheck` gained a check for non-default header settings, and both gates now count faile
- **`gradlew verifyall`**, every gate in both modules in one invocation, ~14 s.
- **`playbackcheck`**
- **Known limitation, found by `rngtrace`: a run that ended on the soft stall guard replays as 
- **`observecheck`**, a gate asserting that *reading* the world draws no randomness.
- **A recording now says how wide its slot head was.**
- **Policy checkpoints, so a training run can be stopped and continued.**
- **`checkpointcheck`**
- **`weightsdiff <file>`**, reporting how far a checkpoint is from a freshly initialised policy.
- **Per-generation metrics history**
- **`--sample-rate`, `--max-sampled-per-episode`, `--max-samples` and `--metrics`** on the trainer.
- **A TODO entry for the thing that will block the first real training run: the weights are neve
- **`gradle :superintelligence:gaecheck`**
- **The generation report prints what it is for.**
- **`gradle :superintelligence:updatecost`**
- **Reported gradient norms and clip fraction,** per minibatch and averaged.
- **`gradle :superintelligence:modecheck`**
- **`gradle :superintelligence:restartcheck`**
- **`replay-viewer.bat`**
- **Replay fixtures in `replays/`,**
- **Diagnostics on stderr.**
- **Desktop replay viewer.**
- `PPO.rolloutCap` bounds collection between updates.
- `gradle :superintelligence:gradcheck` (or `gradcheck --verbose`) finite-difference checks th
- **`paritycheck`, a replay-parity sweep across seeds in one process.**
- **`configcheck`, which asserts the configuration layer is capable of configuring anything.**
- **`RunState`, the one place that lists what a new run must not inherit.**
- **`docs/documentation.md`**
### Changed
- **`viewcheck`'s `GATE_SPEED` of 40 was silently clamped to 16**
- The documented cause of the viewer divergence was **wrong**, and is corrected i
- **`superintelligence/FINDINGS-viewer-fidelity.md`**
- **Each refuted hypothesis is kept with both what predicted it and what the run showed.**
- **`STALLED` is a truncation and only `DEATH`/`VICTORY` are natural endings**
- **`PLAN-data-flow.md` corrected against the code**
- **Ordering corrected: instrumentation now precedes the parallel update.**
- **The reward curriculum is recorded as deliberately inert.**
- **Every episode ended `STALLED` after 8-13 turns,**
- **`MENU` mode was unreachable in a real run,** not merely hard to record.
- **The `MENU` branch never returned to `WORLD`,**
- **`WindowBridge.open()` meant "a window is showing" rather than "a dialog the agent can answer."*
- **`SLOT` had no entry in the action mask**
- **`EnvMode.INVENTORY` was unreachable.**
- **Throwing stones were equipped rather than thrown.**
- **Every equipment change invented a targeting step.**
- **A refused equip stalled the episode.**
- **No aim could ever be resolved or cancelled.**
- **The replay viewer could leave the game completely unresponsive.**
- **`lockCellInput` did not lock the keyboard.**
- **Viewer keys could not override a game binding.**
- **`R` did not restart.**
- **Viewer controls died after `R`.**
- **The replay HUD vanished after `R`,**
- **Resuming a diverged replay re-reported the same divergence forever.**
- **On-screen replay divergence could never fire.**
- **`GameScene` gained `topWindow()`.**
- **The libGDX natives were never loaded in any headless JVM,**
- **`BitmapText` threw on every measuring call with no font.**
- **Both of the above were live crashes on item use, not latent ones.**
- **Global gradient clipping did not exist.**
- **A recording of a random-seed episode could never be replayed or verified.**
- **The reported losses were running sums, not means.**
- **Clipping now happens after the minibatch average.**
- **`gradle :superintelligence:train` rejected its own arguments.**
- **A worker that acknowledged a policy push with the wrong message went unnoticed.**
- **A worker that replied to an episode request with the wrong message went unnoticed**
- **`gradle :superintelligence:collectcheck`**
- **`gradle :superintelligence:replays`**
- **`gradle :superintelligence:replaycheck`**
- **`Replay.declaredSteps` and `Replay.truncatedAt`**
- **A recording is selected by number.**
- **`--epochs`, default 2 instead of 4.**
- **`gradle :superintelligence:gates` runs every correctness check in one invocation.**
- **Workers compute advantages and ship a sample of them; the trainer runs a pooled update on it.*
- **One forward pass per step, not two.**
- **gamma, lambda, sampleRate and maxSampledPerEpisode travel with the weights.**
- **`PPO` no longer collects.** `collect()` drove the env, the network and the masks, and `update()`
- **The worker keeps its transitions on a pool; the trainer allocates.**
- **A per-generation memory cap on the update buffer,**
- **The trainer is split into five classes.**
- **Worker pipe buffers are 1 MB, not the 8 KB default.**
- **The generation report prints the transition count.**
- **The learner and the trainer are split by responsibility, both of which were over the 500-lin
- **`resetcheck` grew a case per leaked static** (3 checks -> 5).
- **`testing-guide.md` names `gates` rather than `verifyall`**
- **A pre-commit hook**
### Changed
- **Documentation corrected against the source.**
### Fixed
- **The rendered viewer played the game with different randomness, so recordings that verified exactl
- **The RNG trace could not see the draws that caused it.**
- **`WorldDiff` never named the hero field that differed.**
- **`regenerate-corpus.ps1` appended the freshly compiled classes after the stal
- **`viewcheck` reported only a step index,**
- **`replay-viewer`'s answer to a recorded `MENU` step was against nothing.**
- **`ReplayRecorder.inventory()` ignored the contents of bags,**
- **`ReplayPlayer` discarded whether the engine accepted a recorded step,**
- **The rendered viewer took over the desktop on every gate run.**
- **`viewcheck` took six minutes and reported differently each run.**
- **`ReplayPlayer` frame counters reported zero whenever tracing was off**
- The `readyToAct()` step-gate hypothesis was measured and **refuted** before implementing it.
- ~~14 of 17 recordings still diverge in the rendered viewer, deterministically and at identical steps.~
- **Merged upstream v4.0.2** (10 commits from `00-Evan/shattered-pixel-dungeon`).
- **`--out` did not move the checkpoint, or the metrics history.**
- **A dead hero's remains were inherited by the next run, and 2 of 32 recordings stopped reproducing.*
- **The configuration that decides a run's settings was hardcoded in five classes, and two of th
- **Sound-effect pitch was drawn from the gameplay stream.**
- **The viewer spent a turn on steps that owe none.**
- **The viewer skipped the trainer's per-step preamble, so a throw never landed.**
- **Four headless crashes on paths a longer run now reaches.**
- **Presentation randomness was consuming the game's stream.**
- **Encoding an observation was consuming the game's randomness.**
- **Headless sprites had no particle emitter.**
- **The viewer's drain yields to the render loop.**
- **The trainer settled a turn before the game did.**
- **Headless crashed on a surprise attack.**
- **Every vertical direction was inverted.**
- **`OPEN_INVENTORY` also acted on the world.**
- **The replay viewer could not find most recordings.**
- **A divergence report said only what was expected, never what happened.**
- **The trend chart reported a range it never observed, and a depth-2 episode was reported that neve
- **A reset was not isolated from the previous episode, so a second recording in one process wa
- **Every death blew the stack, so `DEATH` was unreachable.**
- **Headless crashed on any texture that had no GL context.**
- **`REST` ended every episode that used it.**
- **`clip=` reported a constant, not PPO's clip fraction.**
- **The reward paid the agent to give up on an episode.**
- **`WAIT`, `REST` and `SEARCH` were reported as `INVALID_ACTION`.**
- **`INVALID_ACTION` was unobservable.**
- **`SPDEnv.terminate` was not idempotent, and one caller relied on that.**
- **Gradients leaked between samples.**
- **The update was optimising a different objective than PPO.**
- **`Network.state()` returned the hidden state only.**
- **The critic could not represent its own targets.**
- **Samples were coupled**, sample *i* depending on sample *i-1*.
- **`Protocol.VERSION` is still 1**
- **An uncapped PPO rollout was an out-of-memory crash waiting to happen.**
- Three headless crashes that the scripted policy never reached but a random one walks int
- **The policy network's gradients were wrong in five separate ways, so training optimised 
- **More identity-hash iteration in gameplay and level generation.**
- **A seed longer than 20 characters was silently discarded**
- `gradle :superintelligence:rollout` and `:verify` never passed their own subcommand to th
### Known limitations
- **The viewer fidelity fix is an engine change and is not in this commit.**
- **`viewcheck` is outside `gates` and stays there.**
- **A replay recorded before this change with an empty `seed=` cannot be verified.**
## [0.1.0] - 2026-10-06
### Fixed
- **The environment is now reproducible across processes.**
- `GameScene.add(Heap)` left `heap.sprite` null when there is no scene, so trampling high gras
- `Image.frame` derived its size from a null texture when there is no renderer
### Added
- **Headless training framework** (`superintelligence` module).
### Changed
- `ItemSpriteSheet.Icons` builds its `TextureFilm` on first use instead of in a static initialiser
- `CharSprite` gains `sprint`, `updateArmor`, `read` and `fall` as overridable no-ops, hoisted fro
- `AttackIndicator.target`/`updateState`, `QuickSlotButton.target`, `GameScene.selectCell`
- `TextureCache.getBitmap` returns null without a GL context, and `TextureCache.get` no longe
### Added (game)
- `Actor.headlessStep()` - one turn-scheduler iteration with no render-thread handshake, fo
- `GameScene.show` records a dialog when there is no scene; `GameScene.pendingCellListener` record
- `Item.throwAt()` - the bookkeeping half of `Item.cast`, without a projectile sprite
### Documentation
- `superintelligence/TODO.md` - status against the design documents: what still needs doing, ordere
### Renamed
- `ReplayIO.play` is now `ReplayIO.verify`, and `ReplayIO.Result` is `ReplayIO.Verification`.
### Measured: the noise floor, and what it retires
### Fixed: keep tracing past a divergence, and forward `-PspdHidden`
### Fixed: `ValueScale` described a fault that no longer exists
### Closed: twenty of thirty config fields never reached the worker processes
### Fixed: the free-step budget was ending every episode at 8% of its granted turns
### Fixed: the policy push no longer depends on a single 14MB pipe write succeeding
