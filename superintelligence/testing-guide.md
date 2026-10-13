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
  * **Rendered Playback Gate (`viewcheck`)**: Execute `gradle :desktop:viewcheck`. It plays the whole committed corpus through the real viewer - real `GameScene`, real frame driver, real GL context - with the window hidden (`-Dspd.hidden`) but never faked, and fails if any recording diverges. One child JVM per recording, so a diverged run cannot leak a GL context into the next. **A gate that must not be run headless**, since the fault was in the stream the renderer draws from and a context-free harness cannot reproduce it; it needs a display, and for that reason it is deliberately *not* in `gates`. **Currently green**: 20 of 20 play clean.

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
  * **Corpus coverage (`viewcheck` / `playbackcheck`)**: `duelist-drop.replay`, regenerated by `gradle :superintelligence:dropfixture`, which **verifies the recording before writing it**, so a fixture that no longer plays cannot be committed in place of one that does. Mutation-tested by removing the `ThrowingSpike` entry from its `DROP` step's bindings, which fails `viewcheck` with `DIVERGED at step 2 - DROP/1 in SLOT` — re-run against the 0.9.0 regeneration, which changed only that file's reward column.
  * **Corpus coverage for a press (`duelist-throw.replay`)**: regenerated by `gradle :superintelligence:pressfixture`, same verify-before-write rule, and it additionally **refuses to write a run whose throw did not press** — the seed is searched, and a floor with no grass beside the hero is discarded rather than recorded. See the section on which gate refuses it.
  * **Round trip through the real trainer**: `gradle :superintelligence:train --args="--workers 2 --generations 2 --episodes 2 --max-turns 120"`, then `verify` and `:desktop:viewcheck` on what it wrote. This is the only check that covers the whole path, and it is the one that produced the measured claim in `issues.md` 15 - a recording the trainer writes verifies and plays clean in the real viewer, end to end. Its output is not checked in - `trainer-replays/` is gitignored and empty in the repository - so **copy one out before a reboot takes it**, because evidence in a temp directory is evidence with a timer on it.
    * **The catalog must say which recordings are affected, per file** (`replaycheck`). The section blurb used to say `NOT known to play end to end` for the whole training section, which was the only thing between a reader and a playback that halts at step 1 — and which became *wrong* the moment a fixed recording existed, because it condemned a file that plays. The label is read from the **four-field signature** by `ReplayIO.readHeader`, so it costs no parsing, and the check asserts the cheap scan and the full parse agree in **both** directions, because a scan that always answers one way passes a single-sided check.
* **One directory, two spellings, one set of entries** (`replaycheck`). The trainer resolved its default
    destination to `superintelligence/./trainer-replays` and the catalogue's own default spelled the same
    directory without the `.`; the deduplication compared strings, so all 185 trainer recordings were
    scanned twice. Measured before and after: 380 entries listed against 190, 170 `share this seed`
    warnings against 10, and - the part that was wrong rather than noisy - **1 recording marked
    `not watchable` against the correct 9**, because the dedup kept whichever spelling came first and so
    directory order decided it. The check spells one directory two ways and asserts one entry per file.
  * **A file name names a file** (`replaycheck`). The listing refused a bare seed several runs had
    recorded and told the reader to type a file name; typing one answered *no such recording*, because
    `byName` searched the seed-deduplicated list and a file is not a seed. Measured:
    `IPW-QMA-AFY-r3.dat` and `IPW-QMA-AFY-r3` both failed while the bare seed refused as designed. The
    check builds three files sharing one seed under different names, asserts the bare seed is still
    refused, asserts each file name resolves to itself, and asserts the instruction does not ask for a
      path.
* **The section header and its own rows must not contradict each other** (`replaycheck`). The trainer
    section's blurb read *"every one of them is watchable in the viewer"* over a section carrying nine
    lines marked `not watchable` - same screen, opposite claims. Written when the claim was true, never
    revisited when nine files came back. The header is what a skimming reader takes away, so a false one
    costs more than nine absent markers: it contradicts the list under it. The count is now in the blurb,
    making the claim a function of the section rather than a sentence somebody typed once. The check
    builds a section with one unplayable recording, asserts the header reports `1 of 2` and not "every
    one of them is watchable", and asserts the number matches the rows - counting row lines only, since
    the header names the state in words too and a naive count finds its own mention.

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
| **`./gradlew :superintelligence:gates`** | Master aggregator running all 23 automated gate checks across all modules. | **All 23 gates pass cleanly** in 23 seconds (measured 2026-10-12). |
| **`./gradlew :superintelligence:run --args="verify <file.replay>"`** | Headless re-execution of a recorded replay through `ActionMapper` & `LevelPipeline`, resolving each recorded slot index against its recorded quickslot bindings - the same `Quickslots.prepare` the viewer calls. | Replays trajectory to completion, matching recorded position, HP, turns, and inventory. Exits 3 rather than 0 on a recording whose slot indices cannot be resolved at all. |
| **`transitioncheck` (`:superintelligence`)** | Drives real descents **and** an ascent through `Level.activateTransition`, and asserts the hero's first action on the new floor is not swallowed by the act where the floor's mobs notice him. 9 cases, mutation-tested. | Depth changes in both directions, the episode continues, the engine clock restarts with the floor, and the agent's first action there moves the hero. |
| **`fallcheck` (`:superintelligence`)** | Drives a real chasm fall through `Level.occupyCell` - the transition that carries **no** `LevelTransition`, so `curTransition` is null on it by construction. 6 cases, each mutation-tested. | The fall lands the hero a floor down, alive, on a restarted clock, and the landing costs health the way `InterlevelScene.fall()` costs it; the hand-off leaves `InterlevelScene.mode` and `curTransition` clear, so the next episode's ordinary descent is a descent; walking toward a chasm asks the game's own question instead of throwing; the same fall twice in one process, with other runs in between, lands on the same cell of the same floor; and **a levitation that expires over a chasm drops the hero**, which `Levitation.detach` does not do headlessly. |
| **`presscheck` (`:superintelligence`)** | A `Food` dropped on an armed trap through the real `Level.drop`, the same drop onto high grass, and the same drop with `Dungeon.level` nulled - the state `RegularLevel.createItems` places starting items in. 3 cases, in `gates`, mutation-tested. | A dropped item fires the trap it landed on and tramples the grass it landed on, and level generation presses nothing - which is the half of the guard that actually does that work. |
| **`dialogcheck` (`:superintelligence`)** | The `WindowBridge` surface: a dialog the game opened, and an answer the agent gave it. 3 cases, each mutation-tested. | The game's own "jump?" prompt is reached, aged, answered, and closes itself, and the agent ends up a floor down; a dialog the game declines survives the answer and one it accepts closes; and a dialog raised *during* a turn is reported in that turn. |
| **`paritycheck` (`:superintelligence`)** | Records a sweep of seeds x hero classes in one process, then verifies each recording twice - once immediately, once after the whole sweep has run in between. | Every recording replays exactly, both times. |
| **`playbackcheck` (`:desktop`)** | Drives `ReplayPlayer` logic headlessly without UI or display server. Covers the player's own logic only, and plays the committed corpus. | Faithful replays pass; altered steps (HP, position, turn, quickslots) fail at exact step; playing a death recording leaves no `bones.dat` behind; every committed recording plays **every step**; a recording whose bindings were never written is refused *at* the first step that needed them, not after playing; and a death landing on the **final** recorded step still plays that step, which the corpus could not reach until `duelist-death-last.replay` was added. 23 cases. |
| **`viewcheck` (`:desktop`)** | Plays the **whole committed corpus through the real viewer** - real `GameScene`, real frame driver, real GL context, window hidden. Needs a display, so it is outside `gates`. | Every committed recording plays **every step** in the viewer - not merely "did not diverge", which is what a halt for any other reason used to score. **Green**: 20 of 20, and stable across repeated runs. |
| **`observecheck` (`:superintelligence`)** | Verifies observation encoders (`ObservationEncoder`, `HeroEncoder`, `Quickslots`), **and** that every `Random` draw path is counted - so a count that agrees for the wrong reason cannot be read as parity. | **0 RNG draws** consumed during state encoding; byte-identical vector output; all 18 draw paths counted. |
| **`viewdiff` (`:superintelligence`)** | Diffs a headless `worldtrace` snapshot against a rendered `-Dspd.worldTrace` one, and reports frames that moved with no recorded step applied. | Names the first differing step **and field**, rather than the whole hero row under `pos`. |
| **`configcheck` (`:superintelligence`)** | Verifies the configuration binder, and that `superintelligence.properties` agrees with the compiled defaults. | Every documented key changes the setting it names; malformed and out-of-range values are refused. |
| **`rngtrace <file.replay>`** | Emits per-step draw counts and order-sensitive fingerprints (`baseFingerprint`). | Step-by-step fingerprint alignment between headless and rendered runs. |
| **`collectcheck` (`:superintelligence`)** | Records a sweep of seeds and hero classes in one process, verifies each recording twice - once immediately, once after the sweep - round-trips a populated replay through the worker wire (`train.ReplayCodec`), and asserts that a `DROP` in `SLOT` is recorded against the bindings its index was chosen against. 7 cases. | Every recording replays exactly, both times; all nine step fields survive the pipe; and a rebinding step is recorded on the clock it was chosen on rather than the one it produced. |
| **`rolloutcheck` (`:superintelligence`)** | Records an episode with **no seed**, so `SeedPool` puts it on a random one, and verifies the result. 3 cases. **Red about one run in thirty for a reason `issues.md` 18 records without claiming to know it** - a random-seed episode occasionally records a trajectory that does not reproduce. A/B'd against the base tree, so it is not a recent change's doing. | A random-seed episode records a usable seed, and what it records verifies. **A green here is a claim about 97% of random draws, not about all of them**, which is worth stating rather than leaving a reader to assume the other reading. |
| **`slotcheck` (`:superintelligence`)** | A recorded slot index resolving to a different item, and the binding on an equipped item that no index can name. 6 cases. | Capture and restore reproduce slot resolution; a capture naming nothing clears a carried binding and leaves an equipped one alone. |
| **`verifycheck` (`:superintelligence`)** | Verification that cannot detect an altered recording, **and** verification that certifies one that cannot be played. 8 cases. | Position, health, engine time and inventory alterations are caught at the altered step; a recording carrying none of the five dropped step fields is refused; and a hero carrying nothing bound is not refused. |
| **`modecheck` & `restartcheck`** | Verifies action modes (`WORLD`, `SLOT`, `TARGETING`, `MENU`, `INVENTORY`) and floor re-initialisation. | Zero mode traps; floor 1 regenerates identically after 400+ turns. |
| **`rewardcheck` (`:superintelligence`)** | Drives the real environment and asserts the reward **prices the objective**, as properties rather than as constants. 20 cases. | Terminal outcomes are **ordered** (stall < death < turn limit < a cleared floor); stalling costs *more* than dying; descending beats idling out the floor's turn budget; mapping 120 tiles pays less than a descent; a step that advanced no engine time costs more than a real one; **an action consuming two engine turns is charged for both**; a search is never cheaper than a single-turn action; killing and surprise-attacking both pay, the surprise more; a talent point pays more than a level; **`reward.profile=depth_only` pays nothing but a descent and the four prices for stopping over an episode that provably fires 80 incidental events, while `default` still admits all 30 terms.** |
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

### The reward profile override, and how to test that an override is still an override

`reward.profile=depth_only` (`SPD_REWARD_PROFILE`, or a one-line properties file) silences every reward
term except a descent, a win and the four prices for stopping. Three cases in `rewardcheck` hold the
property, and the interesting one is `checkDepthOnlyPaysOnlyForDepth`.

**It asserts that the terms it silences were reached.** A profile case that only checked totals would pass
on an episode that never fired a kill, never dropped an item and never equipped anything - and a gate that
passes without measuring is the subject of half this file. So the driver places a mob (the three steps
`SummoningTrap` uses), fights it, alternates equip and unequip on the hero's weapon at zero engine time,
and descends through `Level.activateTransition`; and the case fails if `EXPLORE`, a combat term or
`NO_PROGRESS` were not recorded. Measured: **80 incidental events, all paying 0.00**, and the descent's
1800.00 was the whole of the run's positive score.

**The allow-list is written out rather than derived**, because "everything that is not shaping" would
admit `DAMAGE_TAKEN`, `GOLD_LOST` and `CURSE_EQUIPPED` - and a derived rule and a stated rule disagree the
first time a term is added.

**A profile that has become permanent is the failure mode worth gating**, and it is not a crash:
`checkDefaultProfilePaysEveryTerm` requires `DEFAULT` to admit all 30 terms and to be what a run with no
configuration and no `SPD_*` variable loads. Every other case in the file would still pass on its own
constants while the module quietly trained the experiment.

Both mutations are recorded in `CHANGELOG.md` 0.10.0; the shorter version is that a gate which has never
been seen to go red has not been tested, and the two reverts that make these three cases fail are one
line in `RewardLedger.add` and one line in `SPDEnv`'s constructor.

**Run a smoke test under the profile and read the header, not the score.** A trainer's replays travel the
worker pipe before they are written as text, so a profile that is wired everywhere except the codec is
dropped in transit and every recording says `default`. That is what happened: five recordings from a
3-generation run under `depth_only`, all of them `reward_profile=DEFAULT`. `collectcheck` now carries the
field on its wire case, with `DEPTH_ONLY` in the fixture rather than the default - a field that falls back
to the default when it is dropped cannot be tested by a fixture holding the default.

---

### The chasm, and a green that meant nothing had ever fallen

`issues.md` 16 is the fourth counter-example and the sharpest one, because nothing here was a corpus
problem at all: **no recording, no gate and no policy had ever put a hero into a chasm, so the code that
services a fall had never run.**

A fall is the only floor transition that carries no `LevelTransition`. `Chasm.heroFall` writes
`InterlevelScene.mode = FALL`, asks for a scene switch, and writes nothing else - no `curTransition`,
no destination, no landing cell. `LevelPipeline.handleTransition` tested `curTransition == null`
*before* it tested the mode, which is true on every fall by construction, so it answered
`Transition.NONE`, cleared the switch request and dropped the fall. `Transition.FALL` and
`LevelPipeline.handleFall()` were unreachable code.

Measured, before the fix, by putting the hero on a chasm cell and stepping:

```
depth 1 -> 1, switch request cleared, hero still standing on the pit
```

and by walking a hero at one:

```
NullPointerException: cannot invoke "SmartTexture.uvRect" because "this.texture" is null
  at Chasm$1.call(Chasm.java:64)          <- new Image(Dungeon.level.tilesTex(), ...)
  at Hero.getCloser(Hero.java:1885)
  at Actor.headlessStep(Actor.java:328)
  at Worker.serve(...)                    <- nothing between these two catches it
```

`fallcheck` drives both, and each of its six cases was reverted-and-refused in turn:

| Reverted | Case that refuses it |
| --- | --- |
| the `mode == FALL` test moved back behind the null test | `a fall is a floor` (and two others, since nothing else is true once the fall does not happen) |
| `Buff.affect(hero, Chasm.Falling.class)` removed from `handleFall` | `landing hurts` |
| `InterlevelScene.mode = null` removed from `clearSwitchRequest` | `a fall leaves no hand-off behind` |
| the null guard removed from `Image`'s five-argument constructor | `walking into a chasm does not take the process down` |
| the pipeline's standing-on-a-chasm check removed | `levitation ending over a chasm drops the hero`, with the hero left on the chasm cell at full health |

`presscheck` is the gate for `issues.md` 17 and is now **in `gates`** — it was left out only because it was
red, and a gate for an open defect is not a gate. Run it on its own with
`gradle :superintelligence:presscheck`. Its three cases are a dropped item firing a trap, the same drop
trampling high grass, and the same drop with `Dungeon.level` nulled, which is the state
`RegularLevel.createItems` places starting items in. The issue has the measurement and the reasoning, and
the two fixtures are below.

The fifth case is the question `issues.md` 16 asked, asked directly: the same seed and the same fall
twice in one process, with other runs churned in between, landing on the same cell of the same floor
with the same health, clock and `SpecialRoom.runSpecials`. It needed a fixture that drives a fall, which
is what this gate is.

---

### The question the game asks before it drops you, and the answer it never got

`dialogcheck` covers the other half of the same path, and it is the half that made the chasm
*unreachable* rather than merely broken.

The agent was **offered** the game's "jump?" prompt and then had the answer thrown away. `Chasm`'s
dialog gates both its option and its hide on `elapsed > 0.2f`, a counter its own `update()` increments
and `PixelScene.update()` is the only thing that ever called — so headlessly the option could not fire.
And `WindowBridge.selectOption` cleared the parked window on any press, so a handler that declined had
its dialog removed anyway, which is exactly what `Chasm` does while `elapsed` is under 0.2 s.

`WindowBridge.age()` advances a parked dialog once per environment step, which is once per frame — the
same approximation `ReplayPlayer` makes about a recorded step, so the gate is the game's own 0.2 s and
**the answer takes 13 steps**. Those are not free, contrary to what this file said when the case was
first written: the 12 *refused* steps cost nothing, because `settle` returns before it scores a turn, but
the step that accepts the answer resumes the hero's interrupted move and that turn is scored as one
which advanced no engine time. **One crossing costs -15.00** (`NO_PROGRESS -12.00`, `TURN_COST -3.00`),
against a descent at `+1800.00`.

Separately, `WindowBridge.selectOption` cleared the parked window on any press, so a handler that
declined had its dialog removed anyway — which is exactly what `Chasm` does while `elapsed` is under
0.2 s. A parked window now lives in a `Group`, so the game's own `hide()` closes it. And `settle` reports
a dialog opened *during* the drain rather than a turn later, because a rendered game blocks cell input
the instant the window appears.

| Reverted | Cases that refuse it |
| --- | --- |
| `WindowBridge.selectOption` clearing the slot again | 2 of 3 |
| `GameScene.headlessWindow()` ignoring the game's own `hide()` | 3 of 3 |
| `settle` not reporting a dialog the drain opened | 1 of 3 |
| `WindowBridge.age()` neutered | 2 of 3 |

Measured end to end: **the prompt opens, the agent answers, the game accepts, the dialog closes itself,
and the next move puts the hero a floor down — `DIALOGCHECK-A`, 13 steps for the answer, depth 1 → 2.**

**What is left, and it is small:** `Chasm.jumpConfirmed` is a one-shot, so a second chasm asks again —
measured, and that is the game's own behaviour rather than something this module introduced.
`Levitation.detach()` still does not re-check the landing cell headlessly, because the game guards that
call on a `GameScene` (`ENGINE-CHANGES.md` §7): measured, a hero whose levitation expires over a pit
ends up standing *on* the pit cell, and walking off it does not drop him either.

---

### Which gate refuses a fault in the trainer's half of a fix

`issues.md` 17 is the clearest instance in the suite of a fix that changes **only one** of the two
environments, and the mutation test says something the test plan did not predict.

`Level.drop` presses the cell a thrown item lands on. It did so only when the current scene is a
`GameScene`, which is false in a headless process and true in the viewer — so a thrown item landed inert
in the trainer and correctly in the viewer. The fix adds one term that only `HeadlessGame.install()`
sets. **Reverting it leaves the viewer untouched by construction**, and the gates say so:

| Gate | After reverting the fix |
| --- | --- |
| `presscheck` | **red**, 2 of 3 cases |
| `viewcheck` (real viewer, 20 recordings) | **green** — correctly, and not a hole |
| `verify test-replays/duelist-throw.replay` | **red**: `diverged at step 27 of 60 - hp: replayed 17, recorded 19` |
| `playbackcheck` | **red**: `duelist-throw.replay diverged at step 27: DIVERGED at step 28 - INTERACT/0 in WORLD` |

So **a fault that exists only in the trainer cannot be caught by the rendered gate, and that is not a
limitation of the rendered gate** — it has nothing to look at. The recording is what closes it, and the
recording only closes it because it contains a press: `duelist-throw.replay` throws a `ThrowingSpike` at
high grass (terrain `15 → 2` at the landing cell), and the resulting divergence in health is nine steps
downstream of it.

That is the sixth time this suite has hit the same shape from a different direction, and the rule is the
one the rest of this file keeps arriving at: **a green is a claim about what was checked.** `viewcheck`
being green here is a true statement about the viewer and says nothing about the trainer, and the gate
that says something about the trainer is the one that re-executes a recording headlessly.

