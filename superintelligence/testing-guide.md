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
  * **Rendered Playback Gate (`viewcheck`)**: Execute `gradle :desktop:viewcheck`. It plays the whole committed corpus through the real viewer - real `GameScene`, real frame driver, real GL context - with the window hidden (`-Dspd.hidden`) but never faked, and fails if any recording diverges. One child JVM per recording, so a diverged run cannot leak a GL context into the next. **A gate that must not be run headless**, since the fault was in the stream the renderer draws from and a context-free harness cannot reproduce it; it needs a display, and for that reason it is deliberately *not* in `gates`. **Currently green**: 17 of 17 play clean.

    **It defaults to 8 children at once, and that is a valid result.** Children were forked one at a time until this default changed; `VIEWCHECK_JOBS=N` (or `-PviewcheckJobs=N`) overrides it either way. Measured on the 17 committed recordings: **37.0s at 8 children against 219.7s sequential**, 17 of 17 clean at both. What concurrency costs is the **failing step**, not the verdict: playback is timing-sensitive, so several children at once report the same *recordings* as diverging but shuffle *which step* each one fails at. The gate prints a note about that **only when something actually failed**, and its `[OK]` line reports the mode too, so a green says which measurement produced it. `VIEWCHECK_JOBS=1` reproduces failing steps exactly.

  **What it does not cover, and it is not a small thing.** Every child is forked *hidden* at speed 40, so the gate has never run the timing path a human run takes. Playback is timing-sensitive by its own nature - a recorded action can be pending across a frame boundary while another actor's animation resolves - and `TODO.md` §0.7 records a divergence reported by hand at speed 1 in a fullscreen window that 50-odd attempts, including 20 concurrent visible children, could not reproduce. A green `viewcheck` is evidence that the hidden, fast path agrees with the recording. It is not evidence that a slow, visible one does.

  **What it now refuses to pass.** The verdict used to be `!playback.diverged()`, which is the same shape of claim `ReplayIO.verify` used to make with its sentinels: a player that halted for *any other* reason - an unresolvable slot index, a stall, a refusal - left `diverged()` false and scored a pass. Measured on a fresh trainer recording whose bindings could not be resolved at step 13:

  ```
  before    [OK]     rendered playback: 1 recordings played clean in the real viewer (3.7s)
  after     [ERROR]  rendered playback: 1 of 1 recordings diverged (3.0s)
              [replay] halted: step 1 names slot 3 but records no quickslot bindings
  ```

  3.7 seconds for a 1499-step recording is the tell: a gate that stops early and reports success is worse than one that reports nothing, because the next reader believes the file was watched. The gate now requires `cursor >= total()` **and** no divergence, and names the shortfall in the failure line.

  **And its child budget must exceed a full-length recording.** It was 120 s, against a 1508-step trainer
  recording that takes ~4 minutes at gate speed, so the gate reported *"the viewer never stopped"* for
  runs that had stopped having applied every step - 1508 of 1508, 1100 of 1100. A false failure whose
  message names the opposite of what happened, now 300 s. Found only because `trainer-replays` gained a
  full-length run the corpus did not contain; **the gate had been green because nothing in it was long
  enough to trip over.**

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

#### **6. A Recording Is Only Watchable If It Can Say What Its Slot Index Meant**
* **The Issue**: A recorded slot index is not self-describing. `ActionMapper.refreshSlots` places quickslot-bound items into their bound slots *first*, then fills the gaps from the backpack in order, so the same index names a different item depending on the bindings. `Replay.Step` therefore carries a per-step `quickslots` field, and a recording without it cannot be played at all - yet `ReplayIO.verify` guards every comparison with a sentinel meaning *not recorded*, so it verified such a file on position alone and printed `[OK]`. Nine recordings the trainer had written were in that state, and the one that survived the wire fix diverged on its first `DROP`, because the trainer had pinned the bindings *after* the step.
* **How to Test**:
  * **Sentinel-absence gate (`verifycheck`)**: blank the bindings **and** the health, turn and inventory the same wire would have dropped, on every step, and require `ReplayIO.verify` to **refuse** it, naming the first step it cannot play, rather than reproduce it. Alongside it, the converse: blank only the bindings, leaving health and engine time real, and require the run to still verify — because a hero who has dropped his last bound item has none, and the index resolves from backpack order.

    **The second case exists because the first was wrong twice, and both versions are worth recording.** Refusing per step refused a real state, which a fresh trainer run exposed (211 SLOT and INVENTORY steps, one carrying bindings, the rest empty because the policy had dropped the Waterskin at step 0). Asking the whole recording instead — "no bindings anywhere" — was wrong again, because it is equally true of a run in which the hero had **nothing** bound at every slot step, and that recording plays perfectly. It arrived as an intermittent `rolloutcheck` failure rather than as an honest red: each run depends on what a random episode happened to be carrying.

    **So the discriminator is the signature of the broken wire, not the absence of one field** — health, turn and inventory absent *together*, on every step. Presence of evidence rather than absence of it. Two further fixtures made the mistake from the other direction and were caught the same way: `playbackcheck`'s "no resolvable slots" fixture blanked only bindings and so became a healthy recording, and `replaycheck`'s `write()` helper wrote every field at its sentinel so a "healthy" fixture carried the four-field signature.
  * **Instant-of-capture gates (`collectcheck`)**: assert that a rebinding step is recorded against the world its slot index was chosen in, and that `EpisodeCollector.Listener` announces a decision before `env.step` applies it. The second asserts on `Actor.now()` rather than on a binding, because asking `Dungeon.quickslot` from inside the listener depends on the very wiring under test - called after the step the dropped item has already left the backpack and reads as unbound, so the mutant reports "no fixture found" rather than the fault.
  * **Corpus coverage (`viewcheck` / `playbackcheck`)**: `duelist-drop.replay`, regenerated by `gradle :superintelligence:dropfixture`, which **verifies the recording before writing it**, so a fixture that no longer plays cannot be committed in place of one that does. Mutation-tested by removing the `ThrowingSpike` entry from its `DROP` step, which fails `viewcheck` with `DIVERGED at step 2 - DROP/1 in SLOT`.
  * **Round trip through the real trainer**: `gradle :superintelligence:train --args="--workers 2 --generations 2 --episodes 2 --max-turns 120"`, then `verify` and `:desktop:viewcheck` on what it wrote. This is the only check that covers the whole path, and it is the one that produced the measured claim in `issues.md` 15 - a recording the trainer writes verifies and plays clean in the real viewer, end to end. Its output is checked into `trainer-replays/` as well, because **evidence in a temp directory is evidence a reboot deletes**, and because the trainer section of the catalog was otherwise 10 of 10 unplayable.
  * **The catalog must say which recordings are affected, per file** (`replaycheck`). The section blurb used to say `NOT known to play end to end` for the whole training section, which was the only thing between a reader and a playback that halts at step 1 — and which became *wrong* the moment a fixed recording existed, because it condemned a file that plays. The label is read from the **four-field signature** by `ReplayIO.readHeader`, so it costs no parsing, and the check asserts the cheap scan and the full parse agree in **both** directions, because a scan that always answers one way passes a single-sided check.

  **A corpus that cannot witness a fault is a gate that cannot fail, and this one was.** For the whole
  life of `issues.md` 15 the corpus held 17 recordings and 310 SLOT-mode steps, and the entire SLOT
  vocabulary was `USE/SLOT` - `ScriptedPolicy` never chooses `DROP`, and an untrained policy only drops
  by accident. `viewcheck` was green on all seventeen **because the thing it would have caught was
  absent**. Adding one recording to a directory both gates already read closed the hole with no new
  plumbing at all, which is the cheapest form that fix can take and the reason to prefer it over a
  second corpus.

---

### Automated Testing Suite & Verification Matrix

| Test Command / Task | Subsystem Verified | Success Criterion |
| :--- | :--- | :--- |
| **`./gradlew :superintelligence:gates`** | Master aggregator running all 20 automated gate checks across all modules. | **All 20 gates pass cleanly** in ~22 seconds. |
| **`./gradlew :superintelligence:run --args="verify <file.replay>"`** | Headless re-execution of a recorded replay through `ActionMapper` & `LevelPipeline`, resolving each recorded slot index against its recorded quickslot bindings - the same `Quickslots.prepare` the viewer calls. | Replays trajectory to completion, matching recorded position, HP, turns, and inventory. Exits 3 rather than 0 on a recording whose slot indices cannot be resolved at all. |
| **`transitioncheck` (`:superintelligence`)** | Drives real descents **and** an ascent through `Level.activateTransition`, and asserts the hero's first action on the new floor is not swallowed by the act where the floor's mobs notice him. 9 cases, mutation-tested. | Depth changes in both directions, the episode continues, the engine clock restarts with the floor, and the agent's first action there moves the hero. |
| **`paritycheck` (`:superintelligence`)** | Records a sweep of seeds x hero classes in one process, then verifies each recording twice - once immediately, once after the whole sweep has run in between. | Every recording replays exactly, both times. |
| **`playbackcheck` (`:desktop`)** | Drives `ReplayPlayer` logic headlessly without UI or display server. Covers the player's own logic only, and plays the committed corpus. | Faithful replays pass; altered steps (HP, position, turn, quickslots) fail at exact step; playing a death recording leaves no `bones.dat` behind; every committed recording plays **every step**; a recording whose bindings were never written is refused *at* the first step that needed them, not after playing; and a death landing on the **final** recorded step still plays that step, which the corpus could not reach until `duelist-death-last.replay` was added. 23 cases. |
| **`viewcheck` (`:desktop`)** | Plays the **whole committed corpus through the real viewer** - real `GameScene`, real frame driver, real GL context, window hidden. Needs a display, so it is outside `gates`. | Every committed recording plays **every step** in the viewer - not merely "did not diverge", which is what a halt for any other reason used to score. **Green**: 19 of 19, and stable across repeated runs. |
| **`observecheck` (`:superintelligence`)** | Verifies observation encoders (`ObservationEncoder`, `HeroEncoder`, `Quickslots`), **and** that every `Random` draw path is counted - so a count that agrees for the wrong reason cannot be read as parity. | **0 RNG draws** consumed during state encoding; byte-identical vector output; all 18 draw paths counted. |
| **`viewdiff` (`:superintelligence`)** | Diffs a headless `worldtrace` snapshot against a rendered `-Dspd.worldTrace` one, and reports frames that moved with no recorded step applied. | Names the first differing step **and field**, rather than the whole hero row under `pos`. |
| **`configcheck` (`:superintelligence`)** | Verifies the configuration binder, and that `superintelligence.properties` agrees with the compiled defaults. | Every documented key changes the setting it names; malformed and out-of-range values are refused. |
| **`rngtrace <file.replay>`** | Emits per-step draw counts and order-sensitive fingerprints (`baseFingerprint`). | Step-by-step fingerprint alignment between headless and rendered runs. |
| **`collectcheck` (`:superintelligence`)** | Records a sweep of seeds and hero classes in one process, verifies each recording twice - once immediately, once after the sweep - round-trips a populated replay through the worker wire (`train.ReplayCodec`), and asserts that a `DROP` in `SLOT` is recorded against the bindings its index was chosen against. 7 cases. | Every recording replays exactly, both times; all nine step fields survive the pipe; and a rebinding step is recorded on the clock it was chosen on rather than the one it produced. |
| **`slotcheck` (`:superintelligence`)** | A recorded slot index resolving to a different item, and the binding on an equipped item that no index can name. 6 cases. | Capture and restore reproduce slot resolution; a capture naming nothing clears a carried binding and leaves an equipped one alone. |
| **`verifycheck` (`:superintelligence`)** | Verification that cannot detect an altered recording, **and** verification that certifies one that cannot be played. 8 cases. | Position, health, engine time and inventory alterations are caught at the altered step; a recording carrying none of the five dropped step fields is refused; and a hero carrying nothing bound is not refused. |
| **`modecheck` & `restartcheck`** | Verifies action modes (`WORLD`, `SLOT`, `TARGETING`, `MENU`, `INVENTORY`) and floor re-initialisation. | Zero mode traps; floor 1 regenerates identically after 400+ turns. |
| **`rewardcheck` (`:superintelligence`)** | Drives the real environment and asserts the reward **prices the objective**, as properties rather than as constants. 16 cases. | Terminal outcomes are **ordered** (stall < death < turn limit < a cleared floor); stalling costs *more* than dying; descending beats idling out the floor's turn budget; mapping 120 tiles pays less than a descent; a step that advanced no engine time costs more than a real one; **an action consuming two engine turns is charged for both**; a search is never cheaper than a single-turn action; killing and surprise-attacking both pay, the surprise more; a talent point pays more than a level. |
| **Multi-JVM Byte-Diff Sweep** | Runs identical seed/hero rollouts across separate JVMs. | Generated `.replay` files are **byte-identical** across fresh JVMs. |

### The reward gate, and what "property rather than constant" means there

`rewardcheck` is the only gate whose subject is an objective rather than a mechanism, and that changes
what a case in it is allowed to assert. A gate that compares `stall == -100` is asserting that a number
has not moved; a gate that asserts `stall < death < turn_limit < depth_reward` is asserting that the
agent has no reason to pick any of the three cheap endings, and it survives every one of them being
retuned. Four of its thirteen cases are of the second kind, and one of them — the ordering — is the one
that would have caught every defect in `issues.md` 14.

**Two cases exist because the first version of them could not fail.** `checkStallingIsNotCheaperThanDying`
originally tested `stall.score < death` and printed "cheaper"; a score is negative, so `-0.24 < -100` is
false and the condition fired on a stall being *more* expensive than dying. It never fired on the -5.0 it
was written to catch. And `checkKillingIsWorthDoing` took three attempts: a random walk from the action
mask met no mob on its seed and reported success; a hunt over the whole level walked the hero into a
dead end for 900 turns and killed nothing, because the level is rooms joined by doors and an enemy the
hero can see but cannot reach is an ordinary situation. Only the third version places a mob deliberately,
through the three steps `SummoningTrap` uses.

The general form, and it is the same one `observecheck` and `graphcheck` were built for: **a check that
cannot reach the code it guards is not a check.** Where reaching it requires a fixture, build the fixture
from the engine's own path — and assert that it was built, rather than skipping when it could not be.

**Producing the event rather than waiting for it is the other half.** `checkTheHeroCanDie` damages the
hero outright instead of waiting for the mob cluster, because whether a random walk dies on floor 1 is a
property of the game's balance and a gate that failed on it would start failing the moment the hero got
better at surviving. `checkSpendingATalentIsWorthALot` spends 38 talent points through
`Hero.upgradeTalent` for the same reason — **nothing in the game can spend one yet**, so a case that
waited for the agent to do it would measure nothing forever. That distinction is the difference between
"the emission site is proven live" and `KILL`'s "declared, plumbed and never emitted", which is the
same shape and the same silence.

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

`issues.md` 15 is the fourth, and it has the sharpest version of the general form: **a gate that passes
because the thing it would catch is absent is not a passing gate.** `viewcheck` was green on seventeen
recordings, `verify` was green on nine recordings the viewer refused to play at step 1, and every one of
those greens was correct about its own question. The corpus held 310 SLOT-mode steps and not one
`DROP`, because `ScriptedPolicy` never chooses one and an untrained policy only drops by accident — so
the only action that unbinds its own item, and therefore the only one where the *instant* a binding is
captured changes what a slot index means, had never been recorded by anything.

The generalisation, and it is the same sentence three times now: **a green is a claim about what was
checked.** Three of them here were claims about a corpus that could not express the question. The fix
that works is not a subtler assertion; it is making the corpus able to ask.
