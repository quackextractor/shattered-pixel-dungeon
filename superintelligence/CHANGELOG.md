# Changelog - Superintelligence

All notable changes to the **Superintelligence module** are documented in this file.

This module is versioned independently of the game it wraps. The game is
[Shattered Pixel Dungeon](../README.md).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Fixed
- **A run recorded one episode in three, so "best per seed" meant best of whatever happened to be sent.*
- **`replay-batch.ps1` reported `total 0` over a directory full of recordings.**
- **`replay-batch.ps1 -Parallel N`.**
- **Every update thread shared one `Network`.**
- **`train.ps1 -Resume` had never worked, and the parser turned it into a stack trace.**
### Changed
- **The scripts that configure, watch and rebuild this module now live in it.**
### Added
- **`--replay-group <id>`: a run's recordings are its own group, in the catalogue and on disk.**
### Fixed
- **An item that creates a mob left it with no sprite, and killed the worker - now committed and gated.*
- **A free-action loop was charged and not bounded, and the charge did not stop it.**
### Changed
- **A run's artefacts go to `superintelligence/out/run/`, not to the system temp directory.**
- **`env.free_step_budget`** (`SPD_ENV_FREE_STEP_BUDGET`), **40**.
- **`mobspritecheck` is a gate**, so `gates` is now 24 checks.
### Fixed
- **A descent pays for a new floor, not for going down a stair.**
- **A run now shows a progress bar, and a script means nobody types a path.**
- **The viewer catalogue no longer goes through gradle.**
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