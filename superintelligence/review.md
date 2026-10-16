# Shattered Pixel Dungeon Superintelligence - Comprehensive Code Review and Assessment

## Executive Summary

**Project Overview:** The `:superintelligence` module (version `0.10.0`, wrapping Shattered Pixel Dungeon `4.0.2`) is a headless reinforcement-learning framework designed to train an autonomous agent to descend floors and defeat bosses (starting with Goo at depth 5) in a 26-floor permadeath roguelike. Because the underlying game relies heavily on process-wide `static` state (`Dungeon`, `Actor`, `Level`, `Random`), the project bypasses the libGDX/noosa renderer (`Gdx.gl` left null) and scales rollout collection across isolated worker JVMs communicating with a central PPO learner over stdin/stdout pipes.

**Key Achievement:** The engineering rigor around simulation determinism and mutation-tested verification gates is exceptional. The project includes 30 automated headless gates (`:superintelligence:gates`) and a rendered desktop verification suite (`:desktop:viewcheck`), which uncovered subtle cross-process bugs such as identity-hash turn-order ties, presentation RNG draws leaking into the gameplay RNG stream, static target-listener leaks across resets, and unnormalized critic gradients.

**Overall Impression:** As a deterministic simulation harness and testing infrastructure, the codebase is mature, disciplined, and thoroughly instrumented. However, **as a reinforcement learning training pipeline for a partially observable, long-horizon roguelike, the current architecture is fundamentally bottlenecked and has not yet learned to play the game**. Across a 30-generation benchmark run (8 workers x 4 episodes, 12 minutes 41 seconds), `meanScore` moved only +1.6 points (`-3,535.7` to `-3,534.1`) against a per-generation standard deviation of 98 to 138, with 98% to 99% of training episodes terminating via `STALLED` and `meanDepth` peaking at `1.062`.

---

## 1. Architectural & Design Review

### Strengths

* **Renderer Bypass and State Isolation (`headless` & `env.RunState`):** Instead of fragile UI mocking, `HeadlessGame`, `HeadlessServices`, `HeadlessGraphics`, and `HeadlessSprite` leave `Gdx.gl` null and make all presentation paths null-tolerant while preserving exact gameplay logic and RNG consumption. `RunState` explicitly clears static remnants (`Bones.clear()`, `GameScene.pendingCellListener`, active dialogs, and `InterlevelScene.mode`) prior to floor generation so sequential episodes in one JVM do not contaminate one another.


* **Concurrency and Memory-Bounded Worker Data Flow (`rl.EpisodeCollector` & `rl.ShardedUpdate`):** Computing Generalized Advantage Estimation (GAE) on the worker using lightweight scalar arrays (~21 bytes per step) avoids shipping 2.4 GB of raw transitions per generation over pipes. Bit-packing the 22-channel 48x48 binary grid (`TransitionCodec`) reduces grid memory 8x from 50,688 bytes to 6,336 bytes losslessly. On the trainer side, `ShardedUpdate` parallelizes minibatch backpropagation across thread-local `Network` copies, achieving a 3.29x speedup at 4 threads (11.29 ms/sample down to 2.73 ms/sample) without shared-memory race conditions.


* **Testing and Maintainability (`diag` & `tools.JavaLint`):** Every subsystem is guarded by a dedicated, mutation-tested verification gate in `build.gradle` (30 gates in `:superintelligence:gates`, including `gradcheck`, `gaecheck`, `bpttcheck`, `parallelcheck`, `paritycheck`, `resetcheck`, `configcheck`, and `javaclint` with `-Xlint:all -Werror`). Source files in `:superintelligence` strictly respect the 500-line (600-line for tests) modularity rule.



### Areas for Improvement

* **Architectural Suitability for Shattered Pixel Dungeon (Is the Pipeline Suited for the Goal?):** The current synchronous CPU-only PPO + 5% Bernoulli subsampling pipeline is **poorly suited** to solving a combinatorially complex, partially observable roguelike without four structural corrections:


1. **5% Per-Step Bernoulli Subsampling Shatters Recurrent BPTT (`rl.sample_rate=0.05` vs. `rl.chunk_length=16`):** `EpisodeCollector` draws an independent 5% Bernoulli sample per step during rollout (`PLAN-data-flow.md` Section 2.1), while `PPO.update` groups retained samples into contiguous runs of up to `rl.chunk_length=16` steps by `(episodeId, stepIndex)` and resets the chunk whenever a step gap occurs (`CHANGELOG.md`, `README.md`). Under independent Bernoulli sampling at $p = 0.05$, the probability of retaining even two consecutive steps outside the 20-step tail is $0.05^2 = 0.25\%$, and the probability of a contiguous 16-step chunk is $0.05^{16} \approx 1.5 \times 10^{-21}$. Consequently, across 98% of an episode, `rl.chunk_length=16` is defeated by the sampler and degrades back to length-1 truncated gradients, starving `LSTM.W_hh` of temporal credit assignment except during the final 20 steps (`rl.tail_steps=20`) when the hero is already dying or stalling.


2. **Extreme Sample Starvation vs. Parameter Count (3.58M Weights on ~2,400 Samples/Gen):** `Network` contains 3,583,827 parameters because a single `Conv2D` layer over a 21x48x48 grid is flattened directly into a `12,696 x 256` `Dense` trunk (`3.25M` parameters in one dense matrix) before a 256->128 `LSTM`. Discarding 95% of transitions leaves only ~2,400 samples per generation to train 3.58M weights on pure-Java CPU loops, where memory bandwidth saturates at 4 threads (14.3 MB gradient reduction per thread per minibatch).


3. **Multi-Step Sub-Mode Action Space Induces 99% `STALLED` Episodes:** Pausing engine time across sub-modes (`WORLD` -> `INVENTORY` / `SLOT` / `TALENT` / `TARGETING` -> `CANCEL` or `USE`) means an exploring policy takes actions that advance `Actor.now()` by zero turns. Despite adding `SPD_ENV_FREE_STEP_BUDGET=250` and a 1-step mask lock after zero-time exits, 98% to 99% of training episodes still terminate early in `STALLED` (`issues.md` 27), flooding the critic and policy with `-3000.0` stall penalties (`meanScore` stuck at `-3,535`) before the agent can discover stairs or combat.


4. **Fixed 48x48 Spatial Window Clips 40% of Dungeon Floors:** `SPD_ENV_GRID_WIDTH=48` and `SPD_ENV_GRID_HEIGHT=48` encode a fixed 48x48 grid, yet empirical measurement across 1,040 floors (`PLAN-reward-signals.md` Section 11.1) shows 419 of 1,040 floors exceed 48 tiles on at least one axis (reaching up to 81 tiles tall), blinding the spatial encoder on large floors unless the grid is ego-centric (centered on `hero.pos`) or downsampled.




* **Recommendation:**
1. Replace per-step Bernoulli subsampling in `EpisodeCollector` with **contiguous block-chunk sampling** (sampling start indices at rate $p / L$ and retaining $L = 16$ consecutive steps per draw) so every sampled transition participates in a full 16-step BPTT chunk.


2. Add spatial downsampling (strided `Conv2D` or 2x2 max-pooling) before the `Dense` trunk to cut parameter count from 3.58M to ~400K, reducing the 14.3 MB gradient-reduction bottleneck by ~9x and allowing 4x to 8x more samples per generation on CPU.


3. Collapse sub-mode navigation (`OPEN_INVENTORY` + `CANCEL`) by masking out sub-mode entry actions unless a valid, progress-making item action exists, or resolve item use/drop/talent selection as a single compound step so random exploration cannot burn `free_step_budget`.





---

## 2. Compliance Checklist

| Requirement | Status | Implementation Details |
| --- | --- | --- |
| **Headless Simulation & Renderer Bypass (`docs.md`)** | Pass | `HeadlessGame`, `HeadlessServices`, and `HeadlessSprite` bypass OpenGL while preserving core turn scheduling (`Actor.headlessStep`).

 |
| **Cross-Process Replay Determinism (`docs.md`)** | Fail (Partial) | `paritycheck` passes on 32 scripted runs, and `:desktop:viewcheck` passes 19/20, but `rolloutcheck` fails 1 in 8 runs on random seeds (`issues.md` 18), `duelist-death-last.replay` diverges by 1 HP in the viewer (`issues.md` 26), and 1 of 2 worker-recorded training replays fails `verify` at step 534 (`issues.md` 37).

 |
| **Agent Learns to Descend Floors / Beat Depth 5 (`research.md`, `AGENTS.md`)** | Fail | Over 30 generations, `meanScore` moved +1.6 points (noise SD 98-138), 98-99% of runs stalled, and no run has ever passed floor 2 (`TODO.md` Section 1).

 |
| **Complete Reward Signal Coverage (`docs.md`)** | Fail (Partial) | `KILL`, `SNEAK_ATTACK`, `TALENT_UPGRADE`, `STAIR_POTENTIAL`, `CURSE_REMOVED`, and `EQUIP_TOO_STRONG` fire, but `CRAFTED`, `CRAFTED_MEAT_PIE`, and `MENU_NOOP` remain unwired (`TODO.md` Section 2).

 |
| **12-Factor Configuration & No Hardcoded Values (`AGENTS.md`, `instructions.md`)** | Fail (Partial) | 64 keys are bound via `EnvConfigBinder` and checked by `ConfigCheck`, but `CURSE_REMOVED` (`1.50`), `EQUIP_TOO_STRONG` (`-2.00`), related secondary reward weights (`TODO.md` Section 2), and `Curriculum` fade depths (`3` and `10`) remain hardcoded Java literals.

 |
| **500-Line File Limit & Modularity (`AGENTS.md`)** | Pass | Oversized files (`RewardCheck`, `ReplayCatalogCheck`, `CollectCheck`, `ConfigCheck`, `Main`) were split by functional boundary; all `:superintelligence` files are under the limit.

 |
| **No Source Comments Policy (`AGENTS.md`)** | Fail | `AGENTS.md` forbids all `//` and `/* */` comments in source code, but `STATUS.md` Section 12.2 notes that lengthy explanatory comments are still written throughout the codebase and `build.gradle`.

 |

---

## 3. Deep Code Review

### `rl` Module (Network, PPO, EpisodeCollector, and ShardedUpdate)

The `rl` package implements a custom pure-Java tensor engine (`Tensor`, `Conv2D`, `Dense`, `LSTM`), `ValueNormalizer` with Huber loss and residual clipping (`rl.value_gradient_clip=10.0`), and multi-threaded minibatch gradient accumulation (`ShardedUpdate`). Moving from isolated 1-step backprop to reverse-order BPTT over contiguous chunks (`rl.chunk_length=16`) and recomputing forward recurrent states during the update fixed major gradient defects verified by `bpttcheck` and `gradcheck`. However, `EpisodeCollector` still performs uniform per-step Bernoulli subsampling (`rl.sample_rate=0.05`), which fragments step indices so that `PPO.update` rarely sees contiguous chunks longer than 1 step outside the 20-step episode tail. Additionally, `Conv2D` im2col allocation and the massive `12,696 x 256` `Dense` layer dominate the 11.29 ms per-sample update cost.

* **Fix/Optimization:** Change `EpisodeCollector` to sample contiguous blocks of `chunkLength` (16) steps rather than independent single steps, and introduce a second strided `Conv2D` layer (stride 2) to reduce the flattened feature dimension from `12,696` to `3,200` before `Dense`.



### `env` & `reward` Modules (SPDEnv, ActionMapper, LevelPipeline, and RewardModel)

`SPDEnv`, `LevelPipeline`, and `RewardModel` translate game state diffs into dense and terminal rewards without invasively modifying hundreds of item classes in `:core`. Recent additions of potential-based stair shaping (`StairPotential`, $\gamma \Phi(s') - \Phi(s)$ with weight `2.0`), `GridChannel.EXIT_BEACON`, `Action.TALENT` / `TalentPicker`, and engine-clock turn pricing (`TURN_COST` scaled by `Actor.now()` delta) closed multiple reward exploits. However, three structural flaws remain:

1. Several reward terms (`CURSE_REMOVED` at `1.50`, `EQUIP_TOO_STRONG` at `-2.00`, `IDENTIFIED` at `0.50`, and item pickup/drop tiers) and `Curriculum` depth boundaries (`depth 3` to `depth 10`) are hardcoded in Java rather than bound through `EnvConfig` / `superintelligence.properties` / `.env`.


2. The 1-step mask lockout after a zero-time sub-mode exit (`freeactioncheck`) prevents immediate `OPEN_INVENTORY` -> `CANCEL` -> `OPEN_INVENTORY` oscillation, but the agent can still interleave one real step or alternate between zero-time equip/unequip actions until `free_step_budget=250` expires, causing 98-99% `STALLED` episodes in training.


3. `CRAFTED` and `CRAFTED_MEAT_PIE` cannot fire because alchemy requires `AlchemyScene.craftItem` (which depends on a renderer), leaving the alchemy objective in `docs.md` unreachable headlessly.



* **Fix/Optimization:** Externalize all remaining reward literals and curriculum floor bounds to `EnvConfig`, `superintelligence.properties`, and `.env.example`, and add a headless alchemy action path in `ActionMapper` / `SlotAction` calling `Recipe.findRecipe` directly without `AlchemyScene`.



### `replay` & `headless` Modules (ReplayIO, WorldSnapshot, and Determinism)

`ReplayIO`, `Quickslots`, and `WorldSnapshot` provide bit-exact step verification for scripted runs, and moving presentation RNG draws (`CharSprite.link`, `AttackIndicator.checkEnemies`, `Wand.staffFx`, `MagesStaff.StaffParticle.update`) to `PRandom` fixed 14 of 17 viewer divergences. Nevertheless, three critical determinism bugs remain open:

1. **Worker vs. Verifier Turn Divergence (`issues.md` 37):** `LZJ-LNO-HLE.dat` diverges deterministically at step 534 (`turn: replayed 13.0, recorded 12.0`) when replaying a trained-policy episode.


2. **Random-Seed Position Divergence (`issues.md` 18):** `rolloutcheck` fails 1 in 8 times at step 54 (`position: replayed 135, recorded 103`), making `gates` flaky.


3. **Uncommitted `Mob.holdAllies` RNG & Viewer Missing Cell Snapshots (`ENGINE-CHANGES.md` Section 7, `issues.md` 26):** `Mob.holdAllies` uses `Collections.shuffle` (JDK RNG) in committed code, 11 sound pitch sites still draw from `Random` instead of `PRandom`, and `ReplayPlayer` omits `cells` records in `WorldSnapshot`, preventing diagnosis of the 1 HP divergence in `duelist-death-last.replay`.



* **Fix/Optimization:** Commit `Random.shuffle(candidatePositions)` in `Mob.holdAllies`, migrate the 11 sound-pitch `Random` calls to `PRandom`, enable `cells` capture in the viewer's `WorldSnapshot`, and log per-step `RandomTrace` fingerprints in `ReplayRecorder` to pinpoint the step-534 worker turn divergence.



### `train` Module (Trainer, WorkerPool, Checkpoint, and EnvConfigBinder)

`Trainer`, `TrainerWorkers`, `WorkerPool`, and `Checkpoint` manage worker lifecycles, chunked weight pushes (`ipc.push_chunk_bytes=32768` with retries tested by `pushcheck`), atomic checkpointing with Adam moments, and `.env` / properties binding. Two implementation flaws stand out:

1. **Seed Schedule Resets on `--resume` (`README.md`):** When resuming a run via `--resume weights.bin`, `Checkpoint` restores generation and optimizer step counters, but `SeedPool` restarts from `--seed` at generation 0 rather than fast-forwarding to the resumed generation.


2. **Naming Inconsistency & Missing `.env.example` Keys:** `CHANGELOG.md` refers to `train.push_chunk_bytes` and `trainer.push_retries`, whereas `.env.example` defines `SPD_IPC_PUSH_CHUNK_BYTES` and omits `SPD_TRAINER_PUSH_RETRIES` (or `SPD_IPC_PUSH_RETRIES`) as well as the hardcoded secondary reward terms.



* **Fix/Optimization:** Persist or fast-forward `SeedPool` state inside `Checkpoint.Meta` upon `--resume`, and synchronize all 64+ property keys into `.env.example` enforced by `ConfigCheck`.



---

## 4. Documentation & UX Quality

* **[README/Docs]:** Documentation (`README.md`, `CHANGELOG.md`, `ENGINE-CHANGES.md`, `PLAN-data-flow.md`, `PLAN-reward-signals.md`, `WORKER-DATA-FLOW.md`, `issues.md`, `TODO.md`, `REJECTED.md`) is extraordinarily candid and empirically grounded, recording failed hypotheses and negative measurements alongside fixes. However, `README.md` contains contradictory prose accumulated across edits: for example, under "Read this first" it states both *"Nothing has reached depth 2"* and later in the same section *"bestDepth 2 in 9 of 30 generations"* and *"the first depth-2 run in this project's history"*, and under "Known approximation" it still states *"the recurrent gradient is truncated at length one"* even though the status table above it states *"The recurrent gradient is no longer truncated at length one"* (`rl.chunk_length=16`). Additionally, `README.md` contains corrupted markdown strings (`l.chunk_length`, `pttcheck`, `alueLoss`, `alue=0.62, 1.06, .56`, `eward.stair_potential_weight`) where backticks or first characters were stripped.


* **[Visuals]:** The console UX (`diag.RunReport`, `diag.Graph`, `train.ProgressBar`, `diag.Ansi`) provides clear ASCII trend bars, per-floor reward ledgers, and progress bars without emojis. The graphical libGDX trainer dashboard and real-time reward monitor specified in `docs.md` and `research.md` remain unbuilt (`TODO.md` Section 3.1 & 3.3, deferred in `REJECTED.md`).


* **[Developer Experience]:** PowerShell helper scripts (`train.ps1`, `regenerate-corpus.ps1`, `replay-batch.ps1`, `replay-viewer.bat`), automatic `.env` loading in `EnvConfigBinder`, canonical path resolution from the repository root, and the unified `./gradlew :superintelligence:gates` task (~30 seconds for 30 gates) provide a smooth local workflow. However, the flakiness of `rolloutcheck` (failing 1 in 8 runs, `issues.md` 18) combined with a strict pre-commit hook (`hooks/pre-commit`) that runs `verifyall` means developers face random pre-commit rejections unrelated to their edits.



---

## 5. Critical Issues and Actionable Fixes

### Issue 1: Per-Step Bernoulli Subsampling Breaks Contiguous Chunked BPTT

**Location:** `src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/rl/EpisodeCollector.java` and `PPO.java`


**Problem:** `EpisodeCollector` samples transitions independently at each step with probability `sampleRate = 0.05` (except for the final `tailSteps = 20`), while `PPO.update` builds recurrent BPTT chunks of length `chunkLength = 16` only when sampled steps have contiguous `stepIndex` values. Because independent 5% Bernoulli draws have only a $0.05^2 = 0.0025$ chance of picking two adjacent steps, 95%+ of non-tail samples have no adjacent neighbor and are backpropagated as isolated length-1 chunks, defeating `rl.chunk_length=16` and preventing `LSTM.W_hh` from learning long-range memory across floors.
**Fix:**

```java
package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

public final class ContiguousChunkSampler {

    private int remainingInBlock = 0;

    public boolean shouldSample(int stepIndex, int totalSteps, int tailSteps,
                                float sampleRate, int chunkLength,
                                int retainedCount, int maxSampledPerEpisode,
                                Random rng) {
        if (retainedCount >= maxSampledPerEpisode) {
            return false;
        }
        if (totalSteps > 0 && stepIndex >= totalSteps - tailSteps) {
            return true;
        }
        if (sampleRate <= 0f) {
            return false;
        }
        if (sampleRate >= 1f) {
            return true;
        }
        if (remainingInBlock > 0) {
            remainingInBlock--;
            return true;
        }
        int effectiveChunk = Math.max(1, chunkLength);
        float blockStartProb = Math.min(1f, sampleRate / effectiveChunk);
        if (rng.nextFloat() < blockStartProb) {
            remainingInBlock = effectiveChunk - 1;
            return true;
        }
        return false;
    }
}

```

### Issue 2: Hardcoded Reward Weights and Curriculum Boundaries Bypassing `.env` and `superintelligence.properties`

**Location:** `src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/env/EnvConfig.java`, `EnvConfigBinder.java`, `reward/RewardModel.java`, `reward/Curriculum.java`, and `.env.example`


**Problem:** `TODO.md` Section 2 notes that `CURSE_REMOVED` (`1.50`), `EQUIP_TOO_STRONG` (`-2.00`), and related secondary reward weights (`IDENTIFIED` `0.50`, `ITEM_PICKUP`, `ITEM_DROPPED`), along with `Curriculum` fade start/end depths (`3` and `10`) and `trainer.push_retries`, are hardcoded Java literals or missing from `.env.example`, violating the project's 12-factor configuration rule in `AGENTS.md` and preventing `ConfigCheck` from verifying them.
**Fix:**

```java
package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

public final class EnvConfig {

    public double curseRemovedReward = 1.50;
    public double equipTooStrongPenalty = -2.00;
    public double identifiedReward = 0.50;
    public int curriculumFadeStartDepth = 3;
    public int curriculumFadeEndDepth = 10;
    public int ipcPushRetries = 3;

    public void bindAdditionalSettings(java.util.function.Function<String, String> lookup) {
        String curseVal = lookup.apply("reward.curse_removed_reward");
        if (curseVal != null) this.curseRemovedReward = Double.parseDouble(curseVal);

        String equipVal = lookup.apply("reward.equip_too_strong_penalty");
        if (equipVal != null) this.equipTooStrongPenalty = Double.parseDouble(equipVal);

        String identVal = lookup.apply("reward.identified_reward");
        if (identVal != null) this.identifiedReward = Double.parseDouble(identVal);

        String fadeStart = lookup.apply("reward.curriculum_fade_start_depth");
        if (fadeStart != null) this.curriculumFadeStartDepth = Integer.parseInt(fadeStart);

        String fadeEnd = lookup.apply("reward.curriculum_fade_end_depth");
        if (fadeEnd != null) this.curriculumFadeEndDepth = Integer.parseInt(fadeEnd);

        String retries = lookup.apply("trainer.push_retries");
        if (retries != null) this.ipcPushRetries = Integer.parseInt(retries);
    }
}

```

### Issue 3: Seed Schedule Resetting to Generation 0 on Checkpoint `--resume`

**Location:** `src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/train/Trainer.java` and `SeedPool.java`


**Problem:** As documented in `README.md`, when resuming a training run with `--resume <file>`, `Checkpoint` restores the network weights, Adam moments, optimizer step count, and generation index, but `SeedPool` re-initializes from `--seed` without advancing to the resumed generation, causing the resumed run to replay the exact same seed sequence from generation 0.
**Fix:**

```java
package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

public final class SeedPoolResumeFix {

    public static void fastForwardSeedPool(SeedPool pool, int startGeneration, int episodesPerGeneration) {
        for (int gen = 0; gen < startGeneration; gen++) {
            for (int ep = 0; ep < episodesPerGeneration; ep++) {
                pool.nextSeed(gen, ep);
            }
        }
    }
}

```

### Issue 4: Missing Cell Snapshots in Viewer `WorldSnapshot` Blocking Issue 26 Diagnosis

**Location:** `src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/replay/WorldSnapshot.java` and `:desktop` `ReplayPlayer.java`


**Problem:** `issues.md` 26 documents that `:desktop:viewcheck` fails on `duelist-death-last.replay` at step 94/95 because the rendered viewer applies 1 HP of damage not present in the headless recording, and `viewdiff` cannot identify the source because `ReplayPlayer` constructs its `WorldSnapshot` with cell recording disabled (writing zero `cells` records).
**Fix:**

```java
package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

public final class ViewerWorldSnapshotBuilder {

    public static WorldSnapshot captureWithCells(int stepIndex, String phase) {
        Level level = Dungeon.level;
        boolean includeCells = (level != null);
        return WorldSnapshot.capture(stepIndex, phase, includeCells);
    }
}

```

---

## 6. Recommended Quality of Life (QoL) Features

### Feature 1: Spatial Downsampling Conv Trunk + Java Vector API (`jdk.incubator.vector`) SIMD Acceleration

**Benefit:** Currently, `Network` has 3,583,827 parameters because a single `Conv2D` layer over the 48x48 grid flattens into a `12,696 x 256` `Dense` layer (3.25M weights), causing a 14.3 MB gradient reduction per thread per minibatch that saturates memory bandwidth past 4 threads (`PLAN-data-flow.md` Section 4). Adding a stride-2 `Conv2D` reduction layer and vectorizing `Dense.forward` / `Dense.backward` using the built-in JDK Vector API (`FloatVector`) keeps the project 100% pure Java (respecting `REJECTED.md`'s avoidance of external native/ONNX dependencies) while cutting parameter count and gradient-reduction bandwidth by ~4x to 8x.
**Draft Implementation:**

```java
package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class SimdMatVec {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    public static void matVecAdd(float[] weights, float[] input, float[] bias, float[] output, int rows, int cols) {
        int upperBound = SPECIES.loopBound(cols);
        for (int r = 0; r < rows; r++) {
            int rowOffset = r * cols;
            FloatVector acc = FloatVector.zero(SPECIES);
            int c = 0;
            for (; c < upperBound; c += SPECIES.length()) {
                FloatVector wVec = FloatVector.fromArray(SPECIES, weights, rowOffset + c);
                FloatVector xVec = FloatVector.fromArray(SPECIES, input, c);
                acc = wVec.fma(xVec, acc);
            }
            float sum = acc.reduceLanes(VectorOperators.ADD) + bias[r];
            for (; c < cols; c++) {
                sum += weights[rowOffset + c] * input[c];
            }
            output[r] = sum;
        }
    }
}

```

### Feature 2: Per-Step RNG Fingerprint Recording in `Replay.Step` to Diagnose Issue 18 and Issue 37

**Benefit:** `issues.md` 18 (`rolloutcheck` failing 1 in 8 runs on position at step 54) and `issues.md` 37 (worker-recorded `LZJ-LNO-HLE.dat` diverging by 1 engine turn at step 534) occur because when a worker recording diverges during `verify`, `ReplayIO.verify` only sees that `turn` or `pos` diverged, not *whether* the RNG stream (`RandomTrace.baseDraws()`) diverged on an earlier step. Recording `RandomTrace.baseDraws()` in `Replay.Step` makes `verify` immediately report the exact step where an extra RNG draw occurred versus where a deterministic scheduler branch differed.
**Draft Implementation:**

```java
package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

public final class StepRngAssertion {

    public static String checkRngParity(int stepIndex, long recordedDraws, long replayedDraws) {
        if (recordedDraws >= 0 && recordedDraws != replayedDraws) {
            return String.format(
                "rng baseDraws diverged at step %d: replayed %d, recorded %d (delta %+d)",
                stepIndex, replayedDraws, recordedDraws, (replayedDraws - recordedDraws)
            );
        }
        return null;
    }
}

```

### Feature 3: Sub-Mode Pre-Validation Masking to Eliminate `FREE_STEP` Stall Loops

**Benefit:** `issues.md` 27 shows 98% to 99% of training episodes terminate as `STALLED` via `free_step_budget` because the agent repeatedly enters sub-modes (`OPEN_INVENTORY`, `USE`, `DROP`) when it has no advantageous item action or cancels out of them. Masking `USE`, `DROP`, and `TALENT` in `ActionMapper` so they are only legal when at least one non-cancel slot action is executable, and masking `CANCEL` when a direct world action was available, prevents random policies from burning 250 free steps per floor.

---

## 7. Conclusion

**Overall Score: 78/100**

**Top Strengths:**

1. **Rigorous Verification and Determinism Discipline:** The 30-gate `:superintelligence:gates` suite, mutation testing of every check, and systematic isolation of static engine state (`RunState`, `PRandom`, insertion-ordered collections) represent top-tier testing engineering.


2. **Pragmatic Distributed Systems Design:** Worker-side scalar GAE computation, 8x lossless bit-packing of spatial observations, and thread-sharded minibatch gradient accumulation (`ShardedUpdate`) cleanly solve JVM process isolation and IPC bandwidth constraints.



**Top Priorities for Improvement:**

1. **Fix the Sampler-BPTT Mismatch and Network Bottleneck (`rl`):** Replace 5% per-step Bernoulli subsampling in `EpisodeCollector` with contiguous 16-step block sampling so `rl.chunk_length=16` actually backpropagates through time across the floor, and downsample the spatial grid before the `12,696 x 256` `Dense` layer.


2. **Resolve Open Replay Divergences and 99% Stall Termination (`issues.md` 18, 26, 27, 37):** Close the worker-vs-verifier turn divergence (`issues.md` 37) and random-seed rollout flakiness (`issues.md` 18), externalize the remaining hardcoded reward/curriculum literals (`TODO.md` Section 2), and tighten sub-mode action masking so episodes stop terminating at 99% `STALLED`.



**Final Verdict:** The simulation harness, headless renderer bypass, and verification gates are production-grade, but the RL training pipeline is **not yet ready for production training runs** until contiguous block sampling, sub-mode stall elimination, and the open replay parity bugs (`issues.md` 18, 26, 37) are resolved.