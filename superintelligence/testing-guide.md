### Key Issues to Test

#### **1. Static State Leakage Across Resets & Episodes**
* **The Issue**: Residual static variables (such as `GameScene.pendingCellListener`, `SlotAction.pendingUseItem`, and lingering UI or window listeners) persist across consecutive episode resets within the same JVM process. If an episode terminates while mid-aim or in a sub-menu, un-cleared static state forces the subsequent episode to start in `TARGETING` mode, causing immediate step-0 or step-1 position divergence.
* **How to Test**:
  * **Sequential Multi-File Verification**: Run verification across multiple replay files sequentially within a **single JVM process** (or verify the same replay file 2–3 times consecutively in one process). A clean implementation must pass every pass without order-dependent divergence.
  * **`restartcheck` Gate**: Execute the automated `restartcheck` gate, which re-initialises an episode mid-session after hundreds of turns to prove that floor 1 regenerates identically without residual state corruption.

---

#### **2. Gameplay RNG Stream Contamination**
* **The Issue**:
  * **Observation Encoders Mutating RNG**: `HeroEncoder` previously calculated defence statistics by calling `hero.drRoll()`, which drew fresh values from `Random.NormalIntRange()`. Because state encoding occurred during `reset()` and `settle()`, reading state advanced the gameplay RNG stream, causing permanent offset between headless training and replay verification.
  * **Presentation Randomness on Gameplay Stream**: Visual particle effects, emote icons, sound pitches (`Hero.move`), music selection, and environmental jitter were drawing from the primary gameplay `Random` stream. Because headless execution skips rendering, rendered playback and headless execution consumed different quantities of RNG.
* **How to Test**:
  * **Observation Purity Gate (`observecheck`)**: Run `observecheck`, which measures draw counts on the base generator before and after executing `ObservationEncoder.encode` and `Quickslots.capture`. Assert that **zero RNG draws** occur while encoding state and that consecutive encodings of an unchanged world yield byte-identical feature vectors.
  * **RNG Stream Fingerprint Trace (`rngtrace`)**: Execute `rngtrace` to record per-step cumulative draw counts (`baseDraws`) and order-sensitive fingerprints (`baseFingerprint`). Compare the headless trace against the rendered viewer trace to verify that presentation draws are isolated on `PRandom` and do not shift the gameplay stream.

---

#### **3. Viewer vs. Trainer Preamble & Scheduler Discrepancies**
* **The Issue**: The headless trainer (`SPDEnv.step` / `LevelPipeline`) and the visual viewer (`ReplayPlayer`) implement separate scheduler drain loops. `ReplayPlayer` originally lacked calls to `GameScene.clearPendingCellListener()` and `SlotAction.clearPendingUseItem()`, causing targeting and throw actions to fail to resolve during headless playback.
* **How to Test**:
  * **Headless Playback Verifier (`playbackcheck`)**: Execute the `playbackcheck` gate, which drives `ReplayPlayer` headlessly without a window, scene, or display server. It verifies that faithful recordings play cleanly and uses mutation testing (tampering with recorded position, HP, turn, inventory, and quickslots) to assert that any trajectory divergence is caught at the exact altered step.

---

#### **4. Cross-JVM Determinism & Worker Path Parity**
* **The Issue**: Ensuring that policy actions, seed locks, and worker process rollouts remain bit-identical across fresh JVM instances, multi-process training worker pools, and network-driven policy forward passes.
* **How to Test**:
  * **Byte-for-Byte Replay Comparison**: Run multiple rollouts of identical (seed, hero) pairs across fresh JVMs using `--save` and compare the resulting `.replay` files byte-for-byte.
  * **Worker Training Parity**: Run two identical multi-worker training runs with a fixed seed (e.g. `--seed 4242`) and verify that the generated best-per-seed replays are byte-identical end-to-end.

---

#### **5. Seed Lock Resolution & Replay Header Completeness**
* **The Issue**:
  * **Unresolved Random Seeds**: Unseeded or random-seed episodes previously recorded an empty seed string (`seed=`), making the replay un-rebuildable because verification would draw a fresh random seed and diverge at step 0.
  * **Missing Inventory Head Configuration**: Replay headers originally omitted `max_slots` and `allow_equipping` (the parameters read by `ActionMapper`), causing slot index resolution ambiguity during playback.
* **How to Test**:
  * **Random Seed Resolution Test**: Verify that random-seed rollouts capture the resolved seed text (`env.seedText()`) in the replay header and reproduce 100% cleanly upon re-verification.
  * **Header Field Round-Trip Test**: Test Replay v2 headers containing non-default `max_slots` and `allow_equipping` values to verify that settings are reconstructed faithfully during playback.

---

### Automated Testing Suite & Verification Matrix

| Test Command / Task | Subsystem Verified | Success Criterion |
| :--- | :--- | :--- |
| **`./gradlew :superintelligence:verifyall`** | Master aggregator running all 15 automated gate checks across all modules. | **All 15 gates pass cleanly** in ~15 seconds. |
| **`./gradlew :superintelligence:run --args="verify <file.replay>"`** | Headless re-execution of a recorded replay through `ActionMapper` & `LevelPipeline`. | Replays trajectory to completion, matching recorded position, HP, turns, and inventory. |
| **`playbackcheck` (`:desktop`)** | Drives `ReplayPlayer` logic headlessly without UI or display server. | Faithful replays pass; altered steps (HP, position, turn, quickslots) fail at exact step. |
| **`observecheck` (`:superintelligence`)** | Verifies observation encoders (`ObservationEncoder`, `HeroEncoder`, `Quickslots`). | **0 RNG draws** consumed during state encoding; byte-identical vector output. |
| **`rngtrace <file.replay>`** | Emits per-step draw counts and order-sensitive fingerprints (`baseFingerprint`). | Step-by-step fingerprint alignment between headless and rendered runs. |
| **`collectcheck` (`:superintelligence`)** | Verifies that live recordings captured by `EpisodeCollector` replay cleanly. | 100% reproduction of recorded step positions and scores. |
| **`modecheck` & `restartcheck`** | Verifies action modes (`WORLD`, `SLOT`, `TARGETING`, `MENU`, `INVENTORY`) and floor re-initialisation. | Zero mode traps; floor 1 regenerates identically after 400+ turns. |
| **Multi-JVM Byte-Diff Sweep** | Runs identical seed/hero rollouts across separate JVMs. | Generated `.replay` files are **byte-identical** across fresh JVMs. |