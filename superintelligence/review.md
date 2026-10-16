# Shattered Pixel Dungeon: Superintelligence - Comprehensive Code Review and Assessment

## Executive Summary

**Project Overview:** The `superintelligence` module is a custom, pure-Java headless reinforcement learning (RL) framework built around Shattered Pixel Dungeon (v4.0.2). Its goal is to train a Convolutional Neural Network (CNN) and Long Short-Term Memory (LSTM) Actor-Critic policy via Proximal Policy Optimization (PPO) across parallel worker JVMs to descend floors, spend talent points, defeat bosses, and win the game.

**Key Achievement:** The headless engine bypass (`headless/*`, `env/LevelPipeline`, `env/RunState`) and deterministic replay verification suite (`replay/*`, `diag/*` with 24 automated correctness gates) represent a major engineering feat. Isolating a heavily static, render-coupled libGDX game loop so that rollouts execute at 3,000 to 8,500 turns per second while maintaining RNG parity with the desktop viewer is exceptionally well-executed.

**Overall Impression:** While the simulation harness, diagnostic tooling, and testing discipline are mature, **the reinforcement learning pipeline and action-environment interface are architecturally and implementationally broken**. Currently, 98% to 99% of all episodes terminate in `STALLED` around turn 120 on Floor 1, and the agent never learns to reach the exit stairs or spend talent points. As detailed below, this failure is caused by five compounding non-reward defects:

1. **100% Free-Step Budget Exhaustion:** Roughly 90% of available actions in the multi-step UI mode hierarchy (`OPEN_INVENTORY`, `CANCEL`, `USE` mode switches, and a broken `REST` implementation that advances zero engine time 99.1% of the time) consume zero game turns, tripping `freeStepBudget` (`FREE_STEP` stall guard) before the hero can traverse Floor 1.


2. **Broken Recurrent Credit Assignment (BPTT = 1 + 5% Random Subsampling):** Workers discard 95% of episode steps via uniform Bernoulli sampling and shuffle the remaining isolated steps into minibatches, backpropagating with a truncation length of 1 (`dCell` and `dPrev` zeroed every sample). The LSTM receives zero gradient across time steps and cannot learn spatial-temporal memory.


3. **Critic Explosion from Unnormalized Returns:** With rescaled terminal penalties (`stallPenalty = 3000`, `deathPenalty = 2500`, `depthReward = 1800`) and no return normalization or value target scaling, `valueLoss` explodes to 12,432 - 31,323, saturating the shared trunk gradients.


4. **Missing Talent Point Action:** Spending talent points exists only in the desktop UI (`TalentButton` / `TalentsPane` / `WndHero`); `ActionMapper` and `WindowBridge` provide no headless action to spend talent points.


5. **Heuristic Interaction & Inventory Truncation:** `INTERACT` relies on a hardcoded adjacency heuristic (`pickInteractCell()`) rather than directional targeting, and `EnvConfig.maxSlots = 32` truncates the game's 96-slot bag capacity.



---

## 1. Architectural & Design Review

### Strengths

* **Renderer Bypass over Mocking (`headless/*`):** By leaving `Gdx.gl` null and guarding texture/sprite paths via `HeadlessGame`, `HeadlessServices`, and `HeadlessSprite`, the simulation avoids OpenGL overhead and guarantees rollouts cannot accidentally depend on frame rendering.


* **Process-Level Isolation for Static Engine State (`train/WorkerPool`, `env/RunState`):** Because Shattered Pixel Dungeon stores world state in globals (`Dungeon.hero`, `Dungeon.level`, `Actor.all`, `Random`), running one environment per worker JVM and centralizing static cleanup in `RunState` prevents cross-episode memory corruption.


* **Property-Based Gate Suite (`diag/*`):** The 24 verification gates (`gradcheck`, `gaecheck`, `parallelcheck`, `paritycheck`, `resetcheck`, `stallcausecheck`, etc.) are mutation-tested and execute in a single ~23-second Gradle invocation.



### Areas for Improvement

* **1. Architectural Suitability: Is the Current Pipeline Suited for Shattered Pixel Dungeon?**
* **Disjoint Step Subsampling vs. Partial Observability:** Shattered Pixel Dungeon is a long-horizon, partially observable Markov decision process (POMDP) where floors last up to 1,500 turns and full runs span 26 floors. To avoid shipping 2.4 GB of unpacked observations per generation over `stdin`/`stdout`, the architecture adopted "Option C": keeping GAE scalars for all steps in the worker, but shipping only a **5% uniform random subsample** of observations (plus a 20-step tail) to the trainer. In PPO, shuffling isolated single steps and running Backpropagation Through Time (BPTT) of length 1 means **the LSTM is architecturally incapable of learning multi-step memory** (such as remembering where the exit stairs were seen, which rooms were explored, or kiting enemies).


* **Pure-Java CPU Matrix Math Bottleneck (`rl/Conv2D`, `rl/Dense`, `rl/LSTM`):** Training a 3.58M-parameter CNN+LSTM in pure Java on CPU takes 11.29 ms per sample on one thread and 2.73 ms across 4 threads. Scaling past 4 update threads yields almost no speedup (2.59 ms at 8 threads) because reducing a 14.3 MB gradient buffer per thread per minibatch saturates memory bandwidth. This CPU bottleneck is what forced the 5% subsample compromise and 2-epoch limit in the first place.


* **Modal Multi-Step Action Space (`WORLD`, `SLOT`, `INVENTORY`, `TARGETING`, `MENU`):** Turning a single item use into a multi-step state machine transition (`WORLD -> USE -> SLOT -> TARGETING`) where intermediate steps advance zero game time causes an untrained or exploring policy to spend **90% of its actions on zero-time UI bookkeeping**. Combined with `freeStepBudget`, the environment kills 99% of episodes before the agent ever reaches a staircase.


* **Recommendation:** Replace uniform 5% single-step subsampling and 1-step truncated gradients with **contiguous sequence chunking** (e.g., chunks of $T = 16$ or $32$ consecutive steps with burn-in hidden states and full BPTT across the chunk). Bit-pack the binary spatial grid (reducing observation size 8x from 48,384 B to 6,048 B) and use memory-mapped files (`MappedByteBuffer`) instead of `stdin`/`stdout` pipes so 100% of rollouts (or full contiguous trajectories) can be trained on using an ONNX Runtime / LibTorch GPU/SIMD backend. Collapse modal UI transitions or mask out no-op mode switches so every step corresponds to a meaningful game decision.




* **2. Hardcoded Values That Should Be Moved to `.env` / `superintelligence.properties`:**
* **Worker JVM Heap & GC Flags (`build.gradle` / `WorkerPool`):** `-Xms256m`, `-Xmx1536m`, and `-XX:MaxGCPauseMillis=200` are hardcoded in `build.gradle` (`trainerJvmArgs`), risking swap thrashing on machines with a small pagefile when scaling workers. Move to `SPD_WORKER_XMS`, `SPD_WORKER_XMX`, and `SPD_WORKER_GC_PAUSE_MS`.


* **Tail Retention Length (`EpisodeCollector` / `PLAN-data-flow.md` §5):** The collector hardcodes retaining the last $K = 20$ steps of every episode. Move to `SPD_RL_TAIL_STEPS=20`.


* **Gradient Clipping Norm (`rl/Network.java`):** `Network.gradClip = 0.5f` and Adam hyperparameters ($\beta_1 = 0.9$, $\beta_2 = 0.999$, $\epsilon = 10^{-8}$) are hardcoded inside `Network`. Move to `SPD_RL_MAX_GRAD_NORM=0.5`, `SPD_RL_ADAM_BETA1`, `SPD_RL_ADAM_BETA2`, and `SPD_RL_ADAM_EPS`.


* **Value Loss & Critic Scaling Coefficients (`rl/PPO.java`):** The value loss weight (`0.5f`) and value clipping toggle are hardcoded in the PPO loss accumulation rather than exposed in `PpoHyperparameters`. Move to `SPD_RL_VALUE_LOSS_COEF=0.5`.


* **SeedPool Random Leak Rate (`train/SeedPool.java`):** `SeedPool` hardcodes a 10% random seed leak (`0.10`), which breaks deterministic A/B benchmarking across training runs (`issues.md` 27). Move to `SPD_TRAIN_RANDOM_SEED_RATE=0.10` (allowing `0.0` for deterministic evaluation).


* **Checkpoint Interval & Record Frequency (`train/TrainOptions.java`):** Default checkpoint interval (`25` generations) and replay recording frequency (`--record-every 1`, previously `3`) are CLI-only defaults not bound in `.env.example`. Move to `SPD_TRAIN_CHECKPOINT_EVERY=25` and `SPD_TRAIN_RECORD_EVERY=1`.


* **Pipe Buffer & Weight Push Chunk Sizes (`train/WorkerPool.java`, `train/TrainerWorkers.java`):** The 1 MB pipe buffer and 32 KB weight push chunk size / retry counts (`issues.md` 28, 29) are hardcoded in Java. Move to `SPD_IPC_PIPE_BUFFER_BYTES` and `SPD_IPC_PUSH_CHUNK_BYTES`.


* **Dialog Aging Step Delta (`env/WindowBridge.java`):** The `0.2f` second elapsed threshold / per-step aging increment for answering prompts like `Chasm.heroJump` (`issues.md` 16) is hardcoded.





---

## 2. Compliance Checklist

| Requirement | Status | Implementation Details |
| --- | --- | --- |
| **Headless Simulation & Determinism** | Pass | Renderer bypassed (`Gdx.gl == null`); hash collections replaced with `LinkedHashSet`/`LinkedHashMap`; presentation RNG isolated to `PRandom`.

 |
| **500-Line File Limit (`AGENTS.md`)** | Fail | `Trainer.java` is documented at 822 lines (exceeding the 500-line limit in `AGENTS.md` and `instructions.md` §8).

 |
| **No Code Comments Rule (`AGENTS.md`)** | Fail | `AGENTS.md` mandates "Write no comments in code", yet `STATUS.md` §12.2 notes the codebase contains lengthy explanatory comments and `build.gradle` defines a `stripinlines` task to strip them.

 |
| **Twelve-Factor Config & `.env` Sync** | Fail | `.env.example` contains stale pre-rescaling values (`SPD_REWARD_TURN_COST=0.002`, `SPD_REWARD_DEPTH_REWARD=25.0`, `SPD_ENV_FREE_STEP_BUDGET=250`) that contradict the rescaled defaults in `README.md` and `CHANGELOG.md` (`turn_cost=1.0`, `depth_reward=1800.0`, `free_step_budget=40`).

 |
| **Hero Can Spend Talent Points** | Fail | `RewardTerm.TALENT_UPGRADE` is wired in `RewardModel`, but no headless action exists in `Action`, `ActionMapper`, or `WindowBridge` to spend talent points.

 |
| **Hero Can Descend & Win via RL** | Fail | 98% to 99% of runs terminate in `STALLED` at ~120 turns via `FREE_STEP` budget exhaustion; `meanDepth` across 5,360 episodes is `1.001`.

 |
| **Full Inventory Representation** | Fail | `EnvConfig.maxSlots` is capped at 32, silently truncating the 96 item slots available with the 4 expansion bags (`issues.md` 7).

 |

---

## 3. Deep Code Review

### RL & PPO Learner Pipeline (`rl/PPO`, `rl/Network`, `rl/EpisodeCollector`, `rl/ShardedUpdate`)

The learner pipeline has undergone important bug fixes (such as fixing `ShardedUpdate` sharing a single `Network` reference across threads in `issues.md` 25, clearing `dCell` in `Network.backward`, and removing `tanh` from the critic head), but **three fundamental implementation flaws currently prevent PPO from learning**:

1. **Truncated BPTT of Length 1 (`rl/Network.java`, `rl/PPO.java`):** During `PPO.update`, each sampled `Transition` restores the rollout's `hPrev` and `cPrev`, executes a single forward step, and calls `network.backward()`. Because `dPrev` and `dCell` are cleared before every sample and transitions are shuffled individually into minibatches of 32, gradients never backpropagate into `hPrev` or `cPrev` across time ($t \to t-1$). Consequently, the LSTM input-to-hidden (`W_ih`) and hidden-to-hidden (`W_hh`) recurrent weights never receive gradients for retaining information over multiple steps.


2. **Stale Hidden States & 5% Subsampling (`rl/EpisodeCollector.java`):** Sampling 5% of steps uniformly at random (`SPD_RL_SAMPLE_RATE=0.05`) plus a 20-step tail means adjacent steps in the training buffer are separated by ~20 turns on average. Furthermore, in Epoch 2 (and after the first minibatch of Epoch 1), the stored `hPrev`/`cPrev` vectors in each `Transition` were generated by old LSTM weights, introducing severe internal covariate shift into the policy logits and value predictions.


3. **Unnormalized Value Targets & Critic Dominance (`rl/PPO.java`, `rl/Policy.java`):** While advantages are normalized batch-wide (`PPO.normaliseAdvantages`), `t.returnValue` is raw. After rewards were rescaled by ~100x (`stallPenalty = 3000`, `deathPenalty = 2500`, `depthReward = 1800`), the critic's squared error loss (`valueLoss`) jumped to **12,432 - 31,323** (`issues.md` 27). Because the critic and actor share the `Conv2D` and `Dense` trunk layers, the massive value gradients overwhelm the policy gradient and cause global gradient clipping (`gradClip = 0.5f`) to squash the actor update to near zero.



* **Fix/Optimization:** Replace single-step sampling with contiguous trajectory segments of length $L = 16$ or $32$, run forward/backward passes sequentially over each segment (accumulating `dPrev` and `dCell` backward from $t = L-1$ down to $0$), and normalize `returnValue` using a running mean/variance tracker (`ValueNormalizer`).



### Environment, Action Mapping & Stall Guards (`env/SPDEnv`, `env/ActionMapper`, `env/WindowBridge`)

This module is the **direct proximate cause of why all runs end in `STALLED` on Floor 1**:

1. **`FREE_STEP` Guard Kills 100% of Runs at ~120 Turns (`env/SPDEnv.java`, `issues.md` 29):** `SPDEnv` tracks steps where `Actor.now()` does not advance (`freeStepBudget = 40` in `CHANGELOG.md`, `250` in `issues.md` 30). Diagnostic profiling in `issues.md` 29 (`stallbreakdown`) proved that **100% of stalls are triggered by `StallCause.FREE_STEP**` (0% by `POSITION`, `PIPELINE`, or `ACTOR_STEP_LIMIT`), because **~90% of steps taken by an exploring policy advance zero engine time**:


* `OPEN_INVENTORY` (100% free) and `CANCEL` (100% free) can be toggled endlessly without advancing `Actor.now()`.


* `USE` in `WORLD` mode (100% free) always transitions to `EnvMode.SLOT` first without spending time.


* `DROP` in `WORLD` mode is 50% free, and `USE` in `SLOT`/`INVENTORY` is 87.9% free.




2. **`REST` Is Broken and Advances Zero Engine Time (`env/ActionMapper.java`, `issues.md` 29, `TODO.md` §7):** To prevent `REST` from trapping the hero in an infinite rest loop (`PLAN-reward-signals.md` §7), `ActionMapper` sets `hero.resting = true; hero.next();` and immediately clears `hero.resting` on the drain/next action. As measured in `issues.md` 29, **323 out of 326 `REST` actions (99.1%) advance zero engine time**, turning `REST` into a free-step trap that both penalizes the agent with `NO_PROGRESS` and increments the `FREE_STEP` stall counter!


3. **Talent Points Cannot Be Spent (`env/Action.java`, `env/ActionMapper.java`, `TODO.md` §7):** In Shattered Pixel Dungeon, reaching Hero Level 2 (after killing two rats on Floor 1) awards a talent point. However, talent upgrades are handled exclusively in GUI classes (`TalentButton`, `TalentsPane`, `WndHero`). Because `WindowBridge` only handles `WndOptions` and `Action` has no `SPEND_TALENT` action or `TALENT` mode, **the hero can never spend a talent point in headless training**.


4. **Heuristic `pickInteractCell()` & Inventory Truncation (`env/ActionMapper.java`, `obs/InventoryEncoder.java`):**
* `INTERACT` does not let the agent choose which adjacent tile to interact with, relying on `pickInteractCell()`.


* `EnvConfig.maxSlots = 32` truncates the 96 possible inventory slots across the 4 bags.


* `Curriculum.observe()` is never called when descending floors (`TODO.md` §2, §5), leaving `shapingScale()` permanently stuck at `1.0f`.





* **Fix/Optimization:** Make `REST` execute a single concrete 1.0-turn wait/rest step (`hero.spendConstant(Actor.TICK)`), dynamically mask out `OPEN_INVENTORY`, `USE`, and `DROP` when they would result in a no-op or immediate loop back from `CANCEL`, and add a headless talent selection mode (`EnvMode.TALENT`) or direct talent upgrade action.



### Distributed Worker IPC & Configuration (`train/TrainerWorkers`, `train/SeedPool`, `env/EnvConfigBinder`)

1. **Fragile Pipe Transport (`train/TrainerWorkers.java`, `issues.md` 28):** Pushing 14.3 MB of weights over `FileOutputStream` (`stdin` pipe) to 20 worker processes caused `IOException: Insufficient system resources exist to complete the requested service` around generation 22 due to Windows kernel anonymous pipe buffer exhaustion. While chunking to 32 KB writes mitigated the single-write crash, streaming 286 MB of weights + 120 MB of transitions over `stdin`/`stdout` pipes per generation remains slow and lacks an automated gate because spawning a worker from a gate JVM crashes inside `HeadlessServices.install` (`TODO.md` §7).


2. **Uncontrolled Variance in `SeedPool` (`train/SeedPool.java`, `issues.md` 27):** `SeedPool` injects 10% random seeds even when a fixed `--seed` is supplied, causing `bestScore` to vary by 1,595 points and `bestDepth` to flip randomly between 1 and 2 on identical configurations.



---

## 4. Documentation & UX Quality

* **README/Docs:** Documentation across `README.md`, `CHANGELOG.md`, `PLAN-data-flow.md`, `PLAN-reward-signals.md`, `ENGINE-CHANGES.md`, and `issues.md` is extraordinarily detailed and candid about refuted hypotheses, but suffers from **severe internal contradictions**. For example, `README.md` claims in one paragraph that "Every episode runs the full 1500 turns with zero stalls" and later corrects itself in another paragraph stating "Measured: 98-99% of every episode ends STALLED"; similarly, `README.md` states `Curriculum.observe()` is called on every floor change while `TODO.md` §2 and §5 state `Curriculum.observe()` is never called.


* **Visuals:** The console diagnostics (`diag/RunReport`, `diag/Graph`, `train/ProgressBar`) provide helpful ASCII charts and color-coded floor breakdowns, and the desktop replay viewer (`:desktop:replay`) includes a movable HUD. However, the planned graphical libGDX trainer UI (`TODO.md` §3.1) and live play reward monitor (`TODO.md` §3.3) have not been started.


* **Developer Experience:** Having `.env.example` not loaded automatically by the JVM (requiring manual PowerShell/bash environment parsing before running Gradle) and letting `.env.example` drift out of sync with `superintelligence.properties` creates a major trap for developers tuning runs.



---

## 5. Critical Issues and Actionable Fixes

### Issue 1: Zero-Time `REST` and Unmasked Sub-Mode Loops Exhaust `freeStepBudget` (Causing 99% `STALLED` on Floor 1)

**Location:** `src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/env/ActionMapper.java` & `SPDEnv.java`


**Problem:** In `ActionMapper.apply`, `REST` sets `hero.resting = true; hero.next();` without spending engine time before the drain clears `resting`, causing 99.1% of `REST` actions to advance 0 engine turns. Simultaneously, `OPEN_INVENTORY` -> `CANCEL` and `USE` -> `CANCEL` loops consume zero engine time, exhausting `freeStepBudget` at ~120 turns in 100% of episodes (`issues.md` 29).
**Fix:**

```java
// In ActionMapper.java:
// 1. Make REST spend exactly one turn of rest (TIME_TO_REST = 1.0f) and check search/rest state,
//    so it advances Actor.now() by 1.0 turn instead of 0.0 turns.
case REST:
    hero.resting = true;
    hero.spendConstant( Actor.TICK );
    hero.search( false );
    hero.next();
    return true;

// 2. In ActionMapper.maskWorld(Hero hero, EnvMode prevMode, Action lastAction, boolean[] mask):
//    Prevent zero-time mode ping-pong by masking out OPEN_INVENTORY and USE immediately
//    after the agent just cancelled out of INVENTORY or SLOT without advancing Actor.now().
if (lastStepWasFreeCancel) {
    mask[Action.OPEN_INVENTORY.ordinal()] = false;
    mask[Action.USE.ordinal()] = false;
}

```

### Issue 2: Missing Headless Action to Spend Talent Points

**Location:** `src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/env/Action.java` & `ActionMapper.java`


**Problem:** The goal requires the hero to spend talent points as they level up, and `RewardTerm.TALENT_UPGRADE` is wired to `Hero.talentPointsSpent()`, but spending a talent point is only implemented in GUI classes (`TalentsPane` / `TalentButton`). The agent has no action to spend talent points.
**Fix:**

```java
// In ActionMapper.java (or a dedicated TalentAction helper invoked when hero.talentPointsAvailable() > 0):
public static boolean spendAvailableTalentPoint( Hero hero, int choiceIndex ) {
    if (hero == null || !hero.isAlive()) return false;
    for (int tier = 1; tier <= Talent.MAX_TALENT_TIERS; tier++) {
        if (hero.talentPointsAvailable( tier ) > 0 && tier - 1 < hero.talents.size()) {
            LinkedHashMap<Talent, Integer> tierTalents = hero.talents.get( tier - 1 );
            int idx = 0;
            for (Map.Entry<Talent, Integer> entry : tierTalents.entrySet()) {
                Talent talent = entry.getKey();
                int points = entry.getValue();
                if (points < talent.maxPoints()) {
                    if (idx == choiceIndex) {
                        hero.upgradeTalent( talent );
                        return true;
                    }
                    idx++;
                }
            }
        }
    }
    return false;
}

```

### Issue 3: Truncated 1-Step BPTT and Unnormalized Value Returns Break PPO Learning

**Location:** `src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/rl/PPO.java` & `Network.java`


**Problem:** `PPO.update` shuffles isolated single steps from a 5% subsample and zeroes `dPrev`/`dCell` on every step, giving the LSTM zero gradient across time. Meanwhile, raw returns in `[-3000, +1800]` cause `valueLoss` to exceed `30,000`, saturating shared trunk gradients before `gradClip`.
**Fix:**

```java
// 1. In PPO.java: Normalize value targets (or scale critic predictions with running return std)
//    so valueLoss stays O(1) and does not drown out the policy gradient in the shared trunk.
public void updateValueRunningStats( List<Transition> batch ) {
    double sum = 0.0, sqSum = 0.0;
    for (Transition t : batch) {
        sum += t.returnValue;
        sqSum += (double) t.returnValue * t.returnValue;
    }
    double mean = sum / Math.max( 1, batch.size() );
    double var = Math.max( 1e-4, (sqSum / Math.max( 1, batch.size() )) - mean * mean );
    this.returnMean = (float) (0.99 * this.returnMean + 0.01 * mean);
    this.returnStd  = (float) (0.99 * this.returnStd  + 0.01 * Math.sqrt( var ));
}

// Inside PPO.oneSample / Policy.accumulateValueGradient:
float normalizedTarget = (t.returnValue - returnMean) / (returnStd + 1e-5f);
float valueDiff = network.value() - normalizedTarget;
// Huber / clipped value loss prevents outlier terminal penalties (-3000) from exploding gradients:
float dValue = Math.max( -10.0f, Math.min( 10.0f, valueDiff ) ) * valueLossCoef;

// 2. For LSTM BPTT: Group sampled transitions into contiguous chunks of length K (e.g. K = 16)
//    from the same episode, forward sequentially from chunk[0].hPrev/cPrev, and backpropagate
//    in reverse (t = K - 1 down to 0) WITHOUT clearing dPrev/dCell between steps in the chunk.

```

### Issue 4: `Curriculum.observe()` Never Called and `.env.example` Out of Sync

**Location:** `src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/reward/RewardModel.java` & `.env.example`


**Problem:** `Curriculum.observe(depth)` is never invoked (`TODO.md` §2, §5), keeping `shapingScale()` at `1.0f` forever, and `.env.example` lists pre-rescaling constants (`SPD_REWARD_DEPTH_REWARD=25.0`, `SPD_REWARD_TURN_COST=0.002`) that do not match `superintelligence.properties`.
**Fix:**

```java
// In RewardModel.java, inside step() when depth increases:
if (depth > prevDepth) {
    curriculum.observe( depth );
    if (depth > deepestFloorPaid) {
        deepestFloorPaid = depth;
        ledger.note( RewardTerm.DEPTH_ADVANCE, config.depthReward );
    }
}

```

---

## 6. Recommended Quality of Life (QoL) Features

### Feature 1: Bit-Packed Spatial Grid & Memory-Mapped IPC (`MappedByteBuffer`)

**Benefit:** Packing the binary 21x48x48 spatial grid from 1 byte per cell to 1 bit per cell shrinks each observation 8x (from 48,384 B to 6,048 B) with zero information loss (`WORKER-DATA-FLOW.md` §2). Combining bit-packing with a memory-mapped ring buffer (`MappedByteBuffer`) eliminates the Windows anonymous pipe `IOException` crash (`issues.md` 28) and allows shipping 100% of episode trajectories instead of throwing away 95%.
**Draft Implementation:**

```java
public final class BitPackedGrid {
    public static byte[] pack( float[] grid ) {
        int byteLen = (grid.length + 7) >>> 3;
        byte[] out = new byte[byteLen];
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] > 0.5f) {
                out[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return out;
    }

    public static void unpack( byte[] packed, float[] out ) {
        for (int i = 0; i < out.length; i++) {
            out[i] = ((packed[i >>> 3] >>> (i & 7)) & 1) != 0 ? 1.0f : 0.0f;
        }
    }
}

```

### Feature 2: Distance-to-Stairs Potential-Based Reward Shaping (PBRS) & Stair Beacon Channel

**Benefit:** Floor 1 has hundreds of walkable tiles and multiple rooms; waiting for a random walk to step onto the exit stairs gives a 0.1% success rate (`meanDepth = 1.001` across 5,360 episodes in `issues.md` 29). Adding potential-based shaping $\Phi(s) = -d_{\text{BFS}}(\text{hero}, \text{exit})$ (once the exit cell is visible/visited, or during early curriculum stages) provides a dense, provably policy-invariant gradient toward the stairs without creating a ping-pong exploit.

### Feature 3: Native JVM `.env` Auto-Loader in `EnvConfigBinder`

**Benefit:** Currently, `.env` is ignored by the JVM unless the user manually runs a PowerShell or bash one-liner before invoking Gradle (`.env.example`). Teaching `EnvConfigBinder` to parse `.env` directly (between `superintelligence.properties` and process environment variables) prevents silent misconfigurations.

---

## 7. Conclusion

**Overall Score: 68/100**

**Top Strengths:**

1. Deterministic headless simulation engine and comprehensive 24-gate verification suite (`verifyall`, `viewcheck`, `gradcheck`, `gaecheck`, `parallelcheck`, `stallcausecheck`).


2. Clean process-level state isolation (`RunState`) and zero-renderer execution reaching up to 8,500 turns/s.



**Top Priorities for Improvement:**

1. **Fix the Free-Step Stall Trap & Missing Talent Action:** Make `REST` advance 1.0 engine turn, mask out no-op UI mode toggles (`OPEN_INVENTORY`/`USE`/`CANCEL` loops) so episodes stop dying at turn 120 on `FREE_STEP`, and add a headless action to spend talent points (`hero.upgradeTalent`).


2. **Fix Recurrent PPO & Critic Scaling:** Replace 5% disjoint single-step subsampling (BPTT = 1) with contiguous sequence chunks (BPTT = 16 or 32) using bit-packed grids, and normalize value targets so `valueLoss` (~30,000) stops destroying shared trunk representations.



**Final Verdict:** The simulation harness and replay verification infrastructure are production-grade, but the RL training loop and action-mode interface are **not ready for training runs** until the `FREE_STEP` mode-loop exhaustion, zero-time `REST` bug, missing talent-point action, 1-step truncated LSTM gradient, and unnormalized critic loss are resolved.