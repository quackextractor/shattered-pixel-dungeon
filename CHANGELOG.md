# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **`OPEN_INVENTORY` also acted on the world.** `OPEN_INVENTORY` and `CANCEL` were missing from
  `ActionMapper.apply`'s switch, so both fell through to `pickInteractCell()` + `handleCell()` - the
  path `INTERACT` takes. Opening the inventory therefore also moved the hero, attacked an adjacent mob,
  picked up a heap, opened a locked door or took a floor transition.
  It went unnoticed because a comment called the fall-through a no-op. It was not: `pickInteractCell`
  finds something and `handleCell` acts on it. Visible in recorded runs, where the hero moved a cell
  on an `OPEN_INVENTORY` step and moved back on the next.
  **This reached training** - every episode that opened its inventory did two things at once, and the
  agent was credited for both. It also explains why the desktop viewer and the headless trainer
  disagreed about the same recording.
  `modecheck` now covers it, built so it can actually fail: it drops an item heap on a known adjacent
  cell first, because on a quiet seed `pickInteractCell` has nothing to return and the bug hides. The
  first version of that case passed with the bug reinstated.

- **The replay viewer could not find most recordings.** The trainer writes into `<--out>/replays` and
  `--out` is wherever the run was pointed, while the viewer and the catalog searched exactly two fixed
  directories. Measured: **15 of 129 recordings were reachable**; the rest were written, ranked, closed
  and never found again, with no error anywhere.
  Runs now record where they put their replays, and discovery reads that index - 15 to 129 recordings
  here. An index rather than a filesystem search, because scanning for directories named `replays` finds
  these by luck of naming and also picks up unrelated ones.
  Two things came out of fixing it that were wrong in their own right:
  - **A seed recorded by several runs resolved silently to whichever came first.** `TLH-MLA-DYU` exists
    5 times on this machine, as a 139-step recording in three directories and a 1506- and a 1511-step
    one in others. Typing the name returned one of them with no indication there was a choice - so
    someone investigating a specific run would be handed a different run. A name matching more than one
    file is now refused with every copy listed, paths, step counts and scores included. A *filename* is
    not unambiguous either, since two runs writing one seed produce the same filename; a full path is
    the way out, and `--resolve` accepts one.
  - `replay-viewer.bat` no longer probes two hardcoded directories with four extension permutations.
    It asks the Java side, as `:catalog` and `:bynumber` already did.

- **A divergence report said only what was expected, never what happened.** The viewer's message was
  `expected hero at 834`, which does not distinguish a stale recording from a broken viewer. It now
  names the action, the mode, both positions, and what the recording says:
  `DIVERGED at step 42 - OPEN_INVENTORY/0 in WORLD: hero at 834, recording says 869`.

- **The trend chart reported a range it never observed, and a depth-2 episode was reported that never
  happened.** `Graph.bar` widened the scale by 1 whenever min equalled max, purely so the division
  inside the loop was defined - and then printed that widened span as the axis range. A run whose
  `bestDepth` was `1` in every generation printed `depth 1.0..2.0`, which reads as "reached depth 2".
  It was believed: the claim reached `README.md`, `PLAN-reward-signals.md` and the changelog. Every
  underlying measurement said otherwise - all 147 replay files and every generation of every metrics
  CSV recorded depth 1 - and because nothing disagreed with the chart, nothing caught it.
  The rendering span is now internal and the label reports only observed bounds; a flat series labels
  itself `1.0 (flat)` rather than printing a range that suggests it moved. A flat series also renders
  mid-ramp instead of blank, so "never moved" no longer looks like "never measured".
  New `graphcheck` gate, 4 cases, mutation-verified: restoring the original widening fails all 4, with
  the exact `1.0..2.0` string as the first failure.

- **A reset was not isolated from the previous episode, so a second recording in one process was
  unreproducible.** `GameScene.pendingCellListener` is static and belongs to the process, not to a run.
  `SPDEnv.step` cleared it on entry, so it was tidy between steps; `reset` did not, so an item left
  mid-aim by the previous episode was still armed when the new episode's first `settle()` checked it.
  `settle` checks the listener *before* running any turn, so the episode opened in `TARGETING` and the
  agent's first action was read as picking a target instead of being applied. The hero never moved, and
  the recording diverged at step 0.
  Measured: **4 of 10 recorded runs diverged when verified in sequence**, while every one of them
  verified clean on its own; one file passed first and diverged when reached second. That is the replay
  viewer's complaint exactly - it plays files in order, so everything after the first was unreliable.
  Invisible to every existing check, because all of them verify one recording in one process, and a
  single episode has nothing to leak from. `restartcheck` came closest and compared the level but not
  the mode, which is all this touched.
  `reset` now clears it. New `resetcheck` gate, 3 cases, mutation-verified: disabling the fix fails 2
  of the 3.

  Note for anyone reading the check: the assertion is on the *mode*, not on the listener being null.
  `Hero.ready()` calls `GameScene.ready()` -> `selectCell(defaultCellListener)`, so the listener is
  armed again on every turn where the hero awaits input. That is the normal state, not leakage.

- **Every death blew the stack, so `DEATH` was unreachable.** `HeadlessSprite.die` called
  `ch.die( ch )` to invoke the death callback immediately, standing in for an animation that does not
  exist headless. That callback re-enters `Hero.die` -> `Char.die` -> `sprite.die` -> the callback, and
  each bounce passes a different `Char`, so `Hero.die`'s repeated-cause guard never matched and the
  recursion ran until the stack gave out.
  So the ending that matters most was the one the agent could never reach: `StackOverflowError` is not a
  `DEATH`, so no terminal reward was recorded and GAE had nothing terminal to treat as terminal. The
  first episode to actually die killed its worker process.
  Now a no-op - the callback represents the animation finishing, and there is no animation.
  `rewardcheck` grew an 8th case that produces a real death and asserts the reason, the natural-ending
  classification, and that a `DEATH` was recorded. Note this case cannot fail the usual way: with the
  bug reinstated the check process dies with `StackOverflowError` before any assertion runs, which is
  itself the evidence.

- **Headless crashed on any texture that had no GL context.** `TextureCache.get` returns `null`
  when `Gdx.gl == null` - the normal state under the headless trainer - and every `TextureFilm`
  constructor dereferenced the result immediately. Films are built in static initialisers
  (`FloatingText.iconFilm` among them), so the crash fired on first touch of the class, not on
  first render. `Char.damage` reads `PHYS_DMG` off `FloatingText`, so *hunger damage* reached it:
  every worker process died the moment an episode lasted long enough to starve.
  Constructors now fall back to the frame size, which keeps the atlas math well-defined (one
  row, one column) instead of dividing by zero. Nothing renders headless, so the dimensions only
  had to be safe rather than correct.
  Previously unreachable, because episodes ended long before the hero could starve - the `REST`
  fix below is what exposed it. A plain `SEARCH` crashed on this too.

- **`REST` ended every episode that used it.** `ActionMapper` set `hero.resting = true` without
  setting a `curAction`. `Hero.act()` branches on `curAction == null` first, so a resting hero took
  the rest branch - `spendConstant` then `next()` - and never called `ready()`. Control was never
  handed back, and nothing rescued it: `recoverStrandedHero` deliberately refuses a resting hero,
  which is right for a player, because a player escapes rest by choosing another action. Headless
  input has nobody to do that. After 8 scheduler steps `LevelPipeline` returned `STALLED` and the
  episode was over. Any non-REST action now ends the rest, as `Hero.act()` does at the top of its
  `curAction` branch.
  This was the actual cause of the 100% stall rate, and it was **not** the `WAIT` story the previous
  entry describes. Instrumenting the two stall guards showed `STALLED` arriving 36 times from
  `LevelPipeline` and **zero** times from the idle guard; the stall was happening at turns 10-51,
  where a 120-turn idle guard cannot reach. `rewardcheck` grew a 7th case for it.
  Note the fix is load-bearing only for the actions that set no `curAction` (`WAIT`, `SEARCH`,
  `USE`, `DROP`) - movement escapes rest on its own, because it sets one.

- **`clip=` reported a constant, not PPO's clip fraction.** `PPO.update` counted
  `|advantage| > clipEpsilon` on a *normalised* advantage. After normalisation advantages have unit
  variance, so that expression is `P(|N(0,1)| > 0.2)` = 0.8415 regardless of the policy — which is
  exactly what real runs printed (0.81, 0.83, 0.85, 0.90). `Policy.accumulatePolicyGradient` already
  computed `clipBinding` and discarded it; it now returns it, and `PPO` counts the real thing.
  Measured over 9 generations the corrected figure ranges 0.047–0.737 and tracks the update, which is
  the point: it is the one signal that says the policy is moving too far per update, and it could not
  be read before. Covered by a new `gaecheck` case, mutation-tested.

### Added

- **Parallel minibatch update** (`--update-threads N`, default 1). Each minibatch is split across N
  threads, each with its own forward/backward scratch and gradient accumulators, reduced into the
  master before the Adam step. A thread's network is a *copy* of the parameters rather than a view onto
  them, so no thread can observe another's writes even if the reduction is wrong.
  Measured, 8 workers × 3 generations: **8.98 → 5.09 → 2.73 → 2.59 ms/sample at 1, 2, 4 and 8
  threads** (1.00×, 1.76×, 3.29×, 3.47×). The plan predicted ~5× at 8; the shortfall is the gradient
  reduction, which is why 4 and 8 threads are nearly identical.
- **`rewardcheck`**, a gate asserting that ending an episode is never cheaper than dying — replayed
  through the real environment rather than compared against a literal, so it holds however the
  constants are retuned. 6 cases. Mutation-tested: restoring `STALLED` to -5.0 fails the
  terminal-reward case; restoring `WAIT`'s `return false` fails the refusal case with "435 refusals over
  29 WORLD turns".
- **Termination reasons reported per generation.** A console `ended` line, one CSV column per reason,
  and an end-of-run first-third-vs-last-third trend. Written because a 20-generation run converged
  `meanScore` on exactly -5.0 and the reason had to be *inferred* by arithmetic; it is now `ended
  stalled 16 (100%)`, read.
- **`parallelcheck`**, a gate asserting that a sharded accumulation equals a single-network one, at 2, 4
  and 8 shards, and that the answer does not depend on the shard count. Tolerance is 1e-4 relative to
  each tensor's own L2 norm; observed disagreement ~2e-7. Mutation-tested — reverting the `dCell` clear
  fails all four cases.
- **The LSTM state now travels with each sampled transition**, so the update replays an observation
  under the state the behaviour policy actually used. 1 KB per step at the default 128-wide LSTM,
  about 2% of a step's wire cost.
- **`statecheck`**, a gate asserting a sampled observation replays to the value the rollout recorded
  *regardless of processing order* — and, separately, that replay is order-independent, which is the
  precondition for splitting a minibatch across threads. Mutation-tested: removing the restore fails
  two of its four cases.
- **`replayprobe`** and **`valuescale`**, probes rather than gates. Both exist because a number was
  needed to settle a question that reading the code could not: how far a shuffled replay drifts, and
  whether the critic's targets fit inside the range it can reach.

### Fixed

- **The reward paid the agent to give up on an episode.** `STALLED` carried a terminal reward of
  `-depthReward * 0.5` = -5.0, against `deathPenalty` of -100 and a `TURN_LIMIT` cost of -80 — so
  ending an episode was **twenty times cheaper than dying**, and the cheapest action in the game was a
  direct route to it: `WAIT` costs a turn, moves nothing and changes no HP, which is exactly what the
  stall guard tests. A 20-generation run converged `meanScore` on exactly -5.0 with `meanTurns`
  collapsing 62 → 8, never leaving floor 1, while every loss metric looked healthy. `STALLED` is now
  **zero** — idling is priced by `TURN_COST`, which is what turn cost is for — and remains a
  truncation, so GAE bootstraps through it. Measured over the same 20 generations: `meanScore` -4.46 →
  **+3.29**, `meanTurns` 11 → **56**, `valueLoss` 3.53 → **0.44**.
- **`WAIT`, `REST` and `SEARCH` were reported as `INVALID_ACTION`.** `ActionMapper.apply` is documented
  as "needs a follow-up choice" and returned `false` for three legal actions the environment performs;
  `SPDEnv.step` read that as a refusal. It now means "was the action accepted", which is what the one
  caller reads it for.
- **`INVALID_ACTION` was unobservable.** Recorded with `note(term, 0)`, so it moved no total and appeared
  in no report. Now recorded through `noteEvent`, which counts without scoring.
- **`SPDEnv.terminate` was not idempotent, and one caller relied on that.** `settle`'s `actorStepLimit`
  guard called `terminate(STALLED)` twice, charging -10.0 where the term said -5.0. The duplicate is
  removed *and* the method is now idempotent, so the whole class of double-termination is closed. It
  also prevents a second call from overwriting `endReason` with something less specific.
- **Gradients leaked between samples.** `Network.backward` cleared `dPrev` but not `dCell`, and
  `LSTM.backward` reads the incoming `dCPrev` before overwriting it. Every sample's gradient was
  therefore a function of whichever sample was processed before it. Measured on `conv.gb[15]`: four
  samples in sequence gave 0.0892, 0.2075, 0.3372, 0.4235; the same four individually gave 0.0892,
  0.0419, −0.1487, 0.0517. Not additive — which makes a correct parallel reduction indistinguishable from
  a broken one. This was masked until now because the buffer is shuffled, so the leaked gradient arrived
  attached to an unrelated sample.
- **The update was optimising a different objective than PPO.** `PPO.update` shuffles the buffer and
  then calls `network.forward` once per sample, so each sample's replay inherited the hidden state the
  *previously processed* sample left behind — a different timestep of a different episode. Measured:
  replaying in collection order reproduces the rollout's value to 0.000000; replaying shuffled drifts
  up to 0.20, about 20% of the value's magnitude. The ratio `pi_new / pi_old` was therefore formed
  across two different states. It is also why the clip fraction sat at 0.84 on the first update, which
  was indistinguishable from `P(|N(0,1)| > 0.2)` and so read as a plausible measurement.
- **`Network.state()` returned the hidden state only.** The LSTM cell state is not derivable from it —
  the forget gate's accumulated memory lives only in `c` — so restoring `h` alone gave the network a
  state it had never been in. Finite, plausible, wrong. `LSTM.snapshot`/`restore` now carry `[h | c]`.
- **The critic could not represent its own targets.** `valueHead` was a `Dense`, so tanh, so confined
  to `[-1, +1]`, against `deathPenalty` 100 and `victoryReward` 1000. The return is unnormalised (only
  the advantage is normalised), so the critic was asked to fit targets up to 250× outside the range it
  could express, and the squared error from those samples floors out rather than being trained away.
  `valueLoss` sat at 2.97–8.11 over 12 generations and never trended down; it now sits at 2.44–3.51
  over the same span. The value head is linear, and its backward pass no longer multiplies the
  gradient by `1-tanh'`, which shrinks as the error grows — the opposite of what a regressor needs.
- **Samples were coupled**, sample *i* depending on sample *i-1*. This is why the parallel update was
  blocked, and it was a correctness bug rather than a threading one: the parallelisation would have
  fixed it, had anyone attempted it.
- **`Protocol.VERSION` is still 1** despite the params and transition frames both having changed
  incompatibly since. Bumped to 2, so the handshake check that exists to catch a desync now
  distinguishes them.

### Added (earlier in this release)

- **Policy checkpoints, so a training run can be stopped and continued.** `--save <file>` writes a
  checkpoint every `--checkpoint-every` generations (default 25) and on exit; `--resume <file>`
  continues from one, keeping the generation and optimiser-step numbering so the two runs'
  `metrics.csv` rows do not collide. Writes go to a sibling temp file and are renamed, so a power cut
  cannot leave a half-written checkpoint that is newer than the last good one.

  **The format carries more than weights, and that is the substance of it.** `Network.layers()` does
  not include Adam's moments, and a checkpoint without them restarts the optimiser's averages from
  zero — the run still trains, and trains worse, with nothing in the metrics to say why. Neither does
  it include the optimiser step count, and Adam's bias correction divides by `1 - beta^step`, so
  restarting at 1 makes that correction ~0.1 instead of ~1. Both are now saved. The moments are not
  sent to workers: a worker runs forward passes only, so pushing four times the floats per generation
  would cost ~57 MB per worker per push for nothing.

  Also **refuses four kinds of bad file** rather than misreading them: foreign (bad magic),
  truncated, one with trailing bytes, and one from a different `EnvConfig` — the last naming the
  offending field, since "trained with gridWidth=32, this run has 48" says which flag to change.
- **`checkpointcheck`**, a gate that asserts a policy survives disk bit-for-bit including its moments,
  and that each of those four refusals happens. Mutation-tested: dropping the moments, the
  trailing-byte check, or the config check each fails it.
- **`weightsdiff <file>`**, reporting how far a checkpoint is from a freshly initialised policy. The
  complement to `checkpointcheck`, which proves the bytes arrive but not that the resumed network is
  a *trained* one — a checkpoint written from the wrong tensor passes every equality assertion and
  then trains a random policy with entirely normal-looking metrics.
- **Per-generation metrics history** (`MetricsHistory`), written to `metrics.csv` in the work
  directory by default and overridable with `--metrics`. `Trainer` previously kept one generation of
  episodes and discarded it, so a run's history existed only in console scrollback. Rows are flushed
  per generation and appended rather than rewritten, so a crashed or extended run keeps what it
  reached. At the end of a run it renders score, depth and both losses as bars — reviving `diag.Graph`,
  which was written and never used — plus a 7-generation moving average of score, since per-generation
  score is noisy enough to invite reading a trend into noise.
- **`--sample-rate`, `--max-sampled-per-episode`, `--max-samples` and `--metrics`** on the trainer. The
  first three existed as `Trainer` public fields with the documented defaults but were never reachable
  from the command line, so the sampling knobs the plan documents could not actually be turned.

### Changed

- **`STALLED` is a truncation and only `DEATH`/`VICTORY` are natural endings**, now named as
  `SPDEnv.isNaturalEnding` with `isTruncation` as its complement, so the classification can be asserted
  against the real predicate rather than restated. A stalled episode used to be penalised *and*
  bootstrapped — one signal said the episode was over, the other that it carried on, which is why
  `valueLoss` sat at 3.0–8.1 without trending. `gaecheck` gained a case covering the advantage half;
  setting `nonTerminal = 1f` unconditionally fails 5 of its 12.
- **`PLAN-data-flow.md` corrected against the code** rather than against its own prose: removed
  `rolloutCap`/`PPO.collect` references to deleted code, restored a missing `### Step 3` heading whose
  body had been orphaned under step 2b, marked steps 0 and 3 done, and corrected every cost figure
  that the `epochs` default change from 4 to 2 had doubled. Two claims were withdrawn rather than
  fixed: "re-simulation costs ~2.3 ms a turn" was 17× wrong (measured 0.136 ms/turn), and "collection
  throughput roughly doubles" had never been measured.
- **Ordering corrected: instrumentation now precedes the parallel update.** Steps 0–3 built a loop that
  learns and no way to tell whether it does. The parallel update is still required — the measurement
  does not care what else is on the list — but its only success criterion was "faster", so doing it
  first would optimise a loop whose behaviour cannot be observed. Checkpointing also moves ahead of
  it: it is smaller, it is on the critical path to the Goo milestone, and it is what makes two runs
  comparable at all.
- **The reward curriculum is recorded as deliberately inert.** `Curriculum` is written and
  `SPDEnv` applies it, but nothing calls `observe()`, so shaping is a constant 1f. Wiring it is three
  lines and was *not* done: it would start fading dense rewards by depth 3, changing the reward
  function, with no evidence those terms help. Deferred until a run can be judged.


- **Every episode ended `STALLED` after 8-13 turns,** which made the environment useless for
  collecting experience. `Hero.act()` was being entered with a null `curAction`: it clears `ready` and
  then dispatches on `curAction`, so no branch matches, and `Hero.ready()` is the only thing in the
  game that sets `ready` back to true. A normal playthrough never reaches that state because the cell
  selector re-prompts for input, and that prompt is what calls `ready()`; headless there is no scene
  and no selector, so nothing was left to re-prompt. Five seeds that all stalled now run the full
  turn budget.
- **`MENU` mode was unreachable in a real run,** not merely hard to record. `step()` cleared the
  pending dialog on every turn, so the step that could have answered it arrived to find it gone and
  the rollout sat selecting at nothing until it stalled. Clearing now happens on `reset()`, where
  per-run state belongs.
- **The `MENU` branch never returned to `WORLD`,** so a dialog could be answered successfully and the
  mode still never changed hands again.
- **`WindowBridge.open()` meant "a window is showing" rather than "a dialog the agent can answer."**
  `GameScene.show` parks informational windows there too, and "you cannot leave the dungeon yet"
  arrives that way constantly, so each one put the environment into `MENU` with nothing to select.
- **`SLOT` had no entry in the action mask** and fell through to the `WORLD` mask, which offered
  movement and `WAIT` while the environment was waiting for an item choice. `SlotAction.execute`
  treats every action that is neither `CANCEL` nor `DROP` as a use, so a legal-looking move there spent
  the item.
- **`EnvMode.INVENTORY` was unreachable.** The mode was documented on `Action.OPEN_INVENTORY` but
  never assigned, and `OPEN_INVENTORY` fell through to the generic cell handling as a no-op, so no
  rollout could ever contain the mode and no replay could exercise it. It is now entered from the
  `WORLD` step and offered in the `WORLD` action mask, and its branch resolves an aim the same way
  `SLOT` does instead of dropping it.
- **Throwing stones were equipped rather than thrown.** `SlotAction.use` tested equipability before
  the item's own action, and `Weapon extends EquipableItem`, so a missile weapon took the equip path.
- **Every equipment change invented a targeting step.** `SlotAction.use` returned `toggleEquip`'s
  "equipped OK" boolean, which the environment read as "needs an aim", and its success path returned
  `true` outright, so plain consumables falsely demanded an aim too.
- **A refused equip stalled the episode.** `toggleEquip` returned without releasing the hero, which
  left it permanently mid-action; the pipeline never saw it become ready and the run ended `STALLED`
  within about a dozen turns.
- **No aim could ever be resolved or cancelled.** `SPDEnv.step` cleared the pending use item on every
  step, including the `TARGETING` step that needed it. `CANCEL` during targeting was also treated as a
  throw at the default target, spending the item. `CANCEL` is now handled on its own and releases the
  hero's turn.
- **The replay viewer could leave the game completely unresponsive.** Reaching a transition the hero
  cannot use raises an informational `WndMessage` - "you cannot leave the dungeon yet" - and windows
  are modal, so the scene stopped accepting input while playback carried on, driven by the frame hook
  rather than by keys. The viewer now dismisses windows a recording has no step to answer, and leaves
  `WndOptions` alone because a recorded `MENU` step does resolve that one.
- **`lockCellInput` did not lock the keyboard.** It disabled the cell selector's pointer path, but the
  selector's own `KeyEvent` listener ignored `enabled` and kept turning arrow keys into movement, so a
  viewer could still have its hero walked away and desync the recording.
- **Viewer keys could not override a game binding.** `KeyBindings.getActionForKey` consults
  `hardBindings` last, so forcing `SPACE` to nothing lost to the default `SPACE`→`WAIT` and a press
  both paused and waited the hero. `KeyBindings` gained an explicit override layer for this instead of
  reordering the existing lookup, which would have broken players who rebound `ENTER` or `ALT_RIGHT`.
- **`R` did not restart.** It rewound the cursor while leaving the live game where it was; it now
  rebuilds the level through the same path the viewer starts on.
- **Viewer controls died after `R`.** `InterlevelScene` calls `KeyEvent.clearListeners()` to drop its
  own continue-button listener, which clears every listener including the viewer's. The viewer
  re-registers on each scene rebuild.
- **The replay HUD vanished after `R`,** because it was rebuilt against the outgoing scene and the
  non-null field then prevented a rebuild. It is now rebuilt whenever the live scene changes.
- **Resuming a diverged replay re-reported the same divergence forever.** Divergence is now sticky,
  since a diverged game can never rejoin the recording's path.
- **On-screen replay divergence could never fire.** `ReplayPlayer` advanced the playback cursor before
  comparing the landing cell, and advancing clears the expected position, so the check always passed.
- **`GameScene` gained `topWindow()`.** `showingWindow()` only reports whether a window exists, which
  is not enough to decide what to do about one.
- **The libGDX natives were never loaded in any headless JVM,** so the first game code that allocated
  a `Pixmap` died with `UnsatisfiedLinkError`. Desktop gets the library implicitly from
  `Lwjgl3NativesLoader`; the headless backend replaces that, and a natives jar sitting on the classpath
  does not load itself. `TextureCache.getBitmap` is guarded on `Gdx.gl == null` and returns null,
  which is why the item-icon film decoded fine, but its three programmatic constructors —
  `createSolid`, `createGradient` and `create` — are not. Those are reached from a `Flare` at roughly
  forty item and buff sites, and a `ColorBlock` from every inventory slot.
  `GdxNativesLoader.load()` now runs in `HeadlessServices.install()`, the earliest point every entry
  path already passes through.
- **`BitmapText` threw on every measuring call with no font.** Its no-argument constructor already
  built one with a null font, and nothing had ever constructed one without immediately giving it a
  font — until the headless platform, which has no font generator to give. `measure`, `baseLine` and
  `updateVertices` now treat a null font as zero-sized text. Reached from `Bag.execute`, which opens a
  quick-bag window whose item slots lay out text.
- **Both of the above were live crashes on item use, not latent ones.** They only appeared once
  workers stopped playing the scripted heuristic — the class of bug TODO.md §1 predicted, where a
  policy sampling the action mask reaches code a hand-written if/else never did.
- **Global gradient clipping did not exist.** `Network.gradClip` was declared with the comment
  "applied by the caller before `step()`" and no caller applied it; `PPO.update` never computed a
  gradient norm. Harmless while the buffer is empty, which is why it survived. It is implemented now,
  over every layer's gradient accumulator at once, accumulated in double because a network this size
  has millions of entries spanning many orders of magnitude.
- **A recording of a random-seed episode could never be replayed or verified.** `SeedPool` deliberately
  leaks 10% of episodes onto fully random seeds so the agent cannot memorise the locked set, and those
  episodes were recorded with the *requested* seed — which is empty for them. `verify` resets onto a
  fresh draw and reports a divergence at step 0: correct behaviour, and a message that reads as a broken
  seed lock. `SPDEnv.reset` now resolves the drawn seed and reports it, and the recorder begins from
  that rather than from the request, so every recording names the run it actually produced. Measured:
  a 3-generation run wrote 5 recordings and all 5 re-execute exactly, including one that was previously
  written as `random.dat` and always failed.
- **The reported losses were running sums, not means.** Each minibatch averaged over its own samples
  and the totals were summed across every minibatch and epoch, so `policy=` and `value=` grew with
  update length rather than measuring anything. They are now means over the samples seen.
- **Clipping now happens after the minibatch average.** Clipping the raw accumulated gradient would
  have made the ceiling mean something that changes with `minibatchSize`, and `gradClip` is declared
  as an absolute norm. It also means the reported norm is the norm of the gradient that was applied.
- **`gradle :superintelligence:train` rejected its own arguments.** The subcommand was prepended in
  `doFirst` for every CLI task except `train`, so the documented
  `--args="--workers 8 --generations 200"` reached `Main` with no subcommand and died on
  "unknown command: --workers". The trainer had never been launched through Gradle.
- **A worker that acknowledged a policy push with the wrong message went unnoticed.** The handshake
  read the reply and discarded it.
- **A worker that replied to an episode request with the wrong message went unnoticed** for the same
  reason, and on that path the diagnostic read a second int off the wire to name it, so the error
  message itself consumed part of the frame it was describing.

- **`gradle :superintelligence:collectcheck`** fails if a recording made by the policy-driven
  collector will not reproduce. Every recording the trainer wrote diverged at step 0 while still
  playing: `EpisodeCollector` read `heroPosition` before `env.step()` rather than after, while the
  recorder documents that field as where the hero *ended up*. The file parsed, the run scored, the
  dungeon looked like a dungeon — only `verify` caught it. The scripted path steps and then reads, and
  always has, so `replay-viewer.bat --record` was unaffected and only trainer output was. Behavioural
  rather than a unit test of the recorder, because the recorder was never wrong on its own; the caller
  passed it the wrong moment.
- **`gradle :superintelligence:replays`** lists recordings grouped by hero class and ranked by score
  within each group, and `--select N` resolves a listing number to a file. Both live in Java because
  Windows `sort.exe` on this machine rejects `/n` as an invalid switch and the scores are floating
  point and can be negative — batch would have sorted them lexically and wrongly, and could not group
  by a field parsed out of each file at all.
- **`gradle :superintelligence:replaycheck`** covers that catalog: ranking within a group, grouping by
  class, depth breaking a score tie, a truncated or unreadable file listed rather than hidden and
  sorted out of the way, name lookup bare and with an extension and case-insensitively and by path,
  `readHeader` agreeing with `read`, and one listing per seed across directories.
- **`Replay.declaredSteps` and `Replay.truncatedAt`**, so a header-only read can report a body that is
  short instead of failing on it.

### Changed

- **A recording is selected by number.** `replay-viewer.bat` with no argument lists the catalog and
  asks for a number, resolved through the same scan that printed it, so the number under a recording
  and the recording that number selects cannot drift apart.
- **`--epochs`, default 2 instead of 4.** An update is 11.3 ms per sample and dominated by forward and
  backward, so this scales it linearly: ~107 s of trainer CPU per generation at 4 epochs, ~53 s at 2.
  Four was never a considered choice — it gives 300 Adam steps over a 2,400-sample batch, far more than
  a batch that size supports. Measured end to end: a 6-generation prototype run went from ~36 minutes
  to 2.
- **`gradle :superintelligence:gates` runs every correctness check in one invocation.** Gradle's
  per-invocation overhead is about 90% of a gate's cost — `gaecheck` is 0.22 s of work and 2.4 s through
  gradle — so the checks were never slow and the harness around them was. Six gates together take 7.9 s
  against roughly 17 s run one after another. Named `gates` because the `java-library` plugin already
  contributes a lifecycle `check` and Gradle refuses to shadow it.
- **Workers compute advantages and ship a sample of them; the trainer runs a pooled update on it.**
  The worker plays its own episode with the real policy, keeps every step's GAE scalars, and retains
  observations for a uniform 5% of steps plus the last 20 of the episode. Because the backward pass
  reads only scalars, which observations are kept cannot change the numbers it writes — so sampling
  happens during collection and needs neither an all-observations residency of 1.9 GB nor a second
  simulation pass. The frame is observation + masks + decision + advantage + return + terminal;
  `reward`, `value` and `nextValue` no longer travel, since nothing on the far side recomputes from
  them.
- **One forward pass per step, not two.** `t.nextValue` is read at exactly one index of the GAE
  recursion; every other bootstrap is the next step's own value. The extra pass also advanced the LSTM
  over the post-step observation, so every observation was absorbed into the recurrent state twice.
- **gamma, lambda, sampleRate and maxSampledPerEpisode travel with the weights.** The first two were
  `PPO` fields on each side with identical defaults — agreement by coincidence, which would have
  drifted the first time either side was tuned. The last two are new knobs.
- **`PPO` no longer collects.** `collect()` drove the env, the network and the masks, and `update()`
  computed the advantages over whatever it had. Collection is now `EpisodeCollector` in a worker
  process, because the advantage recursion needs consecutive steps and a worker holds its whole
  episode while a trainer never would. `PPO` is the learner only, at 325 lines, and `collect`,
  `rollout`, `rolloutCap`, `episodeInProgress` and `recurrentStateStale` are gone rather than left
  unreferenced — two step loops would have drifted.
- **The worker keeps its transitions on a pool; the trainer allocates.** A pooled object is recycled
  across generations, so a decode that missed a field would read the previous generation's value
  rather than fail. The trainer's buffer is large and cold, which is the case the pool is wrong for.
- **A per-generation memory cap on the update buffer,** dropping the oldest transitions and saying so.
  The per-episode cap bounds one worker; the generation is 320 episodes wide, and this machine's
  2 GB pagefile does not degrade gracefully — it thrashes.
- **The trainer is split into five classes.** `Trainer.java` was 824 lines against the project's own
  500-line rule, and the worker-data-flow work adds to it. Process lifetime, the stall watchdog and the
  per-worker pipes are now `WorkerPool`; the console block is `GenerationReport`, which renders a
  snapshot of plain numbers rather than reading the trainer; the command line is `TrainOptions`; the
  per-episode summary is `Episode`; and the wire constants are `Protocol` rather than bare literals
  duplicated on both sides of the pipe. `Trainer` is now the loop itself.
- **Worker pipe buffers are 1 MB, not the 8 KB default.** A policy push is ~14 MB and a generation's
  transitions will be tens of MB; the default turns those into thousands of syscalls per worker per
  generation.
- **The generation report prints the transition count.** `sampled steps of N collected` is the number
  the whole sampled-transition design rests on, so it is measured and shown rather than derived on
  demand. It reads 0 until the workers start returning transitions.

### Added

- **A TODO entry for the thing that will block the first real training run: the weights are never
  written to disk.** No `saveWeights`, no checkpoint, no `--resume`. Every run starts from `Network`'s
  random initialisation and is discarded at the end, so no attempt so far has been extendable — a run
  has to be babysit from start to finish on a machine whose pagefile is 2 GB and which thrashes rather
  than degrades, `--generations` cannot be split across sittings, and no two training runs can be
  compared because only one line of them can exist at a time. `Network.layers()` / `loadLayer` are
  already the checkpoint format and already validate against the `EnvConfig` shape, so a stale
  checkpoint fails loudly rather than loading into the wrong parameters, and `Worker.writeWeights` /
  `readWeights` already serialise exactly that. It is plumbing, not design, and it is written down
  before it is built because it is invisible until you try to keep a model.
- **`gradle :superintelligence:gaecheck`** fails if the two advantage implementations disagree, or if
  sampling misbehaves. `Policy` computes GAE twice — once over an `ArrayList<Transition>` and once over
  an episode's scalar arrays — because a worker's backward pass must run where the whole episode is
  still resident. Nothing forces them to agree, and two implementations that both produce finite
  advantages while differing slightly is a bug that surfaces only as a policy that learns marginally
  worse, forever. Nine checks: the two agree, a terminal cuts the recursion both ways, a truncated
  episode bootstraps, sampling is uniform and seed-reproducible, the tail is always retained, retention
  is idempotent, advantages are independent of sampling, and the wire round trip is exact. Verified by
  mutation — transposing a field in the codec fails it, and removing the lambda term fails three
  checks. It does **not** drive the collector, only its arithmetic and its stated policy; the class
  comment says so.
- **The generation report prints what it is for.** `sampled steps of N collected`, the raw advantage
  mean and standard deviation *before* normalisation, and the buffer's resident size with the packed
  grid's share of it. A batch whose advantages are all identical has no gradient direction to offer,
  and after normalisation it would present as a textbook mean of zero and standard deviation of one —
  so the figure that catches it has to be the one taken before.
- **`gradle :superintelligence:updatecost`** measures what a PPO update actually costs and projects it
  across sample rates. The worker-data-flow decision was argued entirely on bandwidth, and the update
  behind it — 9,600 forward and backward passes per generation on one thread — had never been
  estimated. Measured at **11.29 ms per sample**: 107 s per generation at a 5% sample rate and 4
  epochs, against 0.12 s of transport and ~7 s of collection. Bandwidth was never the binding
  constraint at these sample rates.
- **Reported gradient norms and clip fraction,** per minibatch and averaged. A norm that climbs
  without bound is the earliest signal that an update is about to diverge, and clipping hides it.
- **`gradle :superintelligence:modecheck`** fails if the environment cannot reach one of its own
  action modes. It drives an explicit script through `WORLD`, `SLOT`, `TARGETING`, `INVENTORY` and
  `MENU`, resolves a real aim rather than cancelling one, and executes a drop. Six real bugs reached
  main while every recording was `WORLD`-only, and a replay fixture cannot prevent a recurrence:
  nothing failed when a mode quietly stopped being reachable.
- **`gradle :superintelligence:restartcheck`** fails if restarting a run does not rebuild an identical
  floor 1, even after several hundred turns have churned the random generators.
- **`replay-viewer.bat`** plays, records and verifies recordings from a terminal: `replay-viewer`
  plays the newest one, `--record <seed>` makes one, `--verify <file>` checks one, `--list` shows them.
  It drives Gradle tasks rather than hand-building a classpath, and resolves paths to absolute first
  because Gradle's `run` task uses the module directory as its working directory.
- **Replay fixtures in `replays/`,** including `modes-all.replay`, whose first six steps cover `WORLD`,
  `SLOT`, `TARGETING` and `INVENTORY`. Every recording made before this was pure movement.
- **Diagnostics on stderr.** The viewer reports each keypress and a per-two-second heartbeat, because
  "the key did nothing" and "the render loop is stalled" look identical from the outside and need
  different fixes.

### Added


- **Desktop replay viewer.** `gradle :desktop:replay --args="--file <replay>"` plays a recorded run
  back in the rendered game. Recorded actions are applied through `ActionMapper`, which hands them to
  `Hero.handle` — the same call the game's own cell selector makes — so attack-versus-loot-versus-
  stairs-versus-menu resolves exactly as it does for a player. The HUD shows step count, seed, hero,
  recorded score, depth, turns, speed and any divergence.

  Two engine hooks were needed, both general rather than replay-specific: `Game.lockCellInput`
  disables the cell selector each frame so nothing but the recording can inject an action, and
  `Game.setSceneClass` lets an entry point that builds the game choose its initial scene.
  `InterlevelScene.autoContinue` skips the region continue prompt so a viewer run starts on its own,
  and the viewer's keys are hard-bound because `InputHandler` only emits a `KeyEvent` for keys present
  in `KeyBindings`, which left space, `R` and `+`/`-` unreachable.

  Entering through `InterlevelScene` with `Mode.DESCEND` is what makes this work: `InterlevelScene`
  switches to `GameScene` hardcoded, so a `GameScene` subclass would never be entered. A static
  `frameDriver` hook pumps playback instead, and the recording is watched in the real scene with real
  sprites.


- `PPO.rolloutCap` bounds collection between updates. It is not only a memory guard: PPO measures
  how far the policy has drifted since it collected the data, so frequent updates are what PPO
  wants anyway.

- `gradle :superintelligence:gradcheck` (or `gradcheck --verbose`) finite-difference checks the
  network's analytic gradients against central differences and exits non-zero on a mismatch, so a
  broken backward pass fails loudly instead of silently training the wrong function.

### Fixed

- **An uncapped PPO rollout was an out-of-memory crash waiting to happen.** Collection stored every
  step of an episode until the next update. A step is dominated by its packed grid at ~49KB and
  `turnLimitTotal` is 40000, so one long episode is ~1.9GB against a 1536m worker heap.
  `PPO.collect()` now stops at `rolloutCap` (default 2048, ~98MB) and leaves the env mid-episode,
  resuming it with the recurrent state intact on the next call, so a long episode is collected
  across several calls instead of being cut short. Each collection is its own advantage segment.

  An update invalidates the hidden state of an episode still in progress - it changes the weights
  that state came from, and resets the state itself to shuffle minibatches. A weight change is now
  an information boundary and the agent forgets, because otherwise where updates happened to land
  would silently change an episode's actions and the same seed would stop reproducing.

- Three headless crashes that the scripted policy never reached but a random one walks into
  immediately, all the same shape of problem as a missing sprite:
  - Blobs had no emitter, so any of the fifteen blob types calling `emitter.pour()` from
    `evolve()` died with an NPE. `LevelPipeline` now attaches them alongside sprites.
  - `GameScene.cancel()` and `cancelCellSelector()` dereferenced a null `cellSelector`, so using
    any item crashed.
  - `GameScene.spellSprite()` read `scene.spells` with no null check, unlike the `emitter()`
    directly beside it, so eating food crashed.

- **The policy network's gradients were wrong in five separate ways, so training optimised a
  function other than the policy loss.** The network had never been executed before this, so none
  of the failures showed up as an exception - they produced plausible numbers and an optimiser
  step that moved nothing.
  - `Conv2D.backward` and `Dense.backward` omitted their activation derivatives, so no gradient
    reached the convolution or the trunk weights.
  - `LSTM.backward` read `h` and `c` that `forward` had already advanced, and used `c_t` where
    the derivative needs `c_{t-1}`. It now caches the gate pre-activations and both prior states.
  - `Network.backward` copied the hidden gradient into the LSTM *before* the heads had accumulated
    into it, so the recurrent cell always received zero.
  - The LSTM's input gradient buffer was never sized or cleared, so the gradient never reached the
    trunk. It is now sized from the cell's input width and refilled by `backward`.
  - `Network.headGradient` hand-rolled `W^T * dOut`, which skipped the head's own parameter
    gradient and its tanh derivative. It now goes through `Dense.backward`.
  - Also fixed the LSTM output being written back into the wider trunk buffer, so the heads were
    fed the wrong width, and a trunk input gradient buffer that was too narrow by the
    inventory and hero block.

  Verified against central differences: `gradle :superintelligence:gradcheck` compares analytic
  gradients to numeric ones on 112 sampled parameters across all seven layers, and fails the build
  if they disagree. The check has been confirmed to fail when a derivative is deliberately removed.

- **More identity-hash iteration in gameplay and level generation.** Collections keyed on
  identity hash codes iterate in a different order in every JVM, and `java.lang.Enum` inherits
  `Object.hashCode`, so enum-keyed maps are affected too. Sites where that order reached the
  outcome were made insertion-ordered:
  - `Char.buffs(Class)` returned a `HashSet`. Callers sort or iterate the result while consuming
    the RNG or folding floats non-associatively - `ShieldBuff.processDamage` distributes damage
    across barriers in that order - so damage and death could differ per run. Now a
    `LinkedHashSet`.
  - `Random.chances(HashMap)` picks off the map's iteration order, so the secret laboratory and
    secret library chose a different potion or scroll every run, changing what the floor
    contained. Their chance tables, and the ones in `WandOfCorruption`, `UnstableSpell`,
    `UnstableBrew` and `ChaoticCenser`, are now `LinkedHashMap`.
  - `Mob.chooseEnemy` resolved equal-distance and equally-attackable candidates by iteration
    order, so which of two equidistant mobs got targeted varied. Now insertion-ordered.
  - `CursingTrap`, `VaultLevel` and `Hero` used `Collections.shuffle`, which ignores the seeded
    generator entirely and is therefore different on every run. Switched to `Random.shuffle`.

- **A seed longer than 20 characters was silently discarded**, and the run continued on a random
  seed. `GameSettings.getString(key, def, maxLength)` treats an over-long stored value as corrupt
  and overwrites it with the default, and `SPDSettings.customSeed()` reads with a 20 character
  cap. A rollout asked to reproduce would simply not reproduce, with nothing reporting it.
  `LevelPipeline.startRun` now verifies the seed survived the round trip and fails with an
  explanation instead.

- `gradle :superintelligence:rollout` and `:verify` never passed their own subcommand to the
  entry point, so `--args` was parsed as the command and both tasks failed. The subcommand is now
  prepended at execution time, which also keeps working when `--args` is supplied.

  Verified: 120 rollouts - 4 seeds x 5 hero classes x 6 repeats - produce one distinct score per
  seed/hero pair, and a recorded 499-step run re-executes exactly 4 times out of 4.

### Known limitations

- **A replay recorded before this change with an empty `seed=` cannot be verified.** `SeedPool`
  deliberately leaks 10% of episodes onto fully random seeds so the agent cannot memorise the locked
  set, and those episodes were recorded with the *requested* seed, which is empty for them. `verify`
  resets onto a fresh draw and reports a divergence at step 0 — correct behaviour, and a message that
  reads as a broken seed lock. Any such file already on disk stays unfixable; re-record it.

## [4.1.0] - 2026-10-06

### Fixed

- **The environment is now reproducible across processes.** An audit found that recording a run
  and re-executing it in a fresh JVM usually reproduced it but not always - six runs of one seed
  produced four distinct traces. Three causes:
  - `Random.resetGenerators()` installs an unseeded base generator, and `Dungeon.init` called it
    after pushing the seeded stack, discarding it. Only level generation drew from a seeded
    generator; mob turns, combat rolls and item drops did not. Added `Random.reseedBase(long)` and
    call it from `Dungeon.init` with the run seed.
  - `EntranceRoom.placeEarlyGuidePages` pushed an unseeded generator during level generation,
    deliberately, to keep meta progression out of levelgen. The first guidebook page therefore
    landed on a different tile in every process, making floor 1 irreproducible on its own. Now
    seeded from the floor's seed with a fixed offset.
  - `Actor.all`, `Actor.chars`, `Level.mobs` and `Level.blobs` were hash-based collections keyed on
    identity hash codes, so iteration order differed between JVM runs. `Actor.headlessStep` broke
    time ties on that order, so who moved first varied. All four are now insertion-ordered
    (`LinkedHashSet` / `LinkedHashMap`); membership semantics are unchanged.

  Verified: 10 traces of one seed across 10 separate JVMs produce one distinct result, and a
  recorded 299-step run re-executes exactly 5 times out of 5 with a bit-identical score.

- `GameScene.add(Heap)` left `heap.sprite` null when there is no scene, so trampling high grass
  dereferenced it and crashed a rollout.
- `Image.frame` derived its size from a null texture when there is no renderer.

### Added

- **Headless training framework** (`superintelligence` module). Implements the design in
  `superintelligence/docs.md` and `superintelligence/research.md`.
  - `headless`: libGDX and noosa shims so the game runs with no renderer, no window and no audio -
    silent `Audio`/`Sound`/`Music`, classpath-aware `Files`, in-memory `Preferences`, an inert
    `noosa.Game`, a fixed-size `Graphics`, and a `CharSprite` that resolves animations instantly so
    combat, doors and chests still resolve their bookkeeping.
  - `env`: `SPDEnv` with a reset/step interface; `LevelPipeline` that starts runs, drives the actor
    scheduler single-threaded and services floor transitions and chasm falls without entering the
    scene system; `ActionMapper` that injects decisions through `Hero.handle` and produces the
    legal-action mask; `WindowBridge` and `SlotAction` for dialogs and inventory.
  - `obs`: `ObservationEncoder` producing binary spatial planes (visible vs. remembered fog of war),
    a fixed-length inventory vector with per-slot identity, level, curse and identification state,
    and normalised hero scalars.
  - `reward`: `RewardModel` scoring by state diffing, a `RewardTerm` per documented reward and
    punishment, `RewardLedger` keeping per-term and per-floor totals, and `Curriculum` fading the
    dense shaping terms out as depth is mastered.
  - `rl`: `Network` (one convolution over the grid, an LSTM for fog-of-war memory, and heads for
    action, slot, target and value), `PPO` with masked sampling, clipped surrogate, GAE and Adam.
  - `train`: `Trainer` pooling episodes across worker JVMs over a binary stdin/stdout protocol,
    `SeedPool` implementing the one-seed to ten to hundred to random generalisation schedule.
  - `replay`: `Replay`/`ReplayRecorder`/`ReplayIO` recording decisions rather than consequences, and
    verifying playback reproduces.
  - `diag`: `RunReport` rendering the floor-by-floor breakdown with green/red colouring, per-term
    totals, and score-over-turns and score-over-floors graphs with floor transitions annotated.
  - `policy.ScriptedPolicy`: a network-free heuristic used as an environment smoke test and as the
    worker bootstrap before the first checkpoint exists.
  - Gradle tasks `rollout`, `replay`, `train`, `probeClasspath` and `workerJar`.

### Changed

- `ItemSpriteSheet.Icons` builds its `TextureFilm` on first use instead of in a static initialiser.
  Icon indices are game data and remain pure arithmetic, but item constructors no longer force a
  texture decode through a GL context.
- `CharSprite` gains `sprint`, `updateArmor`, `read` and `fall` as overridable no-ops, hoisted from
  `HeroSprite` and `MobSprite`, so game logic no longer depends on which sprite a character uses.
- `AttackIndicator.target`/`updateState`, `QuickSlotButton.target`, `GameScene.selectCell`,
  `GameScene.checkKeyHold` and `GameScene.resetKeyHold` tolerate absent UI singletons.
- `TextureCache.getBitmap` returns null without a GL context, and `TextureCache.get` no longer
  caches or wraps a null bitmap. `NinePatch` accepts a null texture and a new no-argument
  constructor for texture-free use. `Chrome.get` and `ShadowBox` return blank chrome without a
  renderer. `PlatformSupport.getSafeInsets` returns empty insets without a display.

### Added (game)

- `Actor.headlessStep()` - one turn-scheduler iteration with no render-thread handshake, for
  single-threaded headless rollouts.
- `GameScene.show` records a dialog when there is no scene; `GameScene.pendingCellListener` records
  an aiming request when there is no cell selector. `WndOptions.optionCount`,
  `optionSelectable` and `selectOption` plus `Button.press` let a caller enumerate and answer a
  dialog with no UI.
- `Item.throwAt()` - the bookkeeping half of `Item.cast`, without a projectile sprite.

### Documentation

- `superintelligence/TODO.md` - status against the design documents: what still needs doing, ordered
  by what unblocks learning first; deliberate deviations with the cost of each; and the source
  documents' references to classes that no longer exist in this version.

### Renamed

- `ReplayIO.play` is now `ReplayIO.verify`, and `ReplayIO.Result` is `ReplayIO.Verification`. The
  operation re-executes a recording to check determinism and draws nothing; `play` implied a human
  watching the game, which the desktop viewer - still to be built - would do. The `replay` CLI
  subcommand and Gradle task are now `verify`. The `Replay` file format keeps its name: a recorded
  run that can be re-executed is a replay.

[4.1.0]: https://github.com/00-Evan/shattered-pixel-dungeon/compare/v4.0.1...v4.1.0
[Unreleased]: https://github.com/00-Evan/shattered-pixel-dungeon/compare/v4.1.0...HEAD
