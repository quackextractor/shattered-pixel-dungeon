# Shattered Pixel Dungeon (Superintelligence Module) - Comprehensive Code Review and Assessment

## Executive Summary

**Project Overview:** Shattered Pixel Dungeon is an open-source roguelike dungeon crawler written in Java on top of libGDX and the custom Noosa rendering engine, compiling for Android, iOS, and Desktop platforms. The `:superintelligence` module wraps the core game in a headless reinforcement-learning (RL) framework using a custom pure-Java Convolutional Neural Network (CNN) and Long Short-Term Memory (LSTM) actor-critic architecture trained via Proximal Policy Optimization (PPO) across multi-process worker JVMs. The primary training goal is to have the hero spend talent points, descend floors, defeat the first boss (Goo on depth 5), and ultimately win the game.

**Key Achievement:** The renderer-bypass architecture (`HeadlessGame`, `HeadlessServices`, `HeadlessSprite`) and deterministic turn-order engineering (`LinkedHashSet`/`LinkedHashMap` actor registries, `PRandom` presentation stream separation, and `RandomTrace` instrumentation) allow the simulation to run headlessly at up to 7,367 turns per second while maintaining cross-process replay parity.

**Overall Impression:** While the verification harness (`verifyall` with 27 headless gates and `playbackcheck`) and replay tooling (`WorldTrace`, `WorldCompare`) are rigorously engineered, the learning pipeline suffered from critical architectural bottlenecks and implementation bugs that completely prevented training. Specifically, non-reward defects in thread-shared network memory, zero-cost action loops exhausting the stall budget, `REST` failing to advance the engine clock, missing headless talent-spending mechanics, length-1 truncated backpropagation through time (BPTT), and dropped worker configuration fields caused nearly 100% of episodes to terminate as `STALLED` on floor 1 without learning.

---

## 1. Architectural & Design Review

### Strengths

* **Renderer-Bypass Simulation Pattern:** Rather than mocking OpenGL calls, `Gdx.gl` is left null and presentation classes (`TextureCache`, `NinePatch`, `BitmapText`, `Image`, `GameScene`) are guarded to tolerate null textures and fonts, ensuring rollouts never depend on rendering state.


* **Multi-Process Worker Isolation (`WorkerPool`) & Worker-Side GAE (`EpisodeCollector`):** Because the base game stores simulation state in `static` globals (`Dungeon.hero`, `Dungeon.level`, `Actor.now`, `Actor.all`), running multiple environments in one JVM is impossible. Isolating environments in separate worker JVMs and computing Generalized Advantage Estimation (GAE) over lightweight 21-byte per-step scalars in the worker before subsampling observations (`Option C`) cuts inter-process pipe bandwidth by 20x (from 2.4 GB to ~118 MB per generation).


* **Mutation-Tested Verification Gates:** The project pairs domain subsystems with 27 dedicated verification tasks in `:superintelligence:gates` (such as `gradcheck`, `gaecheck`, `paritycheck`, `resetcheck`, `parallelcheck`, `freeactioncheck`, and `bpttcheck`) and `:desktop:playbackcheck`, each designed to fail when the invariant it guards is mutated.



### Areas for Improvement

* **1. Architectural Suitability - Is the Pipeline Suited for This Game and Goal?**
* **Synchronous On-Policy Barrier vs. Episode Variance:** PPO requires synchronous collection across all workers before updating and pushing the 14.3 MB parameter blob back to each worker, creating an idle CPU barrier that consumes 12% to 26% of wall time at 20 workers (and 45% at 4 workers) because roguelike episode lengths vary heavily.


* **Pure-Java CPU Matrix Math Bottleneck:** The network contains 3,583,827 parameters (`Conv2D` im2col 12,696x320 matrix-vector multiply, `Dense` 12,696x256 trunk, and `LSTM` 256->128). Executing forward and backward passes in pure Java on CPU takes 11.29 ms per sample on 1 thread and 2.73 ms on 4 threads, making the learner update ~900x slower than pipe transport. Scaling past 4 update threads plateaus (2.59 ms at 8 threads) because reducing 14.3 MB of gradients per thread per minibatch saturates memory bandwidth.


* **Subsampling vs. Partial Observability (LSTM Memory):** Uniformly subsampling 5% of steps (`SPD_RL_SAMPLE_RATE=0.05`) breaks episodes into non-adjacent transitions. In a partially observed grid game where `GridChannel.TRANSITION` only lights stairs while in field-of-view, training an LSTM on isolated steps with length-1 truncated gradients prevents `LSTM.W_hh` from learning spatial memory across time.


* **Fixed 48x48 Observation Window vs. Map Dimensions:** Across 1,040 measured floors (40 seeds, depths 1 to 26), regular floors reach up to 81 tiles in height and 419 of 1,040 floors (40%) exceed the `48x48` `env.grid_width`/`env.grid_height` window on at least one axis.


* **Sparse Horizon on Procedural Maps:** Floor 1 contains several hundred walkable tiles where a random walk reaches the exit only ~0.1% of the time (`meanDepth` 1.001 across 5,360 episodes), making sparse descent rewards unreachable without dense potential-based path shaping (`StairPotential`) and a persistent stair memory plane (`GridChannel.EXIT_BEACON`).




* **2. Implementation Issues - What Broke Learning and Caused 100% Stalls on Floor 1?**
* **Thread-Shared Master `Network` in `ShardedUpdate` (`issues.md` 25):** `ShardedUpdate.run` passed the master `ppo.network` reference directly into each `Shard` constructor without copying it. All `N` update threads executed forward and backward passes on the exact same `Network` instance, tearing forward scratch buffers (`Dense.preAct`, `LSTM.gates/h/c`), wiping other threads' gradients via `clearGradients()`, and corrupting every multi-threaded training update.


* **Unbounded Zero-Time Action Loops & `REST` Bug (`issues.md` 29, 32):** Approximately 90% of actions taken by an untrained policy (`OPEN_INVENTORY`, `USE`, `DROP`, `CANCEL`, and `REST`) advanced zero engine time (`Actor.now()`). `REST` called `hero.next()` without clearing `hero.ready`, so `LevelPipeline.runToHeroReady` returned immediately without advancing the clock (323 of 326 rests spent zero time), while `OPEN_INVENTORY` -> `CANCEL` formed a zero-time loop. Every free step incremented the floor counter against `env.free_step_budget` (originally 40), terminating 98% to 100% of episodes via `StallCause.FREE_STEP` around turn 120 of the 1,500 granted.


* **Floor Transitions & Chasm Falls Dropped or Stalled (`issues.md` 9, 10, 16):** Walking onto stairs called `Game.switchScene(...)`, which never completed headlessly because `Game.step()` is only invoked by `Game.render()`, causing descents to immediately terminate as `STALLED`. After transitions were wired, `SPDEnv.settle` failed to give the hero an initial turn on the new floor (`issues.md` 10), and `LevelPipeline.handleTransition` checked `if (transition == null)` before `if (mode == Mode.FALL)`, ignoring chasm falls because `curTransition` is always null on a fall (`issues.md` 16).


* **Missing Headless Talent-Spending Mechanism (`issues.md` 32, `TODO.md` 7):** Talent point allocation in `:core` is coupled to UI widgets (`TalentButton` -> `TalentsPane` -> `WndHero`), so no headless action existed for the agent to spend talent points earned at level 2 until `Action.TALENT`, `EnvMode.TALENT`, and `TalentPicker` were added.


* **Dropped Worker Config Fields (`issues.md` 30):** `Trainer.writeParams` and `Worker.readParams` manually serialized only 10 of the 30 `EnvConfig` fields; workers initialized a default `new EnvConfig()` and silently ignored 20 configuration overrides.


* **Critic Target Explosion & Cross-Sample Gradient Leak (`PLAN-data-flow.md` 4, `CHANGELOG.md`):** `Network.backward` cleared `dPrev` but failed to clear `dCell`, leaking LSTM cell gradients across unrelated samples in a minibatch. Meanwhile, unnormalised returns in the thousands (`-3000` to `+1800`) produced `valueLoss` values of 12,432 to 31,323, overwhelming the shared trunk and causing global gradient clipping (`0.5`) to zero out policy updates.




* **Recommendation:** Ensure every shard in `ShardedUpdate` constructs an independent `Network` copy, enforce one-step sub-mode entry masking after zero-time `CANCEL` exits, use `spendAndNext(Actor.TICK)` for `REST`, train recurrent chunks via contiguous BPTT (`rl.chunk_length=16`) with normalised critic targets (`ValueNormalizer`), and serialize all `EnvConfig` fields through a single method pair (`EnvConfig.writeTo`/`readFrom`).



---

## 2. Compliance Checklist

| Requirement | Status | Implementation Details |
| --- | --- | --- |
| **No Emojis in Code/Logs (`AGENTS.md`)** | Pass | ASCII status tags (`[OK]`, `[WARN]`, `[ERROR]`) are used throughout scripts, gates, and diagnostics.

 |
| **500-Line File Limit (`AGENTS.md`)** | Fail | While `Trainer.java` was split to 467 lines, five files in `:superintelligence` still exceed the 500-line limit (600 for tests): `RewardCheck.java` (1,354), `ReplayCatalogCheck.java` (783), `CollectCheck.java` (615), `ConfigCheck.java` (542), and `Main.java` (506).

 |
| **No Code Comments (`AGENTS.md`)** | Fail | `stripinlines --javadoc` removed 13,366 comment lines across 114 files, but `STATUS.md` notes explanatory comments remain in files not yet touched, and `Replay.java` has an orphan javadoc warning.

 |
| **Zero Compiler Lint Warnings (`javaclint`)** | Fail | `gradle :superintelligence:javaclint` reports two advisory warnings: an orphan javadoc in `Replay.java` and a potentially lossy `double`-to-`int` cast in `ReplayIO.java:421`.

 |
| **Externalised Configuration (`.env` / Properties)** | Pass (Partial Build Script Gap) | 63 keys are bound across `superintelligence.properties` and `.env` (`SPD_*`) and gated by `configcheck`, though `superintelligence/build.gradle` still hardcodes `trainerJvmArgs` (`-Xms256m`, `-Xmx1536m`, `-XX:MaxGCPauseMillis=200`).

 |
| **Deterministic RNG & Replay Parity** | Pass (Minor Engine Gaps) | `verifyall` (27 gates + `playbackcheck`) passes, and 19 of 20 recordings pass `:desktop:viewcheck`. However, `duelist-death-last.replay` diverges by 1 HP at step 94 (`issues.md` 26), `rolloutcheck` fails ~1 in 30 runs (`issues.md` 18), `Mob.holdAllies` still calls unseeded `Collections.shuffle`, and 11 sound pitch sites still draw from gameplay `Random`.

 |
| **Wire Protocol Consistency (`Protocol.VERSION`)** | Pass | `Protocol.VERSION` is incremented to 4, carrying episode ID, step index, tail length, reward profile, and all `EnvConfig` fields.

 |

---

## 3. Deep Code Review

### Distributed Training, Concurrency & IPC (`train` & `rl`)

The trainer coordinates 20 worker JVMs over standard input/output pipes and runs a multi-threaded minibatch update via `ShardedUpdate`.

* **Thread-Safety Defect in `ShardedUpdate`:** `ShardedUpdate` was designed so each thread owns an isolated `Network` copy to avoid contention on layer scratch arrays (`Dense.preAct`, `LSTM.gates`, `Conv2D` buffers) and gradient accumulators (`gW`, `gb`). However, `ShardedUpdate.run:119` instantiated `new Shard( ppo.network )` and stored the master reference directly, causing data races, corrupted forward activations, impossible negative entropy values, and lost gradients. Fixing `Shard` to construct its own `Network` improved update throughput by 13% (from 804 to 908 turns/s) by eliminating cache-line contention.


* **Anonymous Pipe Exhaustion in `TrainerWorkers`:** Pushing 14.3 MB of fp32 weights in a single `DataOutputStream.write` call per worker thread caused `java.io.IOException: Insufficient system resources exist to complete the requested service` on Windows around generation 22. Chunking writes into 32 KB blocks (`SPD_IPC_PUSH_CHUNK_BYTES=32768`) with retry logic resolves the OS buffer exhaustion, though this path lacks an automated gate because spawning a child worker inside a gate JVM fails in `HeadlessServices.install`.


* **Fix/Optimization:** Keep spatial grids bit-packed (`6,336` bytes vs `50,688` bytes over 22 planes of `48x48`, an 8x reduction verified by `gaecheck`) both on the wire and resident in heap memory, and decouple worker spawning from `HeadlessServices.install` so `pushWeights` chunking can be gated.



### Environment Pipeline, Action Space & Stalling Mechanics (`env` & `headless`)

`SPDEnv`, `LevelPipeline`, `ActionMapper`, and `WindowBridge` bridge headless agent decisions to Shattered Pixel Dungeon's turn scheduler.

* **Stall Guard & Free-Action Starvation:** `SPDEnv` tracks four stall causes (`POSITION`, `FREE_STEP`, `ACTOR_STEP_LIMIT`, `PIPELINE`). Because `OPEN_INVENTORY`, `USE`, `DROP`, and `CANCEL` consume zero engine time, an agent oscillating menu actions burned through `env.free_step_budget` without advancing `Actor.now()`, terminating 100% of episodes via `FREE_STEP`. Masking `OPEN_INVENTORY`, `USE`, and `DROP` for one `WORLD` step after a zero-time sub-mode exit caps consecutive free menu steps at 2 and raises `meanTurns` from 120.6 to 800.7.


* **Headless Sprite & Engine Hook Gaps:** When an agent read a `ScrollOfMirrorImage`, `GameScene.add(Mob, float)` skipped sprite assignment and `Actor.add(mob)` because `scene == null`, causing a `NullPointerException` in `Char.move:1334` followed by an unguarded `ClassCastException` (`HeadlessSprite` to `MirrorSprite`) in `MirrorImage.act:86`. While `GameScene.headlessMobSprite` and an `instanceof MirrorSprite` guard fixed `ScrollOfMirrorImage`, `ENGINE-CHANGES.md` §8 notes that other item paths calling `createMob` without going through `GameScene.add(Mob)` remain unenumerated.


* **Fix/Optimization:** Audit all `Mob` instantiation sites in `:core` (such as `WandOfLivingEarth`, `DriedRose`, `SpiritHawk`, and `ShadowClone`) to ensure every spawned ally or summon receives a `HeadlessSprite` when `GameScene.headlessGameplay()` is active.



### Neural Network, BPTT & Critic Scaling (`rl` & `obs`)

The agent uses a 22-channel `48x48` spatial encoder (`21` spatial planes + `1` scalar `DEPTH_BIAS`), a 96-slot inventory encoder (`96 x 14` floats), and a 23-float hero feature vector feeding a `Conv2D` + `Dense` + `LSTM` actor-critic network.

* **Truncated BPTT and Stale Hidden States:** Backpropagating sampled transitions individually with zeroed `dPrev`/`dCell` prevented temporal credit assignment across steps. Grouping contiguous steps up to `SPD_RL_CHUNK_LENGTH=16` and replaying the forward pass in sequence allows gradients to flow through `LSTM.W_hh` (producing a 63.7% gradient difference on two-step fixtures in `bpttcheck`).


* **Critic Domination on Shared Trunk:** With `SPD_REWARD_STALL_PENALTY=3000.0` and `SPD_REWARD_DEPTH_REWARD=1800.0`, raw returns are four orders of magnitude larger than policy logits. Without `ValueNormalizer` (`SPD_RL_NORMALISE_VALUES=true`) and Huber loss (`SPD_RL_HUBER_VALUES=true`), the critic's squared-error gradient saturates global norm clipping (`SPD_RL_MAX_GRAD_NORM=0.5`) and starves the actor.


* **Fix/Optimization:** Activate `Curriculum.observe()` (`TODO.md` 2 & 5) or use `StairPotential` shaping ($\gamma \Phi(s') - \Phi(s)$) alongside `GridChannel.EXIT_BEACON` so the agent receives dense directional gradients toward the exit after spotting stairs.



---

## 4. Documentation & UX Quality

* **README/Docs:** Documentation is extensive and candid about negative results, refutations, and exact measurements across `README.md`, `CHANGELOG.md`, `ENGINE-CHANGES.md`, `PLAN-data-flow.md`, `PLAN-reward-signals.md`, `WORKER-DATA-FLOW.md`, `TODO.md`, and `issues.md`. However, some cross-document statements have drifted: `README.md` states `Curriculum.observe()` is called on every floor change while `TODO.md` §2 and §5 state `Curriculum.observe()` is never called and remains inert; similarly, `README.md` mentions both "twenty-six gates" and "27 checks" in different paragraphs.


* **Visuals:** The module provides console-based ANSI visualizations (`diag.Ansi`, `diag.Graph`, `diag.RunReport`, `train.ProgressBar`) for floor-by-floor reward ledgers and end-of-run trend charts, plus a desktop libGDX replay viewer (`ReplayLauncher`, `ReplayPlayer`) with a movable HUD. However, the graphical trainer UI (`TODO.md` 3.1) and live play reward monitor (`TODO.md` 3.3) requested in `docs.md` and `research.md` have not been built.


* **Developer Experience:** Local workflows are streamlined via `train.ps1`, `replay-viewer.bat` (optimized to bypass Gradle startup for `--list` in 0.62s vs 3.4s), `install-hooks.ps1`, and `./gradlew verifyall`. One rough edge is that `Main.java` and four diagnostic test files exceed the 500/600-line limit in `AGENTS.md`, and two javac lint warnings remain open in `Replay.java` and `ReplayIO.java`.



---

## 5. Critical Issues and Actionable Fixes

### Issue 1: Update Threads Share a Single Master `Network` Reference in `ShardedUpdate`

**Location:** `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/rl/ShardedUpdate.java - Lines 119, 214-223`

**Problem:** `ShardedUpdate.run` initialized worker shards via `new Shard( ppo.network )`, and the `Shard` constructor assigned `this.network = network` instead of creating an independent `Network` instance. Consequently, all `N` update threads mutated the same layer scratch buffers (`Dense.preAct`, `LSTM.gates/h/c`) during concurrent forward/backward passes and repeatedly called `clearGradients()` on the master network, destroying accumulated gradients and corrupting policy updates.

**Fix:**

```java
// Inside ShardedUpdate.java
while (shards.size() < n) {
    shards.add(new Shard(ppo.network, config, seed + shards.size()));
}

static final class Shard {
    final Network network;
    final float[] grid;

    Shard(Network master, EnvConfig config, long seed) {
        this.network = new Network(config, seed);
        this.network.copyParametersFrom(master);
        this.grid = new float[this.network.gridLength()];
    }
}

```

### Issue 2: `REST` Advances Zero Engine Time and Unmasked Zero-Time Menu Loops Cause 100% `FREE_STEP` Stalls

**Location:** `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/env/ActionMapper.java - Line 209` and `SPDEnv.java`

**Problem:** In `ActionMapper.apply`, `REST` set `hero.resting = true` and called `hero.next()` without clearing `hero.ready`, causing `LevelPipeline.runToHeroReady` to return immediately with zero engine time (`Actor.now()`) elapsed on 323 of 326 rests. Additionally, entering `OPEN_INVENTORY`, `USE`, or `DROP` and immediately choosing `CANCEL` formed a zero-time loop that exhausted `env.free_step_budget` and terminated 100% of runs as `STALLED` around turn 120.

**Fix:**

```java
// 1. In ActionMapper.java: spend a full tick and clear readiness on REST without trapping hero.resting
case REST:
    hero.resting = false;
    hero.spendAndNext(Actor.TICK);
    return true;

// 2. In SPDEnv.java / ActionMapper.java: mask sub-mode entries for one WORLD step after a zero-time exit
if (mode == EnvMode.WORLD &&forbidFreeMenuEntryNextStep) {
    mask[Action.OPEN_INVENTORY.ordinal()] = false;
    mask[Action.USE.ordinal()] = false;
    mask[Action.DROP.ordinal()] = false;
}

```

### Issue 3: Length-1 Truncated BPTT and Cross-Sample `dCell` Gradient Leak in `LSTM` / `Network`

**Location:** `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/rl/Network.java` and `PPO.java`

**Problem:** `Network.backward` cleared `dPrev` between isolated samples but failed to clear `dCell`, causing `LSTM.backward` to leak the previous sample's cell gradient into unrelated samples in the shuffled minibatch. Simultaneously, backpropagating single steps in isolation prevented gradients from crossing timesteps into `LSTM.W_hh`.

**Fix:**

```java
// Inside Network.java and PPO.java chunked BPTT update
public void resetBackwardChain() {
    Arrays.fill(dPrev, 0f);
    Arrays.fill(dCell, 0f);
}

// Backpropagate contiguous trajectory chunks of up to rl.chunk_length (16) in reverse order
network.resetBackwardChain();
for (int t = chunkEnd - 1; t >= chunkStart; t--) {
    StepCache step = cache[t];
    policy.backwardStep(network, step, valueNormalizer);
    // dPrev and dCell are preserved across contiguous steps [chunkStart .. chunkEnd - 1]
}

```

### Issue 4: Incomplete `EnvConfig` Wire Serialization and Hardcoded JVM / Hyperparameter Values

**Location:** `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/env/EnvConfig.java`, `Trainer.java`, `Worker.java`, and `superintelligence/build.gradle`

**Problem:** `Trainer.writeParams` and `Worker.readParams` manually serialized only 10 of 30 `EnvConfig` fields, leaving 20 fields (including `freeStepBudget`, `exploreReward`, `killReward`, `damagePenalty`, and `talentReward`) at compiled defaults in worker processes regardless of `.env` or `superintelligence.properties` overrides. Furthermore, `superintelligence/build.gradle` still hardcodes worker heap and GC pause flags in `trainerJvmArgs` instead of reading `SPD_WORKER_HEAP_MIN`, `SPD_WORKER_HEAP_MAX`, and `SPD_WORKER_GC_PAUSE_MS`.

**Fix:**

```java
// Inside EnvConfig.java: centralize wire serialization so Trainer and Worker never drift
public void writeTo(DataOutputStream out) throws IOException {
    out.writeInt(maxSlots);
    out.writeInt(gridWidth);
    out.writeInt(gridHeight);
    out.writeInt(turnLimitPerFloor);
    out.writeInt(turnLimitTotal);
    out.writeInt(stallLimit);
    out.writeInt(freeStepBudget);
    out.writeInt(actorStepLimit);
    out.writeDouble(depthReward);
    out.writeDouble(exploreReward);
    out.writeDouble(stairPotentialWeight);
    out.writeDouble(turnCost);
    out.writeDouble(freeStepCostFactor);
    out.writeDouble(stallPenalty);
    out.writeDouble(deathPenalty);
    out.writeDouble(turnLimitPenalty);
    out.writeDouble(victoryReward);
    out.writeDouble(killReward);
    out.writeDouble(sneakAttackReward);
    out.writeDouble(damagePenalty);
    out.writeDouble(maxHpReward);
    out.writeDouble(talentReward);
    out.writeDouble(goldReward);
    out.writeDouble(curriculumProgress);
    out.writeFloat(dialogAgeSeconds);
}

```

### Issue 5: Unchunked 14.3 MB Weight Push Crashes Worker Pipes at Generation ~22

**Location:** `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/train/TrainerWorkers.java - Line 258`

**Problem:** Writing the full ~14.3 MB serialized parameter array in a single `DataOutputStream.write(byte[])` call across 20 concurrent worker threads exhausts Windows kernel pipe buffers and crashes the training run with `IOException: Insufficient system resources exist to complete the requested service`.

**Fix:**

```java
// Inside TrainerWorkers.java: chunk weight writes to SPD_IPC_PUSH_CHUNK_BYTES (32768)
static void writeChunked(OutputStream out, byte[] payload, int chunkBytes) throws IOException {
    int offset = 0;
    while (offset < payload.length) {
        int len = Math.min(chunkBytes, payload.length - offset);
        out.write(payload, offset, len);
        offset += len;
    }
    out.flush();
}

```

---

## 6. Recommended Quality of Life (QoL) Features

### Feature 1: Active Curriculum Progression & Emission of Unwired Reward Terms

**Benefit:** `TODO.md` §2 and §5 note that `Curriculum.observe()` is never called (leaving `shapingScale()` stuck at `1.0f`) and 5 reward terms (alchemy/crafting such as meat pie, curse removal, equip rejection, and menu no-ops) have no emission sites, while `KILL`, `GOLD_GAIN`, and `ITEM_PICKUP` rarely fire in early training. Wiring `Curriculum.observe(depth)` into floor transitions and adding state-diff checks for alchemy crafting and curse removal in `RewardModel` completes the reward specification in `docs.md`.

**Draft Implementation:**

```java
// Inside RewardModel.java on step / floor transition
public void onFloorReached(int newDepth) {
    curriculum.observe(newDepth);
    if (newDepth > deepestFloorReached) {
        deepestFloorReached = newDepth;
        ledger.note(RewardTerm.DEPTH_ADVANCE, config.depthReward);
    }
}

public double shapedReward(RewardTerm term, double rawAmount) {
    return rawAmount * curriculum.shapingScale(term);
}

```

### Feature 2: Automated Gate for `Main.rollout`, Viewer Flags, and Split of >500-Line Diagnostic Files

**Benefit:** `issues.md` 31 and `TODO.md` 7 & 8 identify that `Main.rollout` and the viewer diagnostic flags (`-Dspd.replayContinueOnDiverge` and `-PspdHidden`) lack gate coverage, while `RewardCheck.java` (1,354 lines), `ReplayCatalogCheck.java` (783 lines), `CollectCheck.java` (615 lines), `ConfigCheck.java` (542 lines), and `Main.java` (506 lines) violate the repository's 500-line (600-line test) rule. Splitting `RewardCheck` by domain (`RewardTerminalCheck`, `RewardCombatCheck`, `RewardShapingCheck`) and fixing the lossy `double`-to-`int` cast in `ReplayIO.java:421` brings the module into full compliance with `AGENTS.md` and `javaclint`.

**Draft Implementation:**

```java
// In ReplayIO.java:421 - replace lossy double-to-int cast with explicit Math.round / Double.compare
int roundedScore = (int) Math.round(recordedScore);

```

### Feature 3: Native / ONNX Runtime Offload and Live libGDX Trainer Dashboard

**Benefit:** As noted in `AGENTS.md` and `PLAN-data-flow.md`, pure-Java CPU backpropagation takes 2.73 ms/sample across 4 threads and cannot scale past 4 threads due to the 14.3 MB per-thread gradient reduction. Offloading the `Conv2D` + `Dense` + `LSTM` forward/backward pass to an ONNX or native CUDA/JNI backend removes the CPU learner bottleneck, while implementing the libGDX trainer UI (`TODO.md` 3.1) and live reward monitor (`TODO.md` 3.3) in `:desktop` fulfills the visual monitoring goals in `docs.md` and `research.md`.

---

## 7. Conclusion

**Overall Score: 78/100**

**Top Strengths:**

1. Deterministic headless simulation engine (`HeadlessGame`, `LevelPipeline`, `RunState`) with 27 automated verification gates and exact cross-process replay verification.


2. Bandwidth-efficient worker-side scalar GAE computation (`EpisodeCollector`, `EpisodeRecord`) combined with 8x lossless spatial grid bit-packing.



**Top Priorities for Improvement:**

1. Verify that all critical learning fixes (`ShardedUpdate` per-thread `Network` instances, `EnvConfig` full 30-field wire serialization, chunked 16-step BPTT with `dCell` reset, `ValueNormalizer`, `Action.TALENT`, `StairPotential`, and one-step sub-mode exit masking) run together over a segmented 30+ generation run that clears the +/-16 `meanScore` noise band (`TODO.md` 1).


2. Resolve the remaining compliance and fidelity gaps: split the 5 files exceeding the 500/600-line limit (`RewardCheck`, `ReplayCatalogCheck`, `CollectCheck`, `ConfigCheck`, `Main`), fix the 2 `javaclint` warnings in `Replay.java` and `ReplayIO.java:421`, replace `Collections.shuffle` in `Mob.holdAllies` with `Random.shuffle`, and add cell snapshots to `ReplayPlayer`'s `WorldSnapshot` to resolve the 1 HP viewer divergence in `duelist-death-last.replay` (`issues.md` 26).



**Final Verdict:** The headless simulation and verification infrastructure are production-grade, and with the root causes of the `STALLED` termination loop, thread-shared `Network` corruption, dropped worker configurations, and truncated BPTT resolved, the pipeline is now structurally ready for multi-floor training runs.