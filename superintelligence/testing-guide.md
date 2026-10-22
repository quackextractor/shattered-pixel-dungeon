### Key Issues to Test

#### **1. Static State Leakage Across Resets & Episodes**
* **The Issue**: Residual static variables (such as `GameScene.pendingCellListener`, `GameScene`'s headless dialog slot, `SlotAction.pendingUseItem`, and a dead hero's remains in `Bones`) persist across consecutive episode resets within the same JVM process. If an episode terminates while mid-aim or in a sub-menu, un-cleared static state forces the subsequent episode to start in `TARGETING` or `MENU` mode, causing immediate step-0 or step-1 position divergence. Remains are the subtler case: a hero who dies leaves a `REMAINS` heap on a later floor, so the world a run generates depends on what died earlier in the same process.
* **The same fault, in a place no trajectory trace could see**: `Document` is an enum, so `pagesStates` is a process static, and **level generation reads it**. `EntranceRoom.placeEarlyGuidePages:121` drops floor 2's guidebook page on `!isPageFound(Document.GUIDE_SEARCHING)`; `RegularLevel.createItems:559-584` drops one from `missingPages.get(0)` onto a cell that may be high grass. Collecting a page marks it found, so every episode after the first in a process built a **different floor 2**. `paritycheck --seeds 1 --heroes 1 --turns 400` was red on it with `DIVERGED at 256: position: replayed 500, recorded 1301`, and `resetcheck`'s own trajectory cases could not see it, because their hero never reached floor 2. That is the seventh instance of this file's rule — *a green is a claim about what was checked* — from a new direction: the gate that traces trajectories cannot see a fault in the generator of a floor no traced episode reaches.
* **How to Test**:
  * **Sequential Multi-File Verification**: Run verification across multiple replay files sequentially within a **single JVM process** (or verify the same replay file 2–3 times consecutively in one process). A clean implementation must pass every pass without order-dependent divergence.
  * **`restartcheck` Gate**: Execute the automated `restartcheck` gate, which replays the viewer's restart sequence after a scripted run has died on floor 1 to prove that floor 1 regenerates identically without residual state corruption.
  * **`resetcheck` Gate**: Execute the automated `resetcheck` gate, which arms an aim request, opens a dialog and kills a hero - one case each - then asserts the next episode starts clean. Its seventh case is the one that had to be written rather than discovered: **generate floor 2, collect three guide pages, generate floor 2 again, and require the same dungeon** - fingerprinted by dimensions, terrain, custom walls, heaps, traps, mobs and plants. Removing `RunState`'s `Document.resetPages()` call fails it with `First difference: "heaps=17" vs "heaps=16"`.
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
  * **Rendered Playback Gate (`viewcheck`)**: Execute `gradle :desktop:viewcheck`. It plays the whole committed corpus through the real viewer - real `GameScene`, real frame driver, real GL context - with the window hidden (`-Dspd.hidden`) but never faked, and fails if any recording diverges. One child JVM per recording, so a diverged run cannot leak a GL context into the next. **A gate that must not be run headless**, since the fault was in the stream the renderer draws from and a context-free harness cannot reproduce it; it needs a display, and for that reason it is deliberately *not* in `gates`. **Currently green**: 20 of 20 play clean. The one fault this gate existed for is closed, and the closing is a measurement rather than a claim. `duelist-death-last.replay` diverged at step 95 for the whole life of `issues.md` 26. `RandomTrace.siteLog()` - the ordered draw log, which a per-site tally cannot replace because a tally has no order - is now written by both environments and names the first draw at which they part: the headless stand-in for a particle emitter drew from the **gameplay** stream where `Emitter.start` draws from `PRandom`, so the trainer spent one value the rendered game never spends, from the first scroll read onwards, and every later damage and defence roll read from a different position in the stream. Every instrument that compared **step boundaries** was right about its own question and blind to a one-value offset in the middle of a step. `HeadlessSprite$Inert.start` now draws from `PRandom`, `observecheck` holds both halves of that, and `duelist-death-last.replay` was re-recorded - the only one of the twenty that read a scroll, so no other file in the corpus changed.

    **It defaults to 8 children at once, and that is a valid result.** Children were forked one at a time until this default changed; `VIEWCHECK_JOBS=N` (or `-PviewcheckJobs=N`) overrides it either way. Measured on the 17 committed recordings: **37.0s at 8 children against 219.7s sequential**, 17 of 17 clean at both. What concurrency costs is the **failing step**, not the verdict: playback is timing-sensitive, so several children at once report the same *recordings* as diverging but shuffle *which step* each one fails at. The gate prints a note about that **only when something actually failed**, and its `[OK]` line reports the mode too, so a green says which measurement produced it. `VIEWCHECK_JOBS=1` reproduces failing steps exactly.

  **It also gates the two viewer diagnostics, and it runs them whether or not the corpus is green** - so a corpus failure cannot hide a diagnostic failure. Both were in `CHANGELOG.md` with a measured answer and no gate over them, which means either could have stopped working with nothing to say so:

  | check | how | measured |
  | --- | --- | --- |
  | `spd.replayContinueOnDiverge` | Writes a copy of `duelist-death-last.replay` with step 10's cell moved by one, plays it twice - once with the flag, once without - and compares how many steps each applied | **Every step with it, 11 without.** Dropping the flag fails it with both runs at 11 |
  | `spd.hidden` | Forks a child that calls `ReplayLauncher.windowConfig()` and halts, with and without the property, and reads what the launcher reported | **`hidden=false` without it, `hidden=true` with it** |

  The second deliberately **does not open a window**. `windowConfig()` reads the property, builds the configuration and prints `hidden=`; nothing constructs an `Lwjgl3Application`. The project's own record is that the viewer's defaults once made it take over the desktop during an unattended run, and a gate that did that to prove a flag was wired is a gate nobody runs. `-PspdHidden` is still not how you set it - that property lands on the *supervisor*, and the children are what open the window, which is exactly why it once looked set and was not.

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
  message names the opposite of what happened. Found only because `trainer-replays` gained a
  full-length run the corpus did not contain; **the gate had been green because nothing in it was long
  enough to trip over.** A fixed 300 s failed the same way at 20 children: `fresh-1022` `SZZ-PPW-YGF`
  needs 11,854 frames for 542 steps and plays clean in 187 s alone. The budget is now progress, not
  time: each child runs with `spd.heartbeat`, and is killed only after `viewcheckStallSeconds` (120)
  without a new step.

---

#### **4. Cross-JVM Determinism & Worker Path Parity**
* **The Issue**: Ensuring that policy actions, seed locks, and worker process rollouts remain bit-identical across fresh JVM instances, multi-process training worker pools, and network-driven policy forward passes.
* **How to Test**:
  * **Byte-for-Byte Replay Comparison**: Run multiple rollouts of identical (seed, hero) pairs across fresh JVMs using `--save` and compare the resulting `.replay` files byte-for-byte.
  * **Worker Training Parity**: Run two identical multi-worker training runs with a fixed seed (e.g. `--seed 4242`) and verify that the generated best-per-seed replays are byte-identical end-to-end.
  * **Worker-side RNG attribution** (`--rng-trace-dir <dir>`): the instrument `issues.md` 37 needed and no process boundary had ever crossed. The worker enables `RandomTrace.attribute()`, splits its ordered draw log on the same line the viewer splits on (`onEpisodeStart`, immediately after `env.reset`), and writes `<dir>/<seed>.worker.gen.order.txt` and `.all.order.txt` plus the generator-stack logs, in the same format as the viewer and as `reprocheck order`. `reprocheck cross` then diffs a worker's own episode against a verification of the recording it wrote, and `OrderedDiff` names the first draw at which the two part. Measured on the failure it was built for: the two agreed for 705 of 712 draws and then named `Bones.pickItem` four times on the recording side only, which is `issues.md` 37's whole cause in four lines. Off unless the flag is given - attribution walks the stack on every draw.

---

#### **5. Seed Lock Resolution & Replay Header Completeness**
* **The Issue**:
  * **Unresolved Random Seeds**: Unseeded or random-seed episodes previously recorded an empty seed string (`seed=`), making the replay un-rebuildable because verification would draw a fresh random seed and diverge at step 0.
  * **Missing Inventory Head Configuration**: Replay headers originally omitted `max_slots` and `allow_equipping` (the parameters read by `ActionMapper`), causing slot index resolution ambiguity during playback.
* **How to Test**:
  * **Random Seed Resolution Test**: Verify that random-seed rollouts capture the resolved seed text (`env.seedText()`) in the replay header and reproduce 100% cleanly upon re-verification.
  * **Header Field Round-Trip Test**: Test Replay v2 headers containing non-default `max_slots` and `allow_equipping` values to verify that settings are reconstructed faithfully during playback.
  * **Limit Round-Trip Test**: Headers carry `turn_limit_total`, `stall_limit`, `free_step_budget` and `actor_step_limit` (absent keys read as the `EnvConfig` defaults), so a run recorded under non-default limits replays under them; `verifycheck` reads back 11/12/13/14 and fails a replay that ends before its recording does.

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
  * **Round trip through the real trainer**: `gradle :superintelligence:train --args="--workers 2 --generations 2 --episodes 2 --max-turns 120"`, then `verify` and `:desktop:viewcheck` on what it wrote. This is the only check that covers the whole path, and it is the one that produced the measured claim in `CHANGELOG.md` - a recording the trainer writes verifies and plays clean in the real viewer, end to end. Its output is not checked in - `trainer-replays/` is gitignored and empty in the repository - so **copy one out before a reboot takes it**, because evidence in a temp directory is evidence with a timer on it.
    * **The catalog must say which recordings are affected, per file** (`replaycheck`). The section blurb used to say `NOT known to play end to end` for the whole training section, which was the only thing between a reader and a playback that halts at step 1 — and which became *wrong* the moment a fixed recording existed, because it condemned a file that plays. The label is read from the **four-field signature** by `ReplayIO.readHeader`, so it costs no parsing, and the check asserts the cheap scan and the full parse agree in **both** directions, because a scan that always answers one way passes a single-sided check.
* **One directory, two spellings, one set of entries** (`replaycheck`). The trainer resolved its default
    destination to `superintelligence/./trainer-replays` and the catalogue's own default spelled the same
    directory without the `.`; the deduplication compared strings, so all 185 trainer recordings were
    scanned twice. Measured before and after: 380 entries listed against 190, 170 `share this seed`
    warnings against 10, and - the part that was wrong rather than noisy - **1 recording marked
    `not watchable` against the correct 9**, because the dedup kept whichever spelling came first and so
    directory order decided it. The check spells one directory two ways and asserts one entry per file.
  * **A group is a section, and a group's subdirectory is not a hole** (`replaycheck`). Two cases, and
    each of four ways of breaking them was reverted and watched to fail:

    | reverted | what fails |
    | --- | --- |
    | `sectionOf` always answering `TRAINER OUTPUT` | `replaycheck 1 of 19 fails` - the group's two rows land in the ungrouped section |
    | the subdirectory walk | `replaycheck 2 of 19 fail` - a recording written into `trainer-replays/<id>/` is not listed at all |
    | the `rejected/` exclusion | `a recording in rejected/ is listed as a run to watch` |
    | the `group` header key not parsed | `replaycheck 1 of 19 fails` |

    The subdirectory half is the one that matters and the one that is invisible while it is wrong: **a
    run that is grouped writes into a directory the picker reads as empty** - written, ranked, closed and
    unreachable, which is the failure `ReplayIndex` exists to stop happening by accident. The `rejected/`
    half is the other direction: that directory holds what a corpus rebuild could not reproduce, and
    offering it in a picker is a dead end, which `CHANGELOG.md` already paid for once. The group itself is
    read from the **header**, so a recording moved out of its folder still says which run wrote it -
    and the header-parity case now carries a group, because the catalogue's whole grouping decision comes
    from the cheap header-only read.
  * **A file name names a file** (`replaycheck`). The listing refused a bare seed several runs had
    recorded and told the reader to type a file name; typing one answered *no such recording*, because
    `byName` searched the seed-deduplicated list and a file is not a seed. Measured:
    `IPW-QMA-AFY-r3.dat` and `IPW-QMA-AFY-r3` both failed while the bare seed refused as designed. The
    check builds three files sharing one seed under different names, asserts the bare seed is still
    refused, asserts each file name resolves to itself, and asserts the instruction does not ask for a
      path.
* **A view numbers what it shows** (`replaycheck`, `ReplayCatalogViewCheck`, 6 cases). Rows under a
  sort or filter are numbered in printed order and `--select` resolves the same list; `only floor-1`
  lists that group and not `floor-10` or `pre-floor-1`, while `only floor` takes both floors by prefix;
  a collapsed group is one summary line with no numbers; hero, depth, score, find and top narrow rows; a
  saved view reloads unchanged; a mistyped command is refused by name. Seen red: substring group
  matching (`'only floor' listed ... PRE-ONE`) and a flipped sort direction (`top 1` picked `F1-DEEP`).
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
  life of `CHANGELOG.md` the corpus held 17 recordings and 310 SLOT-mode steps, and the entire SLOT
  vocabulary was `USE/SLOT` - `ScriptedPolicy` never chooses `DROP`, and an untrained policy only drops
  by accident. `viewcheck` was green on all seventeen **because the thing it would have caught was
  absent**. Adding one recording to a directory both gates already read closed the hole with no new
  plumbing at all, which is the cheapest form that fix can take and the reason to prefer it over a
  second corpus.

---

### Automated Testing Suite & Verification Matrix

| Test Command / Task | Subsystem Verified | Success Criterion |
| :--- | :--- | :--- |
| **`./gradlew :superintelligence:gates`** | Master aggregator running all 34 automated gate checks across all modules. | **All 34 gates pass cleanly** in one invocation (measured 2026-10-16 at ~40 s). |
| **`./gradlew :superintelligence:run --args="verify <file.replay>"`** | Headless re-execution of a recorded replay through `ActionMapper` & `LevelPipeline`, resolving each recorded slot index against its recorded quickslot bindings - the same `Quickslots.prepare` the viewer calls. | Replays trajectory to completion, matching recorded position, HP, turns, and inventory. Exits 3 rather than 0 on a recording whose slot indices cannot be resolved at all. |
| **`transitioncheck` (`:superintelligence`)** | Drives real descents **and** an ascent through `Level.activateTransition`, and asserts the hero's first action on the new floor is not swallowed by the act where the floor's mobs notice him. 12 cases, mutation-tested. | Depth changes in both directions, the episode continues, the engine clock restarts with the floor, and the agent's first action there moves the hero; **a floor the hero returns to is the floor he left** (same map hash, mobs and heaps, a dropped marker still there), not a regenerated one; returning resumes that floor's turn budget rather than refilling it; and the step that changes floor pays no stair shaping. |
| **`fallcheck` (`:superintelligence`)** | Drives a real chasm fall through `Level.occupyCell` - the transition that carries **no** `LevelTransition`, so `curTransition` is null on it by construction. 7 cases, each mutation-tested. | The fall lands the hero a floor down, alive, on a restarted clock, and the landing costs health the way `InterlevelScene.fall()` costs it; the hand-off leaves `InterlevelScene.mode` and `curTransition` clear, so the next episode's ordinary descent is a descent; walking toward a chasm asks the game's own question instead of throwing; the same fall twice in one process, with other runs in between, lands on the same cell of the same floor; and **a levitation that expires over a chasm drops the hero**, which `Levitation.detach` does not do headlessly; and a death on landing pays the death penalty in the step that caused it. |
| **`presscheck` (`:superintelligence`)** | A `Food` dropped on an armed trap through the real `Level.drop`, the same drop onto high grass, and the same drop with `Dungeon.level` nulled - the state `RegularLevel.createItems` places starting items in. 3 cases, in `gates`, mutation-tested. | A dropped item fires the trap it landed on and tramples the grass it landed on, and level generation presses nothing - which is the half of the guard that actually does that work. |
| **`dialogcheck` (`:superintelligence`)** | The `WindowBridge` surface: a dialog the game opened, and an answer the agent gave it. 2 cases, each mutation-tested. | The game's own "jump?" prompt is reached, aged, answered, and closes itself, and the agent ends up a floor down - the prompt is raised from inside `Hero.act`, so the same case proves a dialog opened *during* a turn is reported in that turn; and a dialog the game declines survives the answer and one it accepts closes. |
| **`mobspritecheck` (`:superintelligence`)** | Reads a real **Scroll of Mirror Image** through `SlotAction.use`, asserts the stand-in factory is unreachable with the headless flag off, and that a mob added with a delay (a Swarm clone) waits it headlessly as in the rendered game; five more give each of the 17 mobs that cast their sprite the class it is cast to and run the casts (Eye, Goo, DM-300, Ghoul). 10 cases, in `gates`, mutation-tested by reverting each half of the `:core` change. | Every image the scroll creates comes out with a sprite **and** in `Actor.chars`, not only in `level.mobs`; with `headlessGameplay` off, `GameScene.add( mob )` never consults the stand-in factory - which is what makes the `:core` change a no-op in a game with a renderer, and the counter is installed after the run is built so the case can fail; and **a ranged mob's zap completes**: a hunting `RedShaman` with a clear bolt line no longer ends the episode on the actor step limit. |
| **`resetcheck` (`:superintelligence`)** | Arms an aim request, opens a dialog and kills a hero - one case each - then asserts the next episode starts clean; proves four seeds reproduce identically after running the others in between; proves the special-room order is a function of the seed; and proves **a reset forgets which guidebook pages have been found**. 7 cases. | The armed aim, the parked dialog and a dead hero's remains do not carry; trajectories do not depend on order; the special-room pools do not either; and **floor 2 of a seed is the same dungeon after three guide pages have been collected**, fingerprinted by dimensions, terrain, custom walls, heaps, traps, mobs and plants. Removing the `Document.resetPages()` call fails the last with `heaps=17` against `heaps=16`. |
| **`paritycheck` (`:superintelligence`)** | Records a sweep of seeds x hero classes in one process, then verifies each recording twice - once immediately, once after the whole sweep has run in between. | Every recording replays exactly, both times. |
| **`playbackcheck` (`:desktop`)** | Drives `ReplayPlayer` logic headlessly without UI or display server. Covers the player's own logic only, and plays the committed corpus. | Faithful replays pass; altered steps (HP, position, turn, quickslots) fail at exact step; playing a death recording leaves no `bones.dat` behind; every committed recording plays **every step**; a recording whose bindings were never written is refused *at* the first step that needed them, not after playing; and a death landing on the **final** recorded step still plays that step, which the corpus could not reach until `duelist-death-last.replay` was added; **a recording the trainer ended on the soft stall guard with the hero alive plays every step and ends as the run rather than halting as a 400-turn viewer stall** — and that one is a property rather than a mutation test, because the fixture's hero is ready after its final step and the ordinary completion path runs; and **`Game.fixedFrameDelta` carries the frame-delta pin where `Game.update` reads it**, so the animation clock can be pinned at all — reverting `FrameDelta.apply()` to write nothing fails it naming `Game.fixedFrameDelta=0.0`. 25 cases. |
| **`viewcheck` (`:desktop`)** | Plays the **whole committed corpus through the real viewer** - real `GameScene`, real frame driver, real GL context, window hidden. Needs a display, so it is outside `gates`. **Two phases**: the corpus is played in parallel, as many children as the machine has logical processors, and **only what diverged is re-played on its own**. | Every committed recording plays **every step** in the viewer - not merely "did not diverge", which is what a halt for any other reason used to score. **Green**: 20 of 20, and stable across repeated runs. A recording that diverged under load and plays clean on its own is a load artefact, is reported as one, and does not fail the gate; one that diverges again on its own is a real fault and fails it, with a reproducible step. Measured: **46.4s at 20 children**, against 93.3s at 8 and 362.4s at one |
| **`observecheck` (`:superintelligence`)** | Verifies observation encoders (`ObservationEncoder`, `HeroEncoder`, `Quickslots`), **and** that every `Random` draw path is counted - so a count that agrees for the wrong reason cannot be read as parity - **and that every public static method on `Random` is in the audited set**, **and** (`ObserveLeakCheck`) that the observation shows nothing the game hides. 16 checks, counted as they run. The new one is the exit beacon: **an 8x8 window with the exit 42 tiles away must still carry a lit `EXIT_BEACON`**, because the beacon is the memory of where the stairs are and the encoder used to drop it the moment the stairs left the window - which `REJECTED.md` had recorded as done. The camera case: **the screen shake must spend no gameplay draw**, because only a rendered game has a camera to shake and how many draws it spends depends on the frame rate - measured on `ZRD-CVK-QYT`, whose divergence was at a different step in every one of five runs and moved to one step when the shake moved to `PRandom`. | **0 RNG draws** consumed during state encoding; byte-identical vector output; all draw paths counted; and a reflection audit of `Random.class.getDeclaredMethods()` naming anything the gate does not know about. **The audit found 13 on its first run** - `index(Collection)`, `element(Collection)`, two `element` array overloads, two `shuffle(Object[])` overloads, `Long(long)`, `Float(float)`, `chances(HashMap)`, `Int(boolean)`, `oneOf(Object[])` and `shuffle(int[])`. That is the whole argument for it: enumerating what the gate knows is an invariant about the present, and the four presentation draws that spent the gameplay stream while every count agreed got through exactly that hole. The ninth case is the one `issues.md` 26 closed on: **the headless stand-in for a particle emitter must spend no gameplay draw and exactly one presentation draw**, because the real `Emitter.start` draws its delay from `PRandom` - and a stand-in that compensated on the gameplay stream instead put the trainer one value ahead of the rendered game from the first scroll read, invisible to every comparison taken at a step boundary. Reinstating `Random.Float` fails 3 assertions naming it. The leak cases: unknown potions, scrolls and an unknown +3 cursed armour encode identically to their unknown peers; an unknown equipment level, shields included, moves no hero feature; unexplored cells carry no `WALL` or `OPAQUE`; and the hero sits at the centre of the window |
| **`renderdrawcheck` (`:superintelligence`)** | Reads the compiled bytecode of `core` and `SPD-classes` and lists every method of a presentation class (`Gizmo`, `Emitter.Factory`) or any `staffFx` that calls a gameplay `Random` draw outside a pushed generator. Only a rendered game runs those, so each one spends values the trainer never spends. 13 allow-listed with a reason; a stale allow-list entry fails too. | Found 26 sites on its first runs - `InterlevelScene.update` drew twice per frame from the base generator during a fall, `CavesLevel`/`HallsLevel` visuals per frame, every `staffFx` override, `CavesBossLevel.PylonEnergy` sparks, and seven sprites. Reinstating `Random.Float(false)` in `InterlevelScene` or `Random` in `WandOfWarding.staffFx` fails it naming the method. |
| **`travelcheck` (`:superintelligence`)** | `TRAVEL_STAIRS` and `TRAVEL_EXPLORE` step the hero one cell along a path over cells it has seen. 4 cases: stairs are not a target until seen, every explore step is accepted and lands on a seen cell, fight-else-travel descends on most of 6 seeds, and no travel path crosses the upstairs. | Measured 6 of 6, in 38 to 144 steps. Making every cell count as seen fails it naming the leaked exit cell. |
| **`teachercheck` (`:superintelligence`)** | `policy.DescentBot` labels each step for the imitation term. 3 cases: the bot alone descends at least one floor per seed on average over 6 seeds, one PPO update at zero advantage raises p(teacher) with `rl.teacher_weight` 1 more than 5x the drift with 0, and a recorded demonstration replays into one labelled, flagged transition per step that `rl.demo_weight` moves the policy toward. | Measured 9 floors over 6 seeds, deepest 4; p(teacher) 0.049 -> 0.082 against 0.049 -> 0.043. Deleting the imitation gradient in `PPO` fails it. |
| **`viewdiff` (`:superintelligence`)** | Diffs a headless `worldtrace` snapshot against a rendered `-Dspd.worldTrace` one, and reports frames that moved with no recorded step applied. | Names the first differing step **and field**, rather than the whole hero row under `pos`. |
| **`configcheck` (`:superintelligence`)** | Verifies the configuration binder, that `superintelligence.properties` agrees with the compiled defaults **in both directions**, and that `.env.example` names every one of them with the compiled default's value. 17 cases, 80 keys. | Every documented key changes the setting it names; a key with no compiled value is a **failure** rather than a skip; malformed and out-of-range values are refused naming the key and the file; `.env.example` must name all 80 keys as `SPD_` variables and every value in it must match the compiled default — which is how `SPD_RL_UPDATE_THREADS=4` against a compiled 1 and seven missing keys were found; and **all five of the trainer's own path flags resolve from the repository root**, not only the rollout command's. That last case exists because the rule had two implementations and the gate covered one of them, so `--out superintelligence/out/evidence/x` wrote to `superintelligence/superintelligence/out/evidence/x` while `configcheck` reported the rule as held; each path must now equal the exact resolved file. Every `EnvConfig` field is probed by reflection through the wire and `copy()`; a bad `.env` value is blamed on its `.env` entry; an unknown `SPD_` name in `.env` warns; a lone quote is a value, not a crash; and the gate reads `EnvConfigBinder.defaults()` with `SPD_*` stripped from its environment, so a developer's own `.env` cannot fail it. |
| **`rngtrace <file.replay>`** | Emits per-step draw counts and order-sensitive fingerprints (`baseFingerprint`). | Step-by-step fingerprint alignment between headless and rendered runs. |
| **`collectcheck` (`:superintelligence`)** | Records a sweep of seeds and hero classes in one process, verifies each recording twice - once immediately, once after the sweep - round-trips a populated replay through the worker wire (`train.ReplayCodec`), round-trips a whole **episode frame** through `train.Episode`, and asserts that a `DROP` in `SLOT` is recorded against the bindings its index was chosen against. 10 cases. | Every recording replays exactly, both times; all step fields survive the pipe **including the RNG draw count and fingerprint, on every step**; a rebinding step is recorded on the clock it was chosen on rather than the one it produced; and the frame a worker's episode summary travels in carries its **stall cause, objective score and every term total and count**, which is what the generation report is built from - a field that falls back to a default there reports a score the run did not earn; the critic's baseline is stored in return units (an episode collected under a value scale differs from the unscaled one by exactly the affine map); the replay keeper's seed cap keeps the best; and a replay that ends before its recording is a failure, not a match. |
| **`graphcheck` (`:superintelligence`)** | Whether a chart reports a range it did not observe, **and** whether the statistics a run is judged on are the statistics it claims. 9 cases. | A flat series is not widened, a varying one is not clipped, the progress bar reports what it measured - and the window mean, the **sample** standard deviation, the standard error and the two-window difference error are what they say on a deterministic fixture, and `depth>=2` counts **episodes** rather than generations; the progress bar's percentage is matched as a whole word; the end-reason trend compares the first third's reasons with the last third's; and **the entropy a run reports cannot exceed the logarithm of the largest masked head**, because a distribution over N outcomes cannot hold more than ln(N) of entropy, so a figure above 4.564 is impossible whatever the policy does. The reported figure used to be the entropy summed over a chunk and divided by the chunk count - 16 times the per-step mean, and it grew with `rl.chunk_length`, so the runs reported 27.5 to 30.6 for a 19-action head whose maximum is 2.94. A population standard deviation would report `s/sqrt(n-1)` and quietly make every error bar in this repository too narrow; counting generations is how `bestDepth` became a coin flip. |
| **`rolloutcheck` (`:superintelligence`)** | Drives the **`rollout` command itself** through `cli.RunCommand.execute`, not the collector underneath it, plus a random-seed episode recorded straight through `ScriptedEpisode`. 7 cases. | An episode asked for no seed records the one it resolved; what it records verifies; an explicit seed is recorded verbatim; **`--seed` with nothing after it is refused naming the flag** while an absent `--seed` is accepted, which is the pair that once made a random run unreachable; `--save` writes a file whose step count is the number the command printed and which reproduces; and `--max-turns` reaches the environment. **It was the one flaky gate in `gates`, at 1 failure in 8 runs, measured three times** - and it is no longer. The failing case was `checkRandomSeedRecordingVerifies` every time and the signature was always a negative delta at step 57 of 60, which is the direction a **per-process state leak** produces rather than the direction a flaky seed does. `issues.md` 38 found that leak - a guidebook page, read out of the process-wide `Document` by level generation - and after `Document.resetPages()` lands on every reset, **eight consecutive runs were green**. Eight runs bound the rate at about 1 in 3 rather than at zero; what closed the entry is the named cause and the measurement agreeing, not the sample size. **A green `gates` is still a claim about the run it just did**, and this entry is why that sentence is here rather than somewhere else. |
| **`slotcheck` (`:superintelligence`)** | A recorded slot index resolving to a different item, and the binding on an equipped item that no index can name. 7 cases. | Capture and restore reproduce slot resolution from a non-empty capture; a binding names the item's backpack position (`Class:slot@index`), so the second of two `Food` is restored to its own slot; a capture naming nothing clears a carried binding and leaves an equipped one alone. |
| **`pushcheck` (`:superintelligence`)** | The trainer's side of a worker pipe: `TrainerWorkers.writeFully`, the loop that writes a whole policy blob to a worker's stdin, and the stall watchdog. 7 cases. | No single write carries more than `train.push_chunk_bytes`; the bytes arrive identical; a payload of exactly one chunk is one write; an empty payload writes nothing; a failed write is **not** retried, because the buffered stream may already have flushed part of the chunk; and the failure is refused naming the worker, the byte count and the offset; the stall watchdog fires on silent workers but not during the trainer's own update. |
| **`javaclint` (`:superintelligence`)** | Compiles the module with `-Xlint:all -Werror` and fails on any diagnostic, **and** on any source a `stripinlines --javadoc` pass would change. | **Zero** warnings, zero mandatory notes and zero Javadoc blocks across the module's own sources. |
| **`verifycheck` (`:superintelligence`)** | Verification that cannot detect an altered recording, **and** verification that certifies one that cannot be played. 15 cases. | Position, health, engine time and inventory alterations are caught at the altered step; a recording carrying none of the five dropped step fields is refused; a hero carrying nothing bound is not refused; and **an altered RNG draw count is caught and the report names it** — the comparison runs before position, so a divergence says whether the two sides had already parted in the generator. **The one step the draw count is not held to is the step the hero dies on**, and that is a property rather than a gap: `Bones.leave` draws once per item the hero carried and refuses to at all for a custom seed, so a random-seed episode's recording and its replay legitimately spend a different number of values there. The case takes the corpus's death recording, alters the count on that step and requires verification to pass, and alters one on a live step and requires it to fail — `issues.md` 37, which was exactly this and read for eight days as a generator leak. Also: a replay that stops before its recording does is a divergence, not a clean prefix; the header's four limits round-trip; a step replayed in a different mode from the one recorded diverges at that step; `verify` never switches off an RNG trace its caller enabled; and an unknown hero class is refused at step 0 rather than played as a Warrior. |
| **`modecheck` & `restartcheck`** | Verifies action modes (`WORLD`, `SLOT`, `TARGETING`, `MENU`, `INVENTORY`) and floor re-initialisation. `restartcheck` runs the engine calls the viewer's restart makes, in order (`RunState.clearRemains`, `Dungeon.hero = null`, `InterlevelScene.descend`'s no-hero branch); it copies that sequence rather than calling `ReplayController.restart`, which lives in `:desktop` and needs a live scene. | Zero mode traps; floor 1 regenerates identically after 137 turns of scripted play ending in a death. |
| **`rewardcheck` (`:superintelligence`)** | Drives the real environment and asserts the reward **prices the objective**, as properties rather than as constants. 46 cases: since this release a death or stall forfeits the floor budget it did not live (an early death is not cheaper than a late one), and a priced ending is terminal to the learner. | Terminal outcomes are **ordered** (stall < death < turn limit < a cleared floor); stalling costs *more* than dying, and **more than dying late** - a stall is a death the run did not die of plus a floor spent without descending, so `reward.stall_penalty` must be at least `reward.death_penalty + reward.turn_limit_penalty` and reinstating 3000 fails the case; descending beats idling out the floor's turn budget; mapping 120 tiles pays less than a descent; a step that advanced no engine time costs more than a real one; **an action consuming two engine turns is charged for both**; a search is never cheaper than a single-turn action; killing and surprise-attacking both pay, the surprise more; a talent point pays more than a level; **a free-action loop is bounded** - `env.stall_limit` is cleared by one move, `env.free_step_budget` is not, and a control run with the budget out of reach must get well past it; **shaping towards the stairs is dense but not farmable** - the potential is zero on the **downstairs** (`REGULAR_EXIT`), a walk pays exactly the sum of `gamma*Phi(s') - Phi(s)` at `rl.gamma` to 1e-3, a `WAIT` included, and the distance field is built once per floor and terrain; **`reward.profile=depth_only` pays nothing but a descent and the four prices for stopping over an episode that provably fires 80 incidental events, while `default` still admits all 30 terms**; **removing a curse pays and wearing something too strong costs**, and a sub-mode action is shown always to either advance the clock or leave the mode, so `MENU_NOOP` has no reachable state yet; and **a heap lying on the hero's own cell is picked up by `INTERACT` and pays `ITEM_PICKUP`** - the cell the hero reaches by walking over a heap is the only way a dropped item ever becomes carried, and an agent whose own pickup is unreachable cannot learn to collect anything. Reinstating the adjacency test in `ActionMapper.handleCell` fails that case. Since 0.11.0 also: the step the hero dies on is terminal; the first step of an episode is scored; `BOSS_SLAIN` pays on leaving a boss floor, not on arriving; picking up the Amulet ends the run as `VICTORY`; `EATEN_EARLY` fires on a meal taken before hunger; a level-up's health is paid once; `IDENTIFIED` pays for identifying, not for carrying more; `USE`/`DROP` in `WORLD` are accepted; a talent point is compared against the configured level-up reward; and every `SEARCH` step is charged `turn_cost x max(1, engine turns)` to 1e-4; and **the terminal price reaches the policy and not only the score** - the last step the learner is given must carry the turn limit's or the stall's price, and the score and the learner's total must agree to within the turn the episode's settling cost. `SPDEnv`'s floor guards used to end the episode and drop `terminate()`'s value, so a stall reached the advantage as the price of a turn and a turn limit reached it as nothing at all, while dying cost 2500 and did reach it; reinstating the drop fails both cases, naming the price that went missing | Every term carries one of eight RewardTerm.Type groups, the type subtotals reconstruct the score the run actually earned, the event counts reconstruct the ledger's own, and RunReport prints a colour-coded 'type totals' block - green above zero, red below - between the floor table and the per-term table. Setting one term's type to null fails the first; dropping the block fails the second. |
| **`freeactioncheck` (`:superintelligence`)** | The arithmetic that ended 100% of episodes: a `REST` that spends an engine turn, and a menu that can be entered and left without the world moving. 8 cases. | `REST` advances the engine clock; opening a menu costs no turn; a zero-time exit masks the three sub-mode entries for exactly one step; and a `TALENT_UPGRADE` point is spent and pays, driven through `SPDEnv.step` rather than through the picker alone; and `WAIT` under `Slow` takes one turn, because `WAIT`/`REST` go through `Hero.rest` as the toolbar does. |
| **`bpttcheck` (`:superintelligence`)** | The recurrent gradient and the critic's targets. 5 cases; the two that compared a run with itself are gone, and the recurrent path is checked numerically by `gradcheck`'s 3-step chunk from a non-zero state. | A two-step chunk's LSTM gradient differs from the sum of its steps' isolated gradients; only contiguous steps are chained; value targets normalise a batch of -3000 and +1800 to at most 1.0. |
| **`curriculumcheck` (`:superintelligence`)** | Whether the shaping fade does anything. 5 cases. | 1.0 at or below depth 3, 0.0 at or beyond depth 10, strictly between in between, monotone over forty floors; a generation's `shaping=` is the mean of its episodes' own scales; and - through real `Level.activateTransition` descents - a run reaching **depth 6** carries **0.6064** in both `SPDEnv.curriculum()` and `RewardLedger`. Removing `curriculum.observe(depth)` fails 2 of the 5. |
| **`samplercheck` (`:superintelligence`)** | Whether the sampler feeds the recurrent chunk the update is built around, whether the grid reaches the dense trunk downsampled, **and** whether the per-generation cap resamples instead of keeping the tail. 8 cases. | At ``rl.sample_rate=0.05` over 20,000 steps the retained steps group into runs as long as ``rl.chunk_length` says with **no interior gaps**; the retained fraction converges on ``rate/(1+rate)` and never exceeds the rate; the tail is retained whatever the rate; a cap is honoured exactly; 0 and 1 are real settings; **the grid is reduced from 12,696 features to 3,200 before the trunk**; a collector built at chunk length 1 keeps only isolated steps while one built at 16 keeps whole blocks; and **1,000 episodes of 20 steps into a 125-chunk reservoir retain 125 chunks, 125 episodes represented, 14 of the last 100, every retained chunk contiguous** - the cap is Algorithm R at chunk granularity, so a late episode has the same chance as an early one. Dropping the oldest steps instead would represent none of the first ~880 episodes, and that is what the case refuses. |
| **`seedcheck` (`:superintelligence`)** | Whether a resumed run continues the seed schedule or replays generation 0, and whether `train.min_active_seeds` floors the schedule. 6 cases. | A draw is a function of its index (500 draws of head start produce the same 100 seeds as none); the same seed draws the same sequence and some of them random; two trainer seeds differ; a pool resumed after 120 draws continues at 121 and reproduces the uninterrupted sequence for the next 80; and a checkpoint's `Meta` carries pool seed, schedule seed, draws and pool size. |
| **`doccheck` (`:superintelligence`)** | Whether the documentation is readable as text, reachable as gates, counting what it says it counts, and naming files that exist. 4 cases. | **No markdown under the module carries a control byte** — a NUL makes `grep` read the file as binary, and the pre-commit hook's badge check then skips it and reports a mismatch it cannot fix; every gate in `gates` is reachable either through `Main`'s dispatch or a `mainClass` of its own, and every `cliTask` has a case in `Main`; and `README.md`'s stated gate count matches the `gates` task. The second case found `replayprobe`, a task that printed the usage and exited non-zero because `Main` had no case for it. The fourth resolves **every** Java path and fully-qualified class name in `research.md`, `docs.md`, `README.md`, `ENGINE-CHANGES.md` and `docs/documentation.md` against the four source roots, and found **26 that named a file which has moved or never existed** — `FogOfWar.java` and `ShadowCaster.java` moved to `tiles/` and `mechanics/`, and `SPDAction.java` was never a class in this repository at all. Changing `tiles/FogOfWar.java` back to `scenes/FogOfWar.java` fails it and names the path. |
| **Multi-JVM Byte-Diff Sweep** | Runs identical seed/hero rollouts across separate JVMs. | Generated `.replay` files are **byte-identical** across fresh JVMs. |

### The reward gate, and what "property rather than constant" means there

`rewardcheck` is the only gate whose subject is an objective rather than a mechanism, and that changes
what a case in it is allowed to assert. A gate that compares `stall == -100` is asserting that a number
has not moved; a gate that asserts `stall < death < turn_limit < depth_reward` is asserting that the
agent has no reason to pick any of the three cheap endings, and it survives every one of them being
retuned. Four of its thirteen cases are of the second kind, and one of them — the ordering — is the one
that would have caught every defect in `CHANGELOG.md`.

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

`CHANGELOG.md` is the fourth, and it has the sharpest version of the general form: **a gate that passes
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

`CHANGELOG.md` is the fourth counter-example and the sharpest one, because nothing here was a corpus
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

`presscheck` is the gate for `CHANGELOG.md` and is now **in `gates`** — it was left out only because it was
red, and a gate for an open defect is not a gate. Run it on its own with
`gradle :superintelligence:presscheck`. Its three cases are a dropped item firing a trap, the same drop
trampling high grass, and the same drop with `Dungeon.level` nulled, which is the state
`RegularLevel.createItems` places starting items in. The issue has the measurement and the reasoning, and
the two fixtures are below.

The fifth case is the question `CHANGELOG.md` asked, asked directly: the same seed and the same fall
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

### Which gate could not see a shared `Network`, and how it came to

`CHANGELOG.md`, and it is the seventh instance of the shape this file keeps recording.

`ShardedUpdate` built every shard from `ppo.network` itself, so all N update threads ran `oneSample` on
one `Network` - shared parameters, shared gradient accumulators, shared layer scratch, `slotLogits()`
among them. Every update thread's `clearGradients()` wiped the others' partial sums, and the reduction
then added the master's own gradient once per shard. **Every training run in this project's history used
more than one update thread.**

`parallelcheck` passed throughout, because all four of its cases build their own `Network[]` and call
`oneSample` on each in a serial loop. It proves the *reduction* - N independent networks accumulate the
same gradient as one - and it never asks `ShardedUpdate` where its shards' networks come from. The
requirement it was supposed to cover was recorded in the data-flow plan as **"Parallel update
silently races on shared state | high ... Needs its own equivalence check"**: the right requirement,
pointed at a gate that could not carry it.

Found by a training run reporting a **negative entropy**, which no distribution over `[0, 1]` can
produce. The chain: a tripwire printed the buffer (`out[0]=1.128`, every other entry 0); a stage trace
showed the corruption present immediately after `Policy.probabilities` and unchanged by the two later
calls; instrumenting `probabilities` showed the mask stable and the one legal logit reading 0.2495 before
`Arrays.fill` and 0.6160 after; an identity probe found `net=756441311` on every pool call.

| reverted | what fails |
| --- | --- |
| `Shard` given `ppo.network` again | `parallelcheck 1 of 5 fails` - `4 shards hold 1 distinct networks` (0.10.0; the gate is 4 cases since 0.11.0) |

**The entropy assertion was tried and measured to be unable to fail, so it is reported rather than
asserted.** With the fault reinstated and only the identity assertions silenced, 64 samples reported
**1.4649** and the check passed: a race does not reliably corrupt anything in one minibatch-sized update.
Over a real run it corrupted roughly one sample in ten thousand - which is why it took 77 generations to
show, and why asserting it would have been a check that cannot fail. `parallelcheck` was **5 cases**; the
new one asserts shard identity through the real `ShardedUpdate.run`, reached by
`PPO.updateShardNetworks()`. Since 0.11.0 it is **4**: the serial-loop cases are replaced by a real `PPO.update` at 1 thread against 2 and 4 (parameters and every reported metric must agree), shard identity, and a shard that throws fails the update instead of hanging it.

Measured A/B, same machine same minute, 6 workers x 3 episodes, 6 update threads, seed 777, 6
generations each: turns/s **804 -> 908**, ppo update **15.5s -> 14.0s**, worst `kl` **0.0474 -> 0.0271**,
valueLoss last **2,378 -> 409**. It is *faster*, because six threads writing 3.5M parameters into one
object were contending for the same cache lines. The cost is 6 x 14.3 MB of trainer heap, which the
generation report's `buffer ... MB resident` line does not include.

### Which gate refuses a fault in the trainer's half of a fix

`CHANGELOG.md` is the clearest instance in the suite of a fix that changes **only one** of the two
environments, and the mutation test says something the test plan did not predict.

`Level.drop` presses the cell a thrown item lands on. It did so only when the current scene is a
`GameScene`, which is false in a headless process and true in the viewer — so a thrown item landed inert
in the trainer and correctly in the viewer. The fix adds one term that only `HeadlessGame.install()`
sets. **Reverting it leaves the viewer untouched by construction**, and the gates say so:

| Gate | After reverting the fix |
| --- | --- |
| `presscheck` | **red**, 2 of 3 cases |
| `viewcheck` (real viewer, 23 recordings) | **green** — correctly, and not a hole |
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

### Why the policy push was ungated for seven months, and what ungates it

`CHANGELOG.md` recorded a run dying at generation ~22 with

```
java.io.IOException: Insufficient system resources exist to complete the requested service
	at ...TrainerWorkers.lambda$pushWeights$0(TrainerWorkers.java:258)
```

and the fix — a chunked, retried write — shipped with no gate over it. The reason was stated and it is
real: **a gate cannot spawn a worker.** `Worker` calls `HeadlessServices.install`, which is a
process-wide install, so a child JVM launched from a gate's own JVM does not come up cleanly and the
case could not be written as it stood.

That reasoning is right about the *fault* and wrong about the *contract*. What killed the run was not a
worker being spawned; it was one call to `write` carrying 14.3 MB, and that is a property of a method
whose entire body is a loop. A gate does not need a pipe to check a loop.

`pushcheck` drives `writeFully` against a recording `OutputStream` that counts what it was asked to
write, throws on the calls it is told to fail, and keeps everything that got through. The six cases
assert the contract rather than the symptom:

| case | what it holds |
| --- | --- |
| no write exceeds the chunk | the size that killed a run is the size the loop refuses to produce |
| the bytes arrive intact | every chunk lands at its own offset, in order |
| a payload of one chunk is one write | chunking did not become two writes per chunk |
| an empty payload writes nothing | the loop has a guard rather than relying on the caller's payload |
| a failure is not retried | the buffered stream may already have flushed part of the chunk, so a retry can send bytes twice |
| a failure is refused by name | the error names the worker, the byte count and the offset |

Each was mutation-tested by reverting one half of the fix:

| reverted | what fails |
| --- | --- |
| the chunk bound | `policy push: 1 of 6 checks failed` — `one write carried 98304 bytes against a chunk size of 32768` |
| the fail-fast write (0.11.0, retrying loop restored) | `policy push: 1 of 6 checks failed` — success after 3 attempts |

The retry is gone in 0.11.0, and `trainer.push_retries` with it. `writeFully` writes through a
`DataOutputStream` over a `BufferedOutputStream`, which may already have flushed part of a failed chunk
to the pipe, so a retry could send those bytes twice and desynchronise the worker protocol. A push now
fails on its first write error.

The general form is the one this file has now stated seven times, and this instance is the one about the
*harness* rather than the game: **a gate that cannot reach a fault is not always a gate that cannot
exist.** It is usually a gate that was written against the symptom rather than the contract.

