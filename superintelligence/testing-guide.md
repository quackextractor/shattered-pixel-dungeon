### Key Issues to Test

#### **1. Static State Leakage Across Resets & Episodes**
* **The Issue**: Residual static variables (such as `GameScene.pendingCellListener`, `GameScene`'s headless dialog slot, `SlotAction.pendingUseItem`, and a dead hero's remains in `Bones`) persist across consecutive episode resets within the same JVM process. If an episode terminates while mid-aim or in a sub-menu, un-cleared static state forces the subsequent episode to start in `TARGETING` or `MENU` mode, causing immediate step-0 or step-1 position divergence. Remains are the subtler case: a hero who dies leaves a `REMAINS` heap on a later floor, so the world a run generates depends on what died earlier in the same process.
* **How to Test**:
  * **Sequential Multi-File Verification**: Run verification across multiple replay files sequentially within a **single JVM process** (or verify the same replay file 2–3 times consecutively in one process). A clean implementation must pass every pass without order-dependent divergence.
  * **`restartcheck` Gate**: Execute the automated `restartcheck` gate, which re-initialises an episode mid-session after hundreds of turns to prove that floor 1 regenerates identically without residual state corruption.
  * **`resetcheck` Gate**: Execute the automated `resetcheck` gate, which arms an aim request, opens a dialog and kills a hero - one case each - then asserts the next episode starts clean.
  * **`paritycheck` Gate**: Execute the automated `paritycheck` gate, which records a sweep of seeds and hero classes in one process and verifies each recording twice. This is the only gate that reaches the remains case, because it is the only one that lets the hero die repeatedly.

---

#### **2. Gameplay RNG Stream Contamination**
* **The Issue**:
  * **Observation Encoders Mutating RNG**: `HeroEncoder` previously calculated defence statistics by calling `hero.drRoll()`, which drew fresh values from `Random.NormalIntRange()`. Because state encoding occurred during `reset()` and `settle()`, reading state advanced the gameplay RNG stream, causing permanent offset between headless training and replay verification.
  * **Presentation Randomness on Gameplay Stream**: Visual particle effects, emote icons, sound pitches (`Hero.move`), music selection, and environmental jitter were drawing from the primary gameplay `Random` stream. Because headless execution skips rendering, rendered playback and headless execution consumed different quantities of RNG.
* **How to Test**:
  * **Observation Purity Gate (`observecheck`)**: Run `observecheck`, which measures draw counts on the base generator before and after executing `ObservationEncoder.encode` and `Quickslots.capture`. Assert that **zero RNG draws** occur while encoding state and that consecutive encodings of an unchanged world yield byte-identical feature vectors.
  * **RNG Stream Fingerprint Trace (`rngtrace`)**: Execute `rngtrace` to record per-step cumulative draw counts (`baseDraws`) and order-sensitive fingerprints (`baseFingerprint`). Compare the headless trace against the rendered viewer trace to verify that presentation draws are isolated on `PRandom` and do not shift the gameplay stream.

---

#### **3. Viewer vs. Trainer Stream Parity**
* **The Issue**:
  * **Separate Drain Loops**: The headless trainer (`SPDEnv.step` / `LevelPipeline`) and the visual viewer (`ReplayPlayer`) implement separate scheduler drain loops. `ReplayPlayer` originally lacked calls to `GameScene.clearPendingCellListener()` and `SlotAction.clearPendingUseItem()`, causing targeting and throw actions to fail to resolve during headless playback.
  * **Presentation randomness on the gameplay stream**: `Random.Int(int, boolean)` and `Random.shuffle(List)` advanced the gameplay generator without calling `RandomTrace.record`, so the RNG trace could not see the draws that actually offset the two environments. Combined with four presentation draws that only the rendered game makes — `CharSprite.link`'s random sprite facing, `AttackIndicator`'s highlight target, and two Mages Staff particle effects — the viewer's gameplay stream ran twelve values away from the trainer's, so every damage and defence roll after the first differed. All six are fixed.
* **How to Test**:
  * **Observation Purity and Trace Coverage Gate (`observecheck`)**: Run `observecheck`, which measures draw counts on the base generator before and after executing `ObservationEncoder.encode` and `Quickslots.capture`. Assert that **zero RNG draws** occur while encoding state and that consecutive encodings of an unchanged world yield byte-identical feature vectors. Its second half asserts the instrument itself: all 17 public `Random` draw paths and `Random.shuffle` must move the counter, so "the counts match" means the streams match. Mutation-tested — removing the `record` call from `Int` fails the gate and names every affected path.
  * **RNG Trace Window Diff**: Generate the base generator's expected output from `scrambleSeed(Dungeon.seed)` and look up where each side's first gameplay draw falls. Two runs located a twelve-value offset that an identical draw count had hidden for the whole investigation.
  * **Generation Window Tally**: Compare the `# generationSites=` header of a headless trace against a rendered one. This is the only part of a run the per-step comparison excludes, so it is where an offset introduced before the first recorded step has nowhere else to appear.
  * **Headless Playback Verifier (`playbackcheck`)**: Execute the `playbackcheck` gate, which drives `ReplayPlayer` headlessly without a window, scene, or display server. It verifies that faithful recordings play cleanly and uses mutation testing (tampering with recorded position, HP, turn, inventory, and quickslots) to assert that any trajectory divergence is caught at the exact altered step. **Its scope is the player's own logic only** - it cannot see anything in what surrounds `player.update`.
  * **Rendered Playback Gate (`viewcheck`)**: Execute `gradle :desktop:viewcheck`. It plays the whole committed corpus through the real viewer - real `GameScene`, real frame driver, real GL context - with the window hidden (`-Dspd.hidden`) but never faked, and fails if any recording diverges. One child JVM per recording, so a diverged run cannot leak a GL context into the next. **A gate that must not be run headless**, since the fault was in the stream the renderer draws from and a context-free harness cannot reproduce it; it needs a display, and for that reason it is deliberately *not* in `gates`. **Currently green**: 17 of 17 play clean, and stay clean across repeated runs.

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
| **`./gradlew :superintelligence:gates`** | Master aggregator running all 20 automated gate checks across all modules. | **All 20 gates pass cleanly** in ~22 seconds. |
| **`./gradlew :superintelligence:run --args="verify <file.replay>"`** | Headless re-execution of a recorded replay through `ActionMapper` & `LevelPipeline`. | Replays trajectory to completion, matching recorded position, HP, turns, and inventory. |
| **`transitioncheck` (`:superintelligence`)** | Drives real descents **and** an ascent through `Level.activateTransition`, and asserts the hero's first action on the new floor is not swallowed by the act where the floor's mobs notice him. 9 cases, mutation-tested. | Depth changes in both directions, the episode continues, the engine clock restarts with the floor, and the agent's first action there moves the hero. |
| **`paritycheck` (`:superintelligence`)** | Records a sweep of seeds x hero classes in one process, then verifies each recording twice - once immediately, once after the whole sweep has run in between. | Every recording replays exactly, both times. |
| **`playbackcheck` (`:desktop`)** | Drives `ReplayPlayer` logic headlessly without UI or display server. Covers the player's own logic only. | Faithful replays pass; altered steps (HP, position, turn, quickslots) fail at exact step. |
| **`viewcheck` (`:desktop`)** | Plays the **whole committed corpus through the real viewer** - real `GameScene`, real frame driver, real GL context, window hidden. Needs a display, so it is outside `gates`. | Every committed recording plays clean in the viewer. **Green**: 17 of 17, and stable across repeated runs. |
| **`observecheck` (`:superintelligence`)** | Verifies observation encoders (`ObservationEncoder`, `HeroEncoder`, `Quickslots`), **and** that every `Random` draw path is counted - so a count that agrees for the wrong reason cannot be read as parity. | **0 RNG draws** consumed during state encoding; byte-identical vector output; all 18 draw paths counted. |
| **`viewdiff` (`:superintelligence`)** | Diffs a headless `worldtrace` snapshot against a rendered `-Dspd.worldTrace` one, and reports frames that moved with no recorded step applied. | Names the first differing step **and field**, rather than the whole hero row under `pos`. |
| **`configcheck` (`:superintelligence`)** | Verifies the configuration binder, and that `superintelligence.properties` agrees with the compiled defaults. | Every documented key changes the setting it names; malformed and out-of-range values are refused. |
| **`rngtrace <file.replay>`** | Emits per-step draw counts and order-sensitive fingerprints (`baseFingerprint`). | Step-by-step fingerprint alignment between headless and rendered runs. |
| **`collectcheck` (`:superintelligence`)** | Verifies that live recordings captured by `EpisodeCollector` replay cleanly. | 100% reproduction of recorded step positions and scores. |
| **`modecheck` & `restartcheck`** | Verifies action modes (`WORLD`, `SLOT`, `TARGETING`, `MENU`, `INVENTORY`) and floor re-initialisation. | Zero mode traps; floor 1 regenerates identically after 400+ turns. |
| **Multi-JVM Byte-Diff Sweep** | Runs identical seed/hero rollouts across separate JVMs. | Generated `.replay` files are **byte-identical** across fresh JVMs. |

The sweep in `paritycheck` is the one that reaches the fault `resetcheck` cannot. `resetcheck` proves
that two runs of one seed produce the same trajectory, which is a statement about the environment.
`paritycheck` proves that a *recording* survives a write, a read and a second episode in the same
process, which is a statement about the recording - and it is the only gate that lets the hero die
repeatedly, which is where process-spanning game state such as a dead hero's remains becomes visible.

And `observecheck` is the one that reached the fault every other gate agreed was not there. Four
presentation draws were spending the gameplay RNG stream in the rendered game, which put the two
environments twelve values apart; `verify`, `playbackcheck` and `paritycheck` all compared a headless
run against a headless run and could not see it, and `RngTrace` reported identical draw counts at
every step because `Random.Int` was never counted. A gate that checks its own instrument found it,
after six hypotheses about ordering had been refuted by measurement.

`transitioncheck` is the third counter-example, and its shape is the useful part: it looked at the floor
the hero lands on and the clock he lands with, and never at whether he **acted** on arrival - so a fault
that ate the agent's first action on every new floor sat behind a gate that was passing. A recording
cannot catch it either, because a step whose action was thrown away still records a step. The case that
does has to end with a real action and assert the hero moved, which means it also needs a fixture where
something is actually in range to interrupt it - which is why the fixture is searched for at run time
rather than hard-coded, and why failing to find one is itself a reported failure.
