# Changelog - Superintelligence

All notable changes to the **Superintelligence module** are documented in this file.

This module is versioned independently of the game it wraps. The game is
[Shattered Pixel Dungeon](../README.md), tracked upstream and pinned here at the version its newest tag
names; the `version-` badge in that file is the **game's** version and is not touched by anything
recorded here. The version series below is the module's own, and it starts at **0** so that it cannot
be mistaken for a game release even when read out of context: the game is at `4.0.2`, this module is at
`0.4.0`, and no `0.x.y` here corresponds to any `x.y.z` there.

**The module is pre-1.0, and that is a statement about stability rather than about numbering.** Under
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) a `0.x.y` release carries no compatibility
promise, so a minor bump here may still change behaviour a caller depended on - and one already did:
`0.3.0` re-priced `STALLED` from zero to the death penalty, which is a semantic change to the reward
function and invalidated the recording corpus. What the `0.x` line buys is that **major** does not
happen by accident. Under the old `4.x` numbering a `0.1.0`-scale change to this module would have been
forced to call itself `5.0.0` and would have sat next to a game `4.0.2` in the same repository, two
badges reading `5.x` and `4.0.x` for two things that had nothing to do with each other.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.0] - 2026-10-10

### Changed

- **The changelog moved here, and the module's version is now its own, on its own line.** Everything
  below was written under a repository-wide changelog whose version series had drifted ahead of the
  game's - `4.3.3` here against a game pinned at `4.0.2` - so a reader of the root `README.md` badge
  had two incompatible answers to "what version is this". Two artefacts, two version lines, and each
  one moves when its own subject moves.

  The split is not cosmetic in one direction: **the game's version is upstream's.** This fork's work
  touches `:core`, `:SPD-classes` and `:desktop` only through the additive, guarded changes recorded in
  [`ENGINE-CHANGES.md`](ENGINE-CHANGES.md), so there is nothing for it to release. A game-side entry
  claiming a release that upstream never made would be a second thing to be wrong about.

- **The module's series now starts at 0 rather than at 4.** `4.1.0` → `0.1.0`, `4.2.0` → `0.2.0`,
  `4.3.0` → `0.3.0`, and the three patches below it unchanged in kind. This is a re-base of the number,
  not a re-reading of the history: `4.x.y` and `0.x.y` here differ by exactly 4.0 at every point, which
  is the check that the classification had to be right before the offset was applied.

  Each release was classified on what it actually did rather than on the number it was given, and the
  classification is the offset - which is the useful part, because it is the part that could have
  differed:

  | was | what it did | class | now |
  | --- | --- | --- | --- |
  | 4.1.0 | The module's first release. The whole headless RL framework, the engine getters it needs, the replay format, and `ReplayIO.play` renamed to `verify`. Nothing in this repository existed to call before it. | minor | **0.1.0** |
  | 4.2.0 | `feat(viewer)`: HUD corner cycling, five viewer faults closed, and `playbackcheck` grown 7 → 18 → 20 checks, alongside `viewdiff`, `WorldSnapshot`/`WorldDiff`, `FrameDelta`, `viewcheck`, `observecheck`, `rngtrace`, checkpoints, `verifyall` and the parallel update. Added surface, no existing caller changed meaning. | minor | **0.2.0** |
  | 4.3.0 | `feat`: floor transitions serviced headlessly behind `transitioncheck`, and `STALLED` re-priced from zero to `-deathPenalty` - which is the one release here that *did* break behaviour a caller depended on. | minor | **0.3.0** |
  | 4.3.1 | `fix(superintelligence)`: the hero takes the landing act on a new floor. | patch | **0.3.1** |
  | 4.3.2 | `fix(viewer)`: a recording ending in death no longer leaves a remains file in the player's profile. | patch | **0.3.2** |
  | 4.3.3 | `docs` only: `issues.md` "Env" 6 and 7 investigated, `TODO.md` §0.8 added, one README table row corrected. No code, no configuration, no public surface. | patch | **0.3.3** |

  Two consequences worth stating. **The old numbers were not wrong about major/minor/patch** - the
  classification was already correct, and it stays correct at 4.0 lower; what was wrong was the
  *neighbourhood*, since a `4.4.0` module next to a `4.0.2` game invites a reader to treat them as
  one line. And **no release becomes a major.** Nothing in this module's history changed a caller's
  meaning in a way that was not already announced as a fix or a feature, which is why the series can
  begin at `0.1.0` rather than at a placeholder `0.0.1` that implies a release came before any release.

  Cross-references inside this file were moved with the headings rather than left pointing at numbers
  that no longer exist, because a changelog whose entries cite versions nobody can reach is worse than
  one with no cross-references at all.

  - [`superintelligence/CHANGELOG.md`](CHANGELOG.md) (this file) - the module's history, 0.1.0 onward.
  - [`../README.md`](../README.md) - the game's history is upstream's. Its `version-` badge is checked
    against the newest `v*` git tag rather than against anything written by hand, so it follows a merge
    and cannot drift from it.
  - The root `README.md` gains exactly two lines: a separate `superintelligence-<version>` badge, and
    the link to [`README.md`](README.md) that was already there. Nothing else about the game's readme
    moved.

- **The pre-commit hook checks two badges instead of one, and they are checked against two different
  sources.** It previously read a single `CHANGELOG.md` and a single `README.md`, so after the split it
  would have compared the game's badge against the module's changelog and silently rewritten the wrong
  file. Now: the root `README.md` badge against the newest `v*` tag, and this module's badge against the
  newest heading here. Both are corrected and re-staged rather than refused, which is what the hook
  already did for one of them.

### Fixed

- **The repository had a changelog it had no right to have.** Upstream `shattered-pixel-dungeon` has
  never shipped a root `CHANGELOG.md` - its release notes live in `metadata/en-US/changelogs/` - so
  every version from 4.1.0 to 4.3.3 was this fork asserting a game release it did not make. The file is
  removed rather than trimmed; nothing was lost with it, and `git log --follow CHANGELOG.md` still
  reaches the whole history through the rename.

- **The pre-push hook still described a red viewer.** `hooks/pre-push` printed *"divergences here are
  EXPECTED while ISSUE-viewer-frame-drift.md is open"* and attributed its non-blocking behaviour to
  the gate failing 7-9 of 17. That issue is fixed and `:desktop:viewcheck` is green on all 17,
  re-measured for this release (219.7 s, one child JVM per recording). The hook was telling every
  pusher to expect a failure that no longer happens, and teaching the wrong reason for why it does not
  block. The header now states the measured state and gives the real one: it needs a GL context, so it
  cannot run on a build machine. Making it blocking is a one-line change left explicitly undecided
  rather than decided for whoever pushes next.

### Verified

- `verifyall` - `:superintelligence:gates` at 20, `:desktop:playbackcheck` at 21, both green.
- `:desktop:viewcheck` - 17 of 17 played clean in the real viewer, re-run for this release rather than
  carried over, so the "17 of 17" every other entry in this file quotes is measured rather than
  inherited.

## [0.4.1] - 2026-10-10

### Documentation

- **`issues.md` "Training" 13 investigated: the stall is real, but the hero is looping rather than
  idling, and the recommended remedy would hide the fault rather than fix it.** Replaying `duelist-mid`
  through the real environment with the engine clock sampled per step shows the last 120 steps alternating
  `USE` on a `Rapier` at **zero engine time each** — `SlotAction.use` (`SlotAction.java:77`) routes an
  equippable, non-targeting item to `toggleEquip`, which equips, then unequips, then equips, and
  `hero.next()` releases the hero every time so the drain returns `READY` and believes the turn resolved.
  `checkFloorLimits` (`SPDEnv.java:504`) sees an unchanged position and HP and terminates after 120.

  **`STALLED` is a true verdict here.** Removing it, as the report offers as a worst case, would let the
  loop run for another `turnLimitTotal = 40000` zero-cost steps and record the result as an ordinary
  truncation — destroying the only signal that the run made no progress. And the action is genuinely free
  in the real game, not a harness artefact: `WndUseItem.onClick` calls `item.execute` and returns,
  spending no turn. The policy found a real free action. The fix that would help is making the stall guard
  count engine time rather than only position and HP, so any zero-cost loop terminates *and is labelled as
  one*. **Not done** — it changes termination behaviour for the whole corpus and needs its own gate.

- **`cleric-mid` is a second and unrelated `STALLED`, and conflating the two is what makes this look like
  one bug.** It spends 1.0–3.0 engine time per step, moves (427 → 463 → 427), and ends `resting=true`, so
  it never accumulates the 120 same-cell steps the stall guard needs and must have been ended by the
  resting backstop at `LevelPipeline.java:259` instead — on a hero resting legitimately, who was about to
  heal. Three distinct outcomes (the stall guard, the resting backstop, and `STEP_LIMIT` drain exhaustion)
  all write the same `STALLED` string to `Replay.termination`, so the corpus cannot be triaged from its
  own header. That is why `issues.md` 10 had to be diagnosed by replaying and instrumenting instead of by
  reading the recording. **Recording which producer fired is worth doing before either fix.**

- **The recommendation to regenerate the recordings is recorded as the wrong remedy, with the reason.**
  It would work and fix nothing: the policy that produced `duelist-mid` will produce it again, because the
  free action is still in the environment. `duelist-mid` has already been regenerated twice during earlier
  work, which is why the evidence `issues.md` 8 cites no longer exists in that file. The recording is a
  symptom; the loop is the fault.

- **Two defects found alongside it, recorded in `TODO.md` §0.8.** Neither is fixed.

  - **`env.grid_height` is documented and bound, and only the encoder implements it.**
    `ObservationEncoder.java:57,60,61` is the only consumer that uses `gridWidth * gridHeight`. `Network.java:93`
    constructs `Conv2D` from `gridWidth` alone — it is square by construction — and `Network.java:129`,
    `TransitionCodec.java:47,159`, `PPO.java:156`, `EpisodeCollector.java:138`, `GradientCheck:72`,
    `ParallelFixtures:50`, `GaeCheck:488` and `ReplayProbe:54` all compute `gridWidth * gridWidth`. So
    `env.grid_height=64` at the shipped 48 allocates a 48×64 encoder feeding a 48×48 network. Harmless
    only because the key has never been set to anything but its default. Either implement the rectangle
    throughout or **refuse a non-square pair in the binder**, which is small and removes the silent path.
  - **None of `env.max_slots`, `env.grid_width` or `env.grid_height` has a range check.** `EnvConfig` has
    no `validate()` and `PpoHyperparameters.validate()` (`PpoHyperparameters.java:116`) covers only `rl.*`,
    so `EnvConfigBinder`'s single validation call (`:214`) never sees them. `env.grid_width=0` is accepted
    and fails later as a `NegativeArraySizeException`; worse, `Conv2D.java:49` computes a *negative*
    `outSize` for `gridWidth < 3` rather than failing legibly. `ConfigCheck`'s sentinels for these keys
    (`:528-530`) only prove the binder *reaches* them — its refusal cases (`:344-347`) cover `rl.*` and
    parse failures. A sentinel that proves a key is read is not a check that its value is usable.

### Changed

- `TODO.md` gains **§0.10** for the equip loop, and its header names it alongside the two §0.8 defects.
  The section is numbered 0.10 rather than 0.9 because **§0.9 was already taken** — by the versioning
  entry added in 0.4.0 — and a file that indexes work by number cannot carry two of the same one.
- `issues.md` 13 now cites `TODO.md` 0.10 in its status line rather than an unnumbered "see below".

## [0.3.3] - 2026-10-10

### Documentation

- **`issues.md` "Env" 6 closed on measurement: the 32x32 figure is wrong, and 48 is not too small.**
  Regular floors are not hardcoded to any size at all - `RegularPainter.java:113` ends level generation
  with `level.setSize(rightMost + 1, bottomMost + 1)`, where the extents are those of the rooms that just
  generated. Measured 1040 floors over 40 seeds at depths 1-26: max width 67, max height 81, and **419 of
  1040 exceed 48 on at least one side**. The 32s are the hand-built boss floors, not the regular ones.

  Which does **not** mean the agent is unprepared for the other 60%. `ObservationEncoder.buildSpatial`
  never sees the whole floor: it samples a hero-centred window, `spanX = gridWidth + MARGIN * 2`, so on a
  67-wide floor `stepX = 52/48 = 1.083` and the window subsamples at about 92% while sliding with the
  hero. The clamp at `ObservationEncoder.java:126-132` makes no floor shape able to read it out of bounds.
  A tall floor is one the encoder represents as a window rather than a map, which is the design.

  What was left open rather than folded into the closure: `EnvConfig.java:16`'s claim that 48 "covers the
  widest floors" is false against the measurement above, and `env.grid_height` is a documented key that
  only the encoder implements - `Network`, `Conv2D`, `TransitionCodec`, `PPO`, `EpisodeCollector` and five
  more all use `gridWidth * gridWidth`, so a non-square pair desynchronises the two halves silently.
  Neither `env.max_slots`, `env.grid_width` nor `env.grid_height` has a range check either.

- **`issues.md` "Env" 7 investigated; the quote is right and the shipped width is not.**
  `env.max_slots=32` against an engine ceiling of 96, truncating silently. `TODO.md` 0.8 records the
  measurement, what raising the width costs (96 is +238k parameters, a larger addition than the entire
  convolution stack, and bought entirely for an endgame state), and three alternatives - a pooled set
  encoding, attention over per-item tokens, and a bag-aware `OPEN_BAG` action. **Nothing is fixed.**

  The finding behind it is that `TODO.md` D7's stated justification does not hold: keeping slot out of the
  action enum does make the *action* head capacity-independent, but the *slot* head's output width **is**
  `maxSlots` and the input width is `maxSlots * 14`, so the property D7 claims is not present in the
  implementation.

### Changed

- `TODO.md` gains §0.8 (the inventory window, recorded not fixed) and its header states which suite
  results were re-measured and which were carried over — `verifyall` re-run and green after this work,
  `viewcheck` not run because it needs a display.
- The `README.md` status table row for the observation encoder no longer reads a bare "Verified"; it names
  the limit and points at §0.8.

## [0.3.2] - 2026-10-10

### Fixed

- **Watching a recording left a dead hero's belongings in the player's own game.** A recording that ends
  in death kills the hero for real, and `Bones.leave()` writes `bones.dat` into the process's file root
  exactly as it would for a playthrough. Three recordings in the corpus end in death, and that file is
  read during level generation - so the next run a player started opened on a heap belonging to a hero who
  had only ever existed inside a recording. Measured: playing `duelist-mid` in the viewer left a
  `bones.dat` in `%APPDATA%\.shatteredpixel\Shattered Pixel Dungeon`, the player's own profile.

  Two things were wrong and either alone would have left it there. `Bones.clear()` resets in-memory
  state only, and says so in its own comment; the file has to be removed separately. `RunState` did
  remove it separately, but guarded the delete on `instanceof HeadlessFiles` - and the viewer is not on
  the headless backend, so the guard was false and it deleted nothing without saying that it had not. The
  delete now resolves through `FileUtils.getFileHandle`, the same resolver `Bones` writes with, because
  the game's root is `External` on a normal launch and `Absolute` whenever `-Dspd.fileRoot` is set - so
  `Gdx.files.local`, the obvious guess, is wrong for both.

  `ReplayPlayer` also clears it when playback ends, in `halt()`: every path that stops playback reaches
  there, whereas a headless driver stops calling `update` the moment playback stops and a quit never
  reaches another frame, so a cleanup on the next frame would pass every rendered run and never run in a
  gate at all. `RunState` clears it when a player is constructed too, so entering the viewer also clears
  what a previous session left.

### Added

- **One more check in `playbackcheck`** (20 -> 21), covering exactly that: play a committed death
  recording to completion and assert no remains file survives. Mutation-tested - removing the cleanup from
  `halt()` leaves `bones.dat` present and fails it. The case states plainly what it does *not* cover: it
  runs on the headless backend, so it exercises the branch that already worked and cannot reach the
  real-backend branch that was broken. The viewer-side half is guarded; the backend half has no headless
  oracle, and claiming otherwise would be worse than saying so.

## [0.3.1] - 2026-10-10

### Fixed

- **A run that descended and then climbed back up diverged from its own recording.** `duelist-mid` goes
  down at step 157 and back up at 159, and from the ascent on the trainer and the rendered viewer
  described different worlds - `DIVERGED at step 161 - MOVE_SE/0 in WORLD: hero at (11, 9) pos 317,
  recording says (10, 8) pos 282` - which is what `issues.md` 10 filed and what had `verifyall` red.

  The two differences between `InterlevelScene.ascend()` and `LevelPipeline.handleTransition()` were
  both innocent, and the way they were eliminated is worth stating: **the regenerated floor came back
  identical in both environments, roster for roster.** `Mob.holdAllies` and `Dungeon.saveAll`, the two
  calls the pipeline omits, could not have been it.

  The fault is one a recording cannot show, because a step whose action was thrown away still records
  a step. The hero arrives on a new floor where everything is at time 0 - `Dungeon.newLevel()` calls
  `Actor.clear()` - so the game's own scheduler picks him for exactly one act before the player can act
  at all, and `Hero.act()` is where `Hero.checkVisibleMobs()` runs. That is what wakes the floor's
  sleeping mobs, and a mob seeing the hero for the first time calls `Hero.interrupt()`, which discards
  whatever action is pending. `SPDEnv.settle` was handing control to the agent before that act, so
  **the agent's first action on every new floor was spent being noticed by a rat**:

  ```
  step 160  MOVE_SE  ->  282  engine time 0.0     the trainer: interrupted, no time spent
  ```

  while the viewer, replaying the same recording in the real game, moved to 317.

  Not draining on arrival was deliberate, and correct when it was written: the new floor's sleeping mobs
  had to be worked up to the hero's stale clock, and the trainer recorded `engine time 129.0` where the
  viewer read `0.0`. `Actor.fixTime()` removed the stale clock and the workaround outlived it. This is
  the sixth instance of the class `PLAN-viewer-fidelity.md` §6 names - quickslot bindings, scheduler
  tie-breaking, the intro flag, `Actor.fixTime()`, the extra drain, and this.

  `SPDEnv.settleLanding()` now gives the hero that act. One act and not a drain, because
  `Hero.act()` with no action pending calls `ready()` and returns without spending anything - which is
  also what stops the loop on its first iteration, so the two are different things here rather than the
  same thing at different sizes.

- **The "Known issue" carried in 0.3.0 is closed.** `viewcheck` is **17 of 17** for the first time since
  the corpus grew a recording that descends, `playbackcheck` is green at 20, and `verifyall` passes.

### Added

- **Two more `transitioncheck` cases** (7 -> 9). One drives a descent *and* an ascent and then asserts
  the agent's first action on the floor it comes back to actually moves the hero; the other asserts the
  ascent is serviced at all, which no gate had ever exercised - every one of the other seven descends,
  and an ascent is the one direction with a second engine behaviour attached to it
  (`InterlevelScene.ascend()` reads a floor off disk when the depth is in `generatedLevels`, and
  descending is what puts it there).

  The first case's fixture is found at run time rather than hard-coded. A hero arrives at a floor's
  *entrance* and level generation keeps entrances clear: the arrival field of view of all nine seeds
  tried was empty of enemies, so a case built on a descent alone would have passed with the fault present
  and said nothing. Climbing back up lands in an ordinary room, which is the shape `issues.md` 10
  actually diverged on. Both are mutation-tested - removing the landing act fails the first with "the
  agent's first action on depth 1 moved nobody", and refusing to service an ascent fails the second.

### Changed

- **`duelist-mid` regenerated**, and only that one. It is the corpus's only descending recording, so it
  is the only file the change touches; the other sixteen are byte-identical, which is the determinism
  claim the regeneration script makes rather than a coincidence. It still descends at 157 and climbs
  back at 159, so the path stays covered by the corpus as well as by the gate.
- **`transitioncheck` added to the gates table** in `docs/documentation.md` and `testing-guide.md`,
  which listed nineteen gates and had been one short since it was added.

## [0.3.0] - 2026-10-10

### Fixed

- **Descending ended the episode, so nothing had ever left floor 1.** `Game.switchScene(...)` raises a
  request and returns; a real game turns it into "a scene wants to take over" in `Game.step()`, which is
  reached from `Game.render()`. A headless rollout never renders — that is what `HeadlessGame.render()`
  being empty means — so the acknowledgement was never raised, `LevelPipeline.runToHeroReady` never saw
  the hand-off, and `Actor.headlessStep()`, which already honours the *request*, had stopped the
  scheduler dead. The drain ran out its 20,000-step budget, returned `STEP_LIMIT`, and `SPDEnv` maps
  that to `STALLED`: a hero at full health walks onto the stairs and the run is over on the spot.

  `duelist-mid` was the recording that showed it — `termination=STALLED`, last step `INTERACT` onto the
  exit at cell 338 — and the rendered viewer descends for real, so the two disagreed about whether the
  run had ended. `HeadlessGame.switchRequested()` now reports the request as well as the acknowledgement.

  **This is why `bestDepth` has been 1 in every generation of every run.** The one action that would
  have moved the agent was ending the episode, so `TODO.md` 1.4 was blocked on a harness fault rather
  than on anything about learning. Three further faults were only reachable *because* nothing descended,
  and are fixed with it:

  - **`DEPTH_ADVANCE` could not fire.** `SPDEnv.onFloorTransition` called `reward.resetSnapshot()`
    *after* the depth had changed, so the next `reward.step` re-took its snapshot at the new depth and
    compared it against itself. The primary goal term — the one `research.md` says survives into the
    sparse phase — was dead.
  - **The environment drained where the game does not.** `InterlevelScene` builds the floor and hands
    the player straight back; `SPDEnv` kept draining, which meant working the new floor's sleeping mobs
    up to the hero. `Dungeon.newLevel()` calls `Actor.clear()`, which zeroes the clock, so the hero's
    clock was the whole previous floor's length — 128 turns on `duelist-mid`. The trainer recorded
    `engine time 129.0` where the viewer read `0.0`.
  - **`Actor.fixTime()` was missing.** `InterlevelScene`'s descend thread opens with it, pulling every
    actor's time back before `Dungeon.newLevel()` zeroes the clock; the pipeline went straight to
    `newLevel()`, so the hero arrived carrying the previous floor's time. Fifth instance of the class
    `PLAN-viewer-fidelity.md` §6 records — quickslot bindings, scheduler tie-breaking, the intro flag,
    and now this.

- **A hero could pick an item up and put it down forever, and the score would climb.** `RewardModel`
  compared a **count** of carried items, one way: `items > prevItemCount` paid out and a decrease paid
  nothing. `INTERACT, DROP, INTERACT, DROP` is three steps a cycle with no limit on cycles, and each
  pickup paid again with the world unchanged. Now a net diff of what the inventory is worth, in both
  directions, with a new `RewardTerm.ITEM_DROPPED` so the round trip appears in the per-term report
  rather than as a total that does not add up to its parts. Valuing rather than counting is also more
  correct than merely non-exploitable: dropping a wand to make room for a bag is a net gain and is
  scored as one, which a count cannot tell from discarding the only thing you were carrying.

- **`STALLED` costs what dying costs.** `RewardModel.terminate` now records `-config.deathPenalty`,
  through the same constant `DEATH` uses so the two cannot drift apart.

  **This reverses `PLAN-reward-signals.md` §3.2**, which set it to zero on the argument that a stall is
  a harness timeout and so should be "priced by turn cost alone". That argument was sound while every
  stall was one the agent walked into. It stopped being true when the stall guard was found to fire on
  *every* descent — "the episode ended on a timeout" was a harness bug wearing an outcome's name, and
  pricing it at zero made the bug free. It cannot re-create the trap §3.2 closed: stalling used to be
  cheaper than surviving to the turn cap and now costs a hundred times more.

  A residual tension is recorded rather than resolved. `STALLED` stays a **truncation**, so GAE still
  bootstraps through it, which leaves a -100 penalty that is also treated as "the episode carries on" —
  the same contradiction `PLAN-reward-signals.md` §2.4 identifies and §3.4 fixed by setting it to zero.
  Resolving it means changing `isNaturalEnding` and with it every advantage a stall produces.

- **`rewardcheck` asserted the opposite of its own name.** `checkStallingIsNotCheaperThanDying` tested
  `stall.score < death` and printed "cheaper"; a score is negative, so `-0.24 < -100` is false and the
  condition fires on a stall being *more* expensive than dying. It therefore never fired on the -5.0 it
  was written to catch. It now tests `stall.score > death`, which fires on -5.24 and on -0.24 and
  passes on -100.24.

  Worth recording for what it says about the suite: a gate can agree with its own name for as long as it
  exists and still not test it. That is the same shape as `Graph.bar` widening an axis it had not
  measured, and it took a re-tune to notice rather than a mutation test.

- **A recording's `depth` header was the depth the hero died at, not the depth it reached.**
  `Replay.depth` and the trainer's per-episode depth now carry `Statistics.deepestFloor` — the game's
  own figure, reset by `Dungeon.init`, so per-run. `duelist-mid` reaches floor 2 and dies on floor 1 and
  reported `depth=1`; `TODO.md` has spent several entries reasoning about "every recording is depth=1",
  and a header that can say 1 for a run that went down keeps saying it.

- **The viewer stalled on a descent instead of yielding.** `Game.switchScene` only sets a flag; the
  scene is built by `Game.step()`, after `ReplayPlayer.driveToHeroReady` has returned. `Actor.headlessStep`
  honours the flag by refusing to advance, so the drain spent its whole 400-step budget on nothing and
  reported `stalled - hero did not become ready within 400 turns` on a hero standing on the stairs at
  full health. It now yields the frame, bounded at 900 frames with a named halt, for the same reason the
  animation case below it already yields.

- **The viewer read a floor back out of a save it never wrote.** `InterlevelScene.ascend()` calls
  `Dungeon.loadLevel(...)` whenever `Dungeon.levelHasBeenGenerated(depth, branch)` is true — and
  descending first records the depth, so a run that went down and climbed back up satisfied the test and
  stopped on screen with `Cannot read save file`, with nothing to show it for.
  `ReplayPlayer` now clears `Dungeon.generatedLevels` on the frame a transition is pending, which is what
  `LevelPipeline.handleTransition` already does and for the same reason. Viewer-side only; a real
  playthrough, where the file genuinely exists, is untouched.

- **`ViewCheck --one` was not muted.** The gate forks every child with `-Dspd.mute=1` — no audio device
  is guaranteed on a build machine and an unhandled one aborts the GL context — and the
  single-recording path inherited nothing, so re-running one recording to investigate a failure played it
  out loud with the window visible and left open. `playOne` now states the same defaults, and only where
  they are unset, so an explicit `-Dspd.hidden=0` still wins. `ReplayLauncher.MUTE` is a `static final`,
  so it has to happen before that class loads.

### Added

- **`gradle :superintelligence:transitioncheck`**, a gate, 7 cases. It drives a real descent through
  `Level.activateTransition` — which is `Hero.actTransition`'s own body for a hero standing on the
  stairs — and asserts that the environment notices, that the episode does not end, that a second
  descent works (so the acknowledgement is being cleared), that `DEPTH_ADVANCE` pays, that the floor
  being *left* is the one marked cleared rather than the one arrived at, that the new floor is playable,
  and that the engine clock restarts with the floor.

  The last of those exists because of what the others could not see. With the clock reading 129.0 in the
  trainer and 0.0 in the viewer, the descent itself had already been fixed and the corpus already
  contained a recording that descended — and no gate failed, because `transitioncheck` did not exist
  yet and every other gate re-executes through the trainer. Mutation-tested: reinstating the old
  `switchRequested()` fails all 7.

- **`RewardTerm.ITEM_DROPPED`**, and a `rewardcheck` case for the exploit it closes. The case puts a
  known item on an adjacent cell through `Level.drop` and has the hero walk onto it, because a case that
  picks its own target on the floor reported "no item appeared" for reasons that had nothing to do with
  the reward — `INTERACT` walks the hero onto a second `Waterskin`, which `doPickUp` refuses, while the
  `CrystalKey` pile one cell away is refused headlessly. One round trip is the whole proof: the assertion
  is that the drop refunds exactly what the pickup paid for the same item, matched by identity, and that
  is a per-item identity rather than a running total.

- **`LevelPipeline.game()`**, so a harness can ask whether a scene switch is pending, and
  `SPDEnv.deepestDepth()`.

### Known issue

- ~~**Ascending after descending still diverges, and `verifyall` is red because of it.**~~
  **Fixed in 0.3.1.** `duelist-mid` descends at step 157 and climbs back at 159; `:desktop:viewcheck`
  and `:desktop:playbackcheck` reported the same `DIVERGED at step 161 - hero at (11, 9) pos 317,
  recording says (10, 8) pos 282`, so it was not a viewer artefact. Two differences between
  `InterlevelScene.ascend()` and `LevelPipeline.handleTransition()` were known and neither was
  confirmed: `ascend()` calls `Mob.holdAllies` and `Dungeon.saveAll()` and the pipeline calls neither,
  and `Mob.holdAllies` still uses `Collections.shuffle` - unseeded, and open since
  `ENGINE-CHANGES.md` §7.

  Both were innocent: the regenerated floor came back identical in both environments. The real fault
  was that the environment never gave the hero a turn on the new floor, so a mob waking up and seeing
  him for the first time interrupted the agent's first action there. Recorded as `issues.md` 10 rather
  than left implicit, because a green `verifyall` is now available and the pre-commit hook was right to
  refuse it before.

### Changed

- **The corpus is regenerated and no longer entirely depth 1.** `duelist-mid` and `warrior-death` now
  reach floor 2 and record `depth=2`. All 17 recordings reproduce headlessly.

- **`viewcheck` is 16 of 17**, down from green on 17 — not a regression, but the far side of the same
  fault: the recordings that descend are new to the corpus and one of them ascends again.

## [0.2.0] - 2026-10-10

### Added

- **The viewer's HUD can be moved, and now defaults to the top left.** `A` cycles the block through all
  four corners. It was pinned to the bottom left, which puts it directly over the hero — and a
  recording spends most of its time on a hero that has not moved. Top left is the one corner the game
  keeps clear: the depth banner is centred, the item log runs up the right edge. Cycled rather than
  toggled between two corners, because which one is right depends on the window and on what else is on
  screen, and a two-way toggle makes someone press the key twice to reach the other three. The help
  line stacks inward from whichever corner is current, so it stays adjacent to the HUD at every anchor.

### Fixed

- **The viewer, five faults from `superintelligence/issues.md` "Viewer / recordings".** Each was
  reported as a symptom; none was where the fault was, and two of them had been hiding a third.

  - **`R` after a finished recording left it finished, and SPACE then waited the hero.** A restart
    nulls `Dungeon.hero` and re-enters the interlevel scene, and the frame driver runs throughout the
    rebuild - so the drain read the null hero as the end of the run, finished the recording on the
    first frame after the keypress and left the HUD reporting a completed replay over a rewound
    cursor. Separately, the viewer's key overrides were re-asserted only when the HUD was rebuilt, and
    `InterlevelScene` calls `KeyEvent.clearListeners()` every time it hands off to the game scene; in
    the gap the game's own SPACE binding (`WAIT_OR_PICKUP`) was live and unopposed, so a pause
    keypress waited the hero, spending a turn no recorded step asked for and diverging the replay.
    Playback now waits for the rebuilt level rather than reading a missing hero as an ending, and the
    key bindings are re-asserted every frame while the viewer is installed.

  - **A death recording did not play its last step.** The test for "the recording ends here" sat at
    the top of the drain, so it fired on the frame the final action was *applied* - the action went in
    and playback ended before the hero performed it. Three of the seventeen committed recordings end
    in death and all three stopped with the hero still standing. The check is now made after the
    drain has run and the hero has acted, so the recorded death actually happens on screen.

  - **"Turns" was a count of actions.** `Replay.turns` is `SPDEnv.turnsTotal()`, a count of the
    agent's decisions, while the engine's own clock is `Actor.now()` - a duration, and a fractional
    one, since a heavy weapon costs two turns and haste less than one. Calling the decision count
    "turns" invited exactly the reading that made the turn limit look like a budget of game turns.
    Both are now labelled for what they are, and the engine clock is shown alongside them.

  - **The viewer had no per-step score.** The recording carried a reward per step and the viewer showed
    only the run's final total, so nothing on screen could say which action cost a hundred points. The
    HUD now reports the running score, the last step's delta, and the gained and lost totals
    separately - a net figure cannot distinguish a run that climbed steadily from one that reached its
    high point and gave most of it back.

  - **Positions were a single number.** `heroPos` is `y * width + x`, and every divergence report
    printed the raw index. They are now reported as `(x, y)` with the raw index kept for comparison
    against a trace. x grows to the right and y grows downward, which is the engine's own order.

- **The viewer HUD ran off the right edge of the screen.** `BitmapText` cannot wrap, and cannot even
  break a line: its font has no newline glyph, so the `\n` the HUD has always used between its rows
  drew as a blank and every row kept running right until it left the window. Only the first row was
  readable, and the rest was not merely ugly but off-screen. It went unnoticed because the HUD was
  short enough to fit at most window sizes - adding the per-step score line above made the string
  longer than any ordinary window. The HUD is now a pool of one-line gizmos laid out against the UI
  camera, wrapped on spaces and measured with the real font, so a narrower window wraps instead of
  clipping. Worth noting what was never in doubt: no `BitmapText` anywhere in the game is given a
  `\n`, because the game's own multi-line component exists precisely because this one will not wrap.
  This one had been the exception.

- **The viewer and the trainer diverged on `cleric-mid`, and nothing had noticed.** Draining the last
  recorded step (above) made the viewer compare a step it had never compared before, and that
  recording immediately failed - seven turns and eight health away from what it says. The cause was a
  real gap: `ReplayPlayer`'s drain had none of the trainer's resting-stall backstop, so it sat out a
  rest that `LevelPipeline.runToHeroReady` had already given up on. `Hero.act()` handles a resting
  hero with no action by spending time and calling `next()` without ever becoming ready, so the viewer
  waited out the rest and reported the world the trainer had stopped before. The backstop is now
  ported, counting scheduler steps the way the trainer's does - as a field rather than a loop
  variable, because the viewer yields the frame and a local counter never reached the threshold.

  It was invisible for as long as it was because the step it affects was the last one, and the last one
  was skipped rather than drained.

### Added

- **The committed corpus is now a playback gate.** `playbackcheck` plays every recording in
  `replays/` and fails on any divergence, which is the headless half of `viewcheck` and can run where
  there is no display. It exists because every other case in that gate builds its own fixture, and a
  freshly recorded run does not end on a rest - which is how the divergence above survived.

- **Seven new checks in `playbackcheck`** (10 -> 18), covering the restart window, a death recording
  playing its final step, the gained/lost split being a real split, and positions decoding as
  coordinates. Mutation-tested: restoring the old drain ordering fails the death case, disarming the
  restart wait fails the rebuild case, flipping the y axis fails the coordinate case, and reporting the
  net instead of the gain half fails the score case.

- **One more check in `playbackcheck`** (18 -> 19), covering the HUD wrap. It measures with a fixed
  per-character width rather than the real font, because it runs headlessly where there is no font and
  every real measurement is zero - a check that could only pass is worse than no check. It asserts
  three separate properties: no line comes out wider than the limit, wrapping does not alter the
  content, and an explicit `\n` still forces a break. Mutation-tested by disabling the width test,
  which fails it.

- **One more check in `playbackcheck`** (19 -> 20), covering the HUD anchor: that the default is top
  left, that every corner places text inside the window, that a top anchor grows downward and a bottom
  anchor upward, that cycling reaches all four corners and returns, and that the help line stacks clear
  of the HUD at every corner. Asserted on the geometry rather than the drawn result, since the drawn
  result needs a window. Mutation-tested three ways — reverting the default, dropping `lineWidth` from
  the right-anchor arithmetic, and making top anchors grow upward each fail it.

- **`gradle :superintelligence:viewdiff`** (`:superintelligence`): reads a headless `worldtrace` snapshot
  and a rendered `-Dspd.worldTrace` one, and reports the first step and **field** that differ, plus the
  frames on which the world moved with no recorded step applied. This is the reader half of the only
  two-sided comparison in the project - every other check compares a headless run against a headless
  run, so a fault in the render loop cannot appear in any of them - and it replaces a hand-written
  comparator that had itself been wrong twice.

- **`# generationSites=` in every RNG trace:** the draw-by-site tally for the window between level
  generation and the first recorded step. It is the one part of a run the per-step comparison excludes,
  so it had nowhere to be looked at, and it is where an offset introduced before playback begins
  appears with nothing else to point at it.

- **`RandomTrace` frame and gate records:** which actor advanced engine time, by how much, on frames
  that applied no recorded step; and the step gate's own inputs at the moment it was consulted.

- **`PRandom.element`**, mirroring `Random.element` so a presentation caller can be moved across
  without reimplementing the indexing.


- **`viewcheck` gate** (`:desktop`): plays the whole committed corpus through the *real* viewer - real  `GameScene`, real `ReplayController` frame driver, real GL context with the window hidden - and fails
  if any recording diverges. One child JVM per recording. Needs a display, so it is deliberately not
  part of `gates`. It exists because `playbackcheck` cannot see this class of fault: it calls
  `ReplayPlayer.update` directly, whereas the viewer installs itself as `GameScene`'s frame driver.

- **`WorldSnapshot` and `WorldDiff`** (`superintelligence/.../replay/`): a per-step and **per-frame**
  record of the whole world - hero, full mob roster with AI state and three-state FOV, blobs, per-cell
  terrain with a fingerprint over the cells it omits, and path-qualified inventory including nested bags -
  plus a field-level diff that resolves a difference down to the coordinates. `worldtrace` is the
  headless producer. Every existing comparison in the suite covers four fields; this one says which mob
  moved and which cell changed, which is what turned "hp is 19, recording says 20" into an explanation.

- **`FrameDelta`** and `-Dspd.fixedDelta`: pins the viewer's playback pacing, so the number of frames
  between recorded steps is a function of the recording rather than of the machine. **It does not pin
  the animation clock** and no arrangement of writes from the frame driver can - `Game.elapsed` is
  derived in `Game.update` before any game code runs, so three separate approaches were tried and
  measured, and all of them leave two clocks disagreeing. The class documents the measurements.

- **`Actor.currentActor()`, `Mob.currentEnemy()`, `Mob.enemySeen()`, `Mob.alerted()` and
  `MovieClip.animationInFlight()`**: getters only. No game logic changed. The first three let an observer
  name which actor acted and on what; the last is the only way to ask whether an attack is still in
  flight, since `CharSprite.isMoving` is set by movement and by nothing else.


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
- **`PLAN-replay-verification.md`**, the plan for the tooling that is still missing, and the report of
  what validating its premises turned up. Three faults got past every existing gate because a gate that
  shares its implementation cannot see a fault in it: `collectcheck`, `verify` and the rest all
  re-execute through `HeadlessGame`, so a build-wide RNG fault is invisible to all of them by
  construction. `ReplayPlayer`'s 560 lines were covered by nothing at all. The proposal is four tools —
  a headless playback verifier, an observation-purity gate, a rendered-vs-headless RNG fingerprint, and
  one command that runs the lot — plus `maxSlots`/`allowEquipping` added to the replay header, without
  which a replay cannot guarantee it resolves slot indices the same way twice.
  Validating the premises confirmed every one of them, and turned up two things reading alone had not:
  - `ReplayPlayer` *can* be driven with no window, no scene and no `ReplayController` — a probe loaded
    a 229-step recording, built the world, applied steps and reported a divergence in 7 ms, so T1 needs
    no refactor to make the viewer headless-testable.
  - That same probe diverged at step 3 of a recording the windowed viewer plays to step 33, because
    `ReplayPlayer` never performs the `GameScene.clearPendingCellListener()` /
    `SlotAction.clearPendingUseItem()` that `SPDEnv.step` does. The windowed viewer masks this by
    having a live `CellSelector`; headless has none, so the aim request routes through
    `pendingCellListener` and the throw never resolves. The viewer and the trainer do not perform the
    same per-step state transition, and only this tool makes that visible.
  - `rollout --seed ""` writes `seed=` and `verify` then dies on an unhandled null-valued expression,
    five times over, naming neither the file nor the step — so the guard needs to go in both commands,
    not one.
  - Both guards are in. `rollout` refuses an empty `--seed` and points at the omitted flag; `verify`
    refuses a recording with no seed and names the file and the field.
- **`rngtrace`**, the first automated check that the rendered game and the headless environment consume
  randomness identically. Everything else in the suite compares a headless run with another headless
  run, so a fault in the headless path cannot show up in any of them. This compares against an artefact
  produced by the viewer, which is the only thing in the project capable of disagreeing.
  Per step it records the cumulative draw count *and* an order-sensitive fingerprint — a count alone
  cannot tell "the same numbers in a different order" from "the same numbers in the same order", and
  the first is just as broken. Counters start at zero once the world is built, so the trace measures
  steps rather than the several thousand draws that level generation makes; the observer contract is
  `ReplayIO.StepObserver`, so it reuses the verification loop rather than writing a second replay loop.
  Comparison is keyed by step rather than line position, so "first divergence" keeps meaning what it
  says when one run produces a different number of samples.
  Landed as a diagnostic rather than a gate, per the plan: any engine change that touches a draw
  invalidates a golden, and a gate people regenerate reflexively is worth less than one they read.
  **Correction.** An earlier note here claimed a 229-step recording agreed "on every step" between the
  viewer and the headless run, and called it the first independent confirmation that the two agree on
  randomness. That comparison drove `ReplayPlayer` *headlessly*, so it compared two headless paths — the
  viewer's logic against `ReplayIO.verify` — and not the rendered game against anything. It shows the
  viewer's control flow consumes randomness identically to the trainer's replay path; it says nothing
  about the renderer.
  The first genuine rendered comparison, through `gradlew :desktop:replay -PspdRngTrace`, is below.
- **`rollout` with no `--seed` works again, and a random-seed recording is now verifiable.** The empty-seed
  guard added earlier rejected the flag *and* its absence, so it told a user to omit `--seed` in order to
  reach the only path that then refused them — random seeds had become unreachable. The guard now
  distinguishes a flag given empty from a flag omitted, which is the distinction it was supposed to make.
  Separately, `rollout` recorded the seed it was *asked* for rather than the seed the environment
  *resolved*. On a random episode those differ: `SPDEnv` encodes the seed it drew into its own
  `seedText`, and recording the empty request instead produced a replay `verify` could never rebuild — it
  would draw a *different* random world and report a step-0 divergence that looks like a broken seed lock
  and is not one. `recorder.begin` now runs after the reset and is given `env.seedText()`. Measured: a
  no-seed run records `seed=RVN-SWK-ZVJ` and verifies; `--seed ""` and `--seed "   "` are still refused,
  with a message that now matches what it does; an explicit seed is unaffected.
- **The rendered game and the headless environment draw from different positions in the RNG stream.**
  Found by `rngtrace` on a 41-step recording that `verify` reproduces exactly: `per-step draw counts
  identical at every step - 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6 - but the order-sensitive fingerprints
  differing from step 6 onward`, and the windowed run eventually diverging on health at step 27 while
  the headless replay reproduced all 41 steps. Same count, different value is the signature of a stream
  offset, and it is exactly what a count-only oracle would report as identical: the two paths agree on
  *how many* values each step consumes and disagree on *which*.
  The offset is roughly 2800 values consumed by the rendered path between level generation and the first
  replayed step. Arming the trace at construction showed those values directly, and the counters now
  start at zero when the first step is applied - but that only fixes the measurement. The underlying
  draws are still made by the renderer and not by the headless path, so the two streams remain offset
  for the whole run. That is the next parity bug to chase, and the tool now points at it.
- **`playbackcheck` gained a check for non-default header settings, and both gates now count failed
  checks rather than failed assertions.** `max_slots` and `allow_equipping` had only ever been round
  tripped at their defaults, which cannot catch a writer that omits the fields: `32` and `true` are
  exactly the values the reader falls back to, so a header that wrote nothing would pass. Non-defaults
  are the only values that distinguish "written" from "not written and defaulted", so the fixture writes
  `max_slots=7` and `allow_equipping=false` and checks the parsed object, the reconstructed
  `EnvConfig`, and the file's own bytes. Mutation-tested by dropping the `allow_equipping` line.
  Separately, both gates reported `failures.size()` against a check count, so one check making three
  assertions announced itself as three failed checks - visible as "3 of 8 checks failed" when exactly one
  check had failed. They now report `N of M checks failed, K assertions`.
- **`gradlew verifyall`**, every gate in both modules in one invocation, ~14 s. It has to sit above the
  modules rather than inside one: `:desktop` depends on `:superintelligence`, so
  `:superintelligence:gates` cannot depend on `:desktop:playbackcheck` without a cycle. Freshness is a
  property of the graph rather than a check — both gate tasks take their classpath from
  `sourceSets.main.runtimeClasspath`, so gradle recompiles what changed before any gate runs, and there
  is no path by which these execute against a stale build. That is worth more than reporting whether
  they did, so the task deliberately prints no freshness claim it cannot verify.
- **`playbackcheck`**, a gate that runs `ReplayPlayer` with no window, no scene and no `ReplayController`,
  and asserts both halves: that a faithful recording plays clean, and that a recording altered in
  `heroPos`, `heroHp`, `turn` or `inventory` is caught *at the altered step* with a message describing
  the quantity that changed, that altered quickslot bindings never play clean, and that an unresolvable
  recording halts with a reason instead of hanging. 7 checks.
  `ReplayPlayer`'s ~560 lines decide what a recorded step means, derive the mode, drain the scheduler,
  work out whether a turn is owed, settle and compare against the recording, and until now the only
  thing that executed them was the viewer — which cannot be put in a test. All three faults that lived
  there were found by hand, by diffing traces.
  Mutation-tested: removing the `clearPendingCellListener` that F1 added fails it with the exact message
  the original probe produced — *"a faithful replay diverged at step 2: USE/0 in TARGETING: engine time is
  0.0, recording says 1.0"* — and the four field mutations then report step 2 instead of step 30, which
  is what proves the localisation check is doing work.
- **Known limitation, found by `rngtrace`: a run that ended on the soft stall guard replays as a
  stall.** `SPDEnv.checkFloorLimits` also terminates when the hero's position and health have not
  changed for `stallLimit` consecutive turns. The recording is complete and the hero is alive, but the
  viewer has no environment to ask, so after the final step it waits for a hero that will never become
  ready and reports "stalled - hero did not become ready within 400 turns" — false, and useless, since
  the recording was complete rather than truncated. The header records no termination reason, which is
  the actual gap. A guard mirroring the per-floor turn cap was written and removed: the stall guard
  fires first in practice, so nothing could exercise it, and shipping an unexercisable check would
  contradict the argument the rest of this work is built on. Fixing it properly means recording the
  termination reason in the header; see `PLAN-replay-verification.md` section 12.1.
- **`observecheck`**, a gate asserting that *reading* the world draws no randomness. One violation of
  this already cost a full parity investigation: `HeroEncoder` built its defence feature with
  `hero.drRoll()`, which is not a property of the hero but a fresh draw per call, so encoding an
  observation advanced the gameplay RNG twice per agent step and the stream stayed offset from the first
  floor. Nothing caught it, because every gate re-executes through `HeadlessGame` — a gate that shares
  its implementation with its subject cannot see a fault in it. This one measures the encoders directly.
  4 checks: that `ObservationEncoder.encode` and `Quickslots.capture` draw nothing, that a second encode
  of an unchanged world is byte-identical, and a positive control that draws on purpose and requires the
  counter to move — without which every "no draws" check would pass for the wrong reason if the
  instrument broke.
  Mutation-tested: restoring `hero.drRoll()` fails it with `drew 6 value(s)` and a differing hero vector.
  Backed by `RandomTrace`, a counter in `com.watabou.utils` that is off by default and instruments only
  `Random`. Presentation randomness lives on `PRandom`, so what it counts is exactly the draws the
  simulation is entitled to.
- **A recording now says how wide its slot head was.** Replay v2 carries `max_slots` and
  `allow_equipping`. Neither was recorded, and they are the only two settings `ActionMapper` reads: the
  slot head is a fixed-width window over the inventory, so a recorded index names a different item at a
  different width, and `allowEquipping` decides whether a USE equips a weapon or arms an aim. A replay
  that could not say which it was recorded under could not guarantee it resolved an index the same way
  twice, and every replay-based check inherited that ambiguity. `ReplayRecorder.begin` takes the
  config, `Main.rollout` and `Worker` pass theirs, and `ReplayIO.configFor` is the one place a header
  becomes settings, so the viewer, `verify` and the headless verifier cannot each pick their own.
  `ReplayPlayer` takes an `EnvConfig` rather than hardcoding one, for the same reason. v1 still parses,
  with defaults, so the existing tooling fixtures stay readable — though they were recorded against the
  RNG stream since repaired, and so reproduce nothing.


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



- **`paritycheck`, a replay-parity sweep across seeds in one process.** Records N seeds x M hero
  classes under the scripted policy, writes each recording, reads it back, verifies it, and then
  verifies every recording a second time after the whole sweep has run in between. The second pass is
  what a recording's own round trip cannot see: nothing has happened between the two verifications of
  one file, so a static that survived a reset has nothing to leak into. It is the gate that found the
  remains fault above.
- **`configcheck`, which asserts the configuration layer is capable of configuring anything.** The
  first check is a positive control: a deliberately wrong value must produce a wrong setting, or every
  other assertion would be satisfied by the compiled defaults alone by a binder that reads nothing.
  The rest prove every documented key reaches the setting it names, that the shipped file's values equal
  the compiled ones, that an unknown key warns rather than being absorbed, and that malformed or
  out-of-range values are refused with the key and the file named.
- **`RunState`, the one place that lists what a new run must not inherit.** Four statics: the armed aim
  listener, the headless dialog slot, the pending use item, and a dead hero's remains. `SPDEnv.reset`
  and the desktop replay viewer each cleared a subset of them by hand, which is precisely how the armed
  listener survived a reset and put every new episode straight into `TARGETING` - 4 of 10 recorded runs
  diverged at step 0, and each of those four verified cleanly on its own.
- **`docs/documentation.md`** - the Superintelligence module: architecture, the environment contract,
  configuration, the gate matrix, and what determinism does and does not guarantee.

### Changed

- **`viewcheck`'s `GATE_SPEED` of 40 was silently clamped to 16** by `ReplayPlayer.speed()`, in both the
  system property and the direct call. Any reasoning about the gate's speed was off by 2.5x.

- The documented cause of the viewer divergence was **wrong**, and is corrected in
  `superintelligence/PLAN-viewer-fidelity.md` §7. `GameScene.update` does not advance the world during
  playback - that branch is gated off by `Actor.manualScheduling`. A planned fix that would have added a
  mid-turn check to the step gate was **measured and refuted**: the viewer already waits, for 48
  consecutive frames while an attack animation resolves. The "real cause" this entry previously named -
  an attack resolving on the render thread while `HeadlessSprite` resolves it synchronously in the
  trainer, so the two order the same events differently - **is itself not established**; it was the
  reading that came before the measurements, and it predicts no measurement that then held. No cause is
  claimed for the divergence yet.

- **`superintelligence/FINDINGS-viewer-fidelity.md`**, an evidence file separate from the plan: the plan
  says what will be tried, the findings say what happened, and after four hypotheses in a row were
  refuted on running code, conflating the two is how the next one gets re-proposed. Seven items
  established, four refuted with the measurement that refuted them, five still open - and no gate is
  claimed to fix anything.

  The load-bearing measurement is that headless and viewer agree on all 19 world fields for 16
  consecutive steps and then diverge on the final action. Nothing accumulates; this is one ordering
  fault, not a drift, which narrows the search considerably and rules out every "it slowly goes wrong"
  explanation.

- **Each refuted hypothesis is kept with both what predicted it and what the run showed.** Four
  mechanisms were read out of the source and three were wrong: the alleged spinning in `Mob.act()`
  shows no cooldown churn, the animation gate already blocks for 48 consecutive frames so it was a
  no-op, and `Actor.current` is not the mob whose action coincides with the divergence. The fourth -
  gating on `cooldown() <= 0` - wedged a sleeping `Sentry@5`, which reads the same value, for 600
  frames applying zero steps. That experiment was reverted rather than committed; a committed tree has
  to build and pass `verifyall`, and the finding is more useful than the dead gate.


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


- **The learner and the trainer are split by responsibility, both of which were over the 500-line
  limit.** `PPO` was 687 lines and about a quarter of that was the thread pool and the gradient
  reduction; `ShardedUpdate` now owns the pool, the per-thread networks and their scratch. `Trainer`
  was 561 logical lines and about a third of that was the half that talks to worker processes;
  `TrainerWorkers` now owns the job record, the concurrent dispatch, the per-worker request/response,
  the replay decode and the parallel policy push.
  The boundary is drawn at the pipes. Everything that crosses one is in `TrainerWorkers`, so a field
  added to the frame has exactly one reader, and a caller never receives a worker's streams - the
  ordering between a request and its reply is what keeps two processes in step.
  The split removed a duplication that had already cost something: the serial and sharded paths each
  carried their own copy of the scale-clip-report tail, which is how two implementations come to report
  different numbers for the same gradient. Both now return a result and one method folds it in, so
  `parallelcheck`'s equality claim is structural rather than something re-established each time either
  path is edited.
  Also removed two private methods with no callers: `PPO.replay`, which `oneSample` duplicates inline,
  and `PPO.oldLogProbabilityFor`, which returned its first argument and ignored its second. The latter
  was listed in `TODO.md` section 5 as a known rough edge; it is now gone rather than listed.
- **`resetcheck` grew a case per leaked static** (3 checks -> 5). An outstanding dialog is now tested as
  well as an outstanding aim, and a run following a hero's death is tested for inheriting nothing. Each
  is mutation-tested: deleting the corresponding clear makes the case fail, and the remains case
  additionally fails if the clear is moved back to after level generation.
- **`testing-guide.md` names `gates` rather than `verifyall`**, which it documented and which does not
  exist, and its matrix lists the two new gates.
- **A pre-commit hook** runs the badge-consistency check and the full gate suite, and refuses the
  commit on failure. The badge check corrects `README.md` and re-stages it rather than aborting, because
  a one-line mechanical mismatch is not worth teaching someone to reach for `--no-verify`. It lives in
  `hooks/pre-commit` with an `install-hooks.ps1` to copy it into place, because `.git/hooks` is not
  tracked and a hook written there exists only on the machine that wrote it.

### Changed

- **Documentation corrected against the source.** Every stale claim in `superintelligence/*.md` and
  `docs/documentation.md` was re-derived from the code rather than trusted, and the ones that did not
  survive were fixed. Documentation only; no behavioural change.

  The corrections that would have misled a reader who went looking:

  - **Counts.** `gates` runs **19** gates and `verifyall` **20** (was 16 and 14). `PlaybackCheck` has
    **10** checks (was 7), `ObserveCheck` **6** (was 4). `ReplayPlayer` is **1064** lines (was ~560).
    The corpus is **17 recordings at header version 3** (was "5 files, version 1"), 2032 steps.
    `observecheck` enumerates **17** draw paths (was "eighteen"). The observation grid is **21** planes,
    not 20, which also moves the `Transition` figure from 50,564 B to 52,868 B. The metrics CSV is **29**
    columns wide (24 named plus 5 derived from `TerminateReason`).
  - **A method that does not exist.** `recoverStrandedHero` is in no Java source in the repo. The
    `CHANGELOG.md` mentions describing its removal are correct; three files read as though it were
    present and now do not.
  - **A class that does not exist.** Action masking is three methods on `ActionMapper`
    (`actionMask`, `targetMask`, `slotMask`), not an `ActionMask` type.
  - **Code that contradicted its own draft.** `review.md`'s Issue 1 sketch calls `hero.rest()` for `REST`
    and `spendConstant`/`busy` for `WAIT`; the shipped code sets `hero.resting = true` and calls
    `hero.next()` for both, and calling `next()` without a `curAction` is what hands control back.
  - **A dead gap presented as open.** The soft-stall termination gap in
    `PLAN-replay-verification.md` §12.1 is fixed - `Replay.termination` plus
    `checkDeclaredTerminationEndsPlayback` - and the section now says so while keeping the reasoning.
  - **A fixture that was never there.** `world-death.replay` does not exist; the death recordings are
    `warrior-death`, `huntress-mid` and `warrior-long`, all `termination=DEATH`.
  - **`recoverStrandedHero` and `Collections.shuffle`.** `Mob.holdAllies` still calls
    `Collections.shuffle` (`Mob.java:1782`) and is now recorded as a remaining gap in
    `ENGINE-CHANGES.md` §7 rather than being described as exhaustive in §3.
  - **Sound-pitch draws.** The number still on `Random` is **11**, not 34, and the sites are now
    enumerated. The neighbouring sites already on `PRandom` are what a partial conversion leaves behind.
  - **`FogOfWar.java`, `Alchemy.java` and the `tiles` package.** All three exist; `TODO.md` §7 reported
    them as absent. What is true is narrower - they are reached through `Level` and the scene layer
    rather than called directly.
  - **Stale line references.** Nine in `PLAN-replay-verification.md`, five in `PLAN-viewer-fidelity.md`,
    and the `ActionMapper` config reads, which were cited at `:308` and are at `:307`.

  Where a claim could not be reconciled against the artefacts still in the tree - `FINDINGS` E-1's
  recording name, which no longer resolves because the corpus was regenerated - that is recorded as an
  inconsistency rather than silently resolved.

### Fixed

- **The rendered viewer played the game with different randomness, so recordings that verified exactly
  headlessly diverged in the viewer.** 14 of the 17 committed recordings failed `viewcheck`, all
  deterministically at the same steps - and two of them reported a *different* field value on different
  runs of the same build, which is what finally made it a fault rather than a rounding difference.

  Four presentation draws were spending the gameplay RNG stream, and only the rendered game makes them:
  `CharSprite.link`'s random sprite facing (once per actor, and `HeadlessSprite` overrides `link()` so
  the trainer never drew it - 12 base-generator values per run), `AttackIndicator.checkEnemies`'s
  choice of highlight target, `Wand.staffFx`'s particle direction, and `MagesStaff`'s staff particle
  size jitter. Every damage roll, defence roll and mob decision after the first draw therefore came
  from a different point in the stream than the recording was made from. Located by computing the base
  generator's expected output from `scrambleSeed(Dungeon.seed)` and looking up where each side's first
  gameplay draw fell: headless at value #5, the viewer at #17, on a recording whose recorded seed is
  `warrior-long`.

  On that recording the hero's recorded `INTERACT` at step 13 became an attack that left the rat at 3/8
  in the trainer and killed it in the viewer - one roll differing by enough - and everything downstream
  of it, which is why 13 of the 14 reported health and one reported engine time.

  All four are moved to `PRandom`, the stream created for exactly this. `DungeonTileSheet.setupVariance`
  draws 962 times during generation but inside a `pushGenerator`/`popGenerator` pair that is discarded,
  so it cannot affect an outcome and was left alone rather than moved for symmetry.

- **The RNG trace could not see the draws that caused it.** `Random.Int(int, boolean)` and
  `Random.shuffle(List)` advanced the generator and never called `RandomTrace.record`; only `Float()`
  and `Long()` were counted. So `RngTrace` reported identical base-draw counts at every step of every
  recording while the two streams were sixteen values apart, and `PLAN-replay-parity.md` §1.4 concluded
  - correctly, given what it could see - that the stream was not offset. Five subsequent hypotheses
  about ordering were refuted by measurement before that was found. Both paths now record, and
  `shuffle` is routed through `Int` with its permutation unchanged.

- **`WorldDiff` never named the hero field that differed.** `WorldSnapshot`'s hero row joins its
  fields with spaces while every other row joins with tabs, and the comparator split on tab only, so
  every hero record parsed as one key: the comparison of the record carrying position, health and engine
  time was a whole-row compare reported as a difference in `pos`. Measured, and it changed the answer -
  the two sides differ at step 13 in exactly one field, `exp`, and the tool reported `pos` for a
  position that was identical in both.

- **`regenerate-corpus.ps1` appended the freshly compiled classes after the stale
  `:superintelligence` jar** on its classpath, so a jar silently shadowed the build the script had just
  made. It regenerated the corpus with old code and the gate then reported ten divergences that had
  nothing to do with the fault under test. The classes are prepended now.

- **`viewcheck` reported only a step index,** so two recordings that reached the same step with a
  different field value were indistinguishable from two that reached it identically. It prints the full
  frame accounting on every line now - frames, frames that applied a step, frames that did not, refused
  steps, and frames that advanced engine time.

- **`replay-viewer`'s answer to a recorded `MENU` step was against nothing.** `WindowBridge` read
  `GameScene.headlessWindow()`, which `GameScene.show` fills only when there is no scene, so in the
  rendered viewer it was always null - and `ReplayController.dismissUnanswerableWindow` deliberately
  exempts `WndOptions`, so the dialog would have stayed on screen forever. `WindowBridge` now reads
  `GameScene.answerableWindow()`, a new getter that prefers the live window. No committed recording
  contains a `MENU` step, so this was latent.

- **`ReplayRecorder.inventory()` ignored the contents of bags,** counting a `VelvetPouch` the same way
  whether it held anything or not. A recording could carry a different pouch from its own replay,
  compare equal, and diverge several steps later on a slot index resolved against different contents.
  It is path-qualified now, matching what `WorldSnapshot` has always walked. This changes the recorded
  string, so the corpus is regenerated; `regenerate-corpus.ps1` rebuilds and re-verifies all 17.

- **`ReplayPlayer` discarded whether the engine accepted a recorded step,** so a refused action - a
  move onto a non-adjacent cell, a slot index resolving to nothing - was indistinguishable from a step
  applied and then lost. It is counted and reported now.


- **The rendered viewer took over the desktop on every gate run.** Two settings were inherited from
  whatever preferences file happened to exist, and both default to values that break an unattended run:
  `SPDSettings.fullscreen()` defaults to `true`, so `DesktopPlatformSupport` called `setFullscreenMode`
  from the game's own `create()` - after the launcher's window configuration was complete, so no `-D`
  flag could stop it, and GLFW ignores its visibility hint for fullscreen windows. `SPDSettings.intro()`
  also defaults to `true`, and `Hunger.act()` returns early while it is set, which freezes the hero's
  hunger clock for the whole run; the trainer has always stated `intro(false)` explicitly, and the
  viewer inherited `false` from the developer's own settings by luck. Both are now stated rather than
  inherited, and `viewcheck` reads the window mode back from the live context and fails if a run went
  fullscreen, so this cannot regress silently. This is the third instance of the same class of fault -
  a setting only one side states - after quickslot bindings and scheduler tie-breaking.

- **`viewcheck` took six minutes and reported differently each run.** It now completes in ~90s, prints a
  progress bar, and captures child output so a failure's detail is reprinted rather than interleaved
  with sixteen other children. **Parallelism is now opt-in** (`VIEWCHECK_JOBS`, default 1): playback is
  timing sensitive, and at ten children the same recordings failed at *different steps* between runs,
  so the gate warns when it is not running one at a time. Two sequential runs now report the same
  fourteen recordings failing at the same fourteen steps.

- **`ReplayPlayer` frame counters reported zero whenever tracing was off**, because they sat after the
  snapshot's null check. The frame ratio is the one number that says how timing-dependent a playback is,
  so it has to be true on a normal run.


- The `readyToAct()` step-gate hypothesis was measured and **refuted** before implementing it. Recorded
  because a plan that predicted the wrong mechanism, and was caught, is worth more than one that was
  never questioned. `superintelligence/PLAN-viewer-fidelity.md` §4.

- ~~14 of 17 recordings still diverge in the rendered viewer, deterministically and at identical steps.~~
  **Superseded by the first entry in this section.** That count was correct when written and described a
  real, reproducible fault; it was caused by presentation draws on the gameplay RNG stream rather than
  by anything about the viewer's scheduling, and it is now zero of seventeen. The entry is left rather
  than deleted because the reasoning that produced it - a scheduler difference, proposed from reading
  and then measured four times - is what the current document exists to warn against.

- **Merged upstream v4.0.2** (10 commits from `00-Evan/shattered-pixel-dungeon`). Clean merge, no
  conflicts, and it touched no file in `:superintelligence`. Our own additions to the engine all
  survive: `Bones.clear()`, `PRandom`, `RandomTrace`, `Random.reseedBase`, and the headless hooks in
  `GameScene`.
  Upstream's changes are gameplay and presentation fixes - cursed wands of warding respecting ward
  spawning rules, an inside-map check on `updateOpenSpace`, health-bar assignment, projectile momentum
  for kunai and knives, crystal enchantment capping, a boss elemental crash, and a movement-shadow fix.
  Two of them are worth naming for this module specifically: the `updateOpenSpace` check and the
  statues rooms touch level generation, so they could have moved floor layouts.
  They did not, as far as anything here can tell: all 19 gates pass, the seven committed recordings
  still reproduce step for step, three fresh JVMs produce byte-identical rollouts, and a fixed-seed
  2-worker training run produces the same `weights.bin` hash and the same reported losses as before the
  merge - `policy=0.0126 value=376.5506` on generation 0, unchanged. That is evidence the merge did not
  perturb the simulation, though it is not a proof about floors no recording happens to visit.


- **`--out` did not move the checkpoint, or the metrics history.** Both are derived from the working
  directory, and both were derived in `TrainOptions`'s constructor - which runs before the command line
  is read. A run pointed at `--out D` put its replays in D and left a 43 MB `weights.bin` and a
  `metrics.csv` in the old directory, reporting the save to a path nobody had named. Half a run's output
  in the place that was asked for and half somewhere else, with nothing saying where the rest went - the
  same failure the trainer's replay-index record was added to fix, one layer up.
  Both are now re-derived after the flags are read, and only when nobody named them: an explicit
  `--save` or `--metrics` is a deliberate path and does not move. `configcheck` grew a case that asserts
  both halves, because a fix that re-derived them unconditionally would pass the first and introduce a
  quieter bug in its place.

- **A dead hero's remains were inherited by the next run, and 2 of 32 recordings stopped reproducing.**
  `Bones` holds a fallen hero's belongings in statics plus a `bones.dat` under the process's file root,
  and `RegularLevel.createItems` reads them to drop a `REMAINS` heap. That is the feature working: a
  player returning to a dungeon finds their stuff where they left it. For an environment playing
  thousands of independent runs in one process it means the world a run generates is a function of
  process history as well as of its seed.
  Found by the new `paritycheck`, which records a sweep of 8 seeds x 4 hero classes in one process and
  verifies every recording twice. Two recordings diverged, both on inventory, both on a remnant - one
  carrying a HUNTRESS's `BowFragment` where the replay had a ROGUE's `CloakScrap`, one the other way
  round. The recording and the replay were the same hero; only what had died in between differed.
  Every existing gate missed it, and the reason is the same in each: one episode per process, or a hero
  that never dies. `paritycheck` is the only gate that lets the hero die repeatedly.
  `Bones.clear()` is new and additive, clearing the statics only - nothing in the game calls it, since a
  normal playthrough wants the opposite. `RunState` now clears statics *and* the file, and does it
  **before** `startRun` rather than after: remains are read during level generation, so clearing them
  afterwards cleared them one floor too late. That ordering was the bug the first attempt at this fix
  had, and every other reset case passed while it was in place. `resetcheck` covers it, by killing a
  hero and asserting the next run's first floor carries none of it.
- **The configuration that decides a run's settings was hardcoded in five classes, and two of the
  values had to agree by coincidence.** `learningRate` was a field on both `Network` and `PPO`;
  `epochs`, `minibatchSize` and the sampling caps were on `PPO` and again on `TrainOptions`; the stall
  timeout was a literal in `WorkerPool` with a different default in `TrainOptions`. A hyperparameter
  sweep that has to edit source and recompile to try a value is a sweep nobody runs.
  Settings now load from a properties file (`--config`) or from `SPD_*` environment variables, which
  override the file, which overrides the compiled defaults. Command-line flags still win over all of
  it - someone who typed `--max-turns 400` meant 400 turns. `PpoHyperparameters` holds one copy of each
  learning value; `PPO` and `Trainer` keep their public fields, so the update path is untouched.
  A value that cannot be read is refused rather than defaulted, and an unknown key warns naming itself:
  a confidently-wrong configuration is worse than one that declines to load. `superintelligence.properties`
  ships with every key documented against its reason, and `configcheck` asserts it agrees with the
  compiled defaults in both directions - a key whose file value has drifted from the code, and a key
  the code gained that the file never learned about.

- **Sound-effect pitch was drawn from the gameplay stream.** Thirty-four `Sample.INSTANCE.play` calls
  computed their pitch argument with `Random.Float(...)`, so every footstep, parry and hit consumed
  randomness the simulation runs on. `Hero.move` draws one per step - the terrain it lands on decides
  which - so the count differed whenever the hero's footing differed, and the stream parted from
  there. The third instance of the same fault as the observation encoder and the particle system, and
  the pitch is exactly as cosmetic as either.
- **The viewer spent a turn on steps that owe none.** `SPDEnv.settle` checks whether the game is
  waiting on a cell before it reaches `runToHeroReady`, so a step that arms an aim - `USE` in `SLOT`
  or `INVENTORY` - costs no turn and the scheduler is not advanced. The viewer drained regardless, so a
  recording opening `USE / SLOT / TARGETING` had the trainer take no scheduler step at step 1 and the
  viewer take one with the hero.
  The question has to be answered from the recording, not from the live cell selector: the selector
  keeps its aim listener until `Hero.ready()` runs, which needs the very drain being skipped, so asking
  it reports "an aim is pending" on the step that consumes the aim - the mirror of the fault. The next
  recorded step's mode says the same thing, and the viewer has the recording.
  `readyToAct` also gained the `curAction == null` condition the trainer's READY test has always had.
  Testing `ready` alone walked through the window between injecting an action and the hero consuming
  it, applying the following step over the pending one.
- **The viewer skipped the trainer's per-step preamble, so a throw never landed.** `SPDEnv.step` opens
  every step by clearing `GameScene`'s aim listener and - unless the step is consuming an aim - the
  pending use item, then refreshes the quickslots, and only then dispatches. `ReplayPlayer` dispatched
  without any of it and refreshed the slots *afterwards*.
  The missing `clearPendingCellListener` is what it cost. `SlotAction.use` re-arms that listener when
  an item wants an aim, and `ActionMapper.resolveTarget` prefers it over the sprite-free `castAt`
  fallback. The trainer clears it every step, so trainer throws always fall back to `castAt`. The
  viewer kept it, so the throw went down the game's own missile path, which recycles a sprite from
  `hero.sprite.parent` - there is no parent without a scene, so the throw threw, no turn was spent and
  the hero kept the stone.
  Found by running `ReplayPlayer` with no window and no scene for the first time, which is only possible
  because `ReplayPlayer` turned out to have no UI dependency: 229-step `RF` reported *"USE/0 in
  TARGETING: engine time is 0.0, recording says 1.0"* at step 3, where the windowed viewer plays to 33.
  With the preamble mirrored it plays all 229 steps with no divergence, in 55 ms. The window hid this
  because it has a live `CellSelector` to aim with.
- **Four headless crashes on paths a longer run now reaches.** Once rollouts stopped dying early they
  got far enough to hit code that assumes a sprite exists. `Char.move`'s vertigo branch interrupted
  motion on a null sprite, as did `ShadowClone` and `ScrollOfTeleportation`; `SentryRoom` cast its
  sprite to `SentrySprite`, which no headless sprite is. Each is guarded at the visual call and none
  touches the gameplay beside it - vertigo still rolls its direction, the sentry still zaps, and the
  charge animation is simply skipped. Ten consecutive rollouts now record without a crash, on floors
  up to 1577 steps.

- **Presentation randomness was consuming the game's stream.** The particle system, emote icons, music
  selection, colour jitter, sewer ambience and sound-effect pitch all drew from `Random`, so a run that
  rendered consumed a different amount of randomness than one that did not - and headless never renders.
  Measured on a fresh recording, eight presentation sites still differed after the observation-encoder
  fault below was fixed, and they were the whole of the remaining offset: `WaterParticle.reset`,
  `ColorMath.random`, `Sink.update`, `WindParticle`, plus the emitters.
  Those draws now come from `PRandom`, a separate generator reseeded with the run seed, so a run stays
  as reproducible as it was while its outcome no longer depends on whether anything was drawn. That is
  the same mistake the observation encoder made, one layer down: using the gameplay stream for something
  that is not gameplay.
  `SewerLevel`'s two `Random.chances` calls are secret-door and exit placement and stay on the gameplay
  stream deliberately.
  With this, a 14-step floor draws within one call of identical in both environments, against 249 before.

- **Encoding an observation was consuming the game's randomness.** `HeroEncoder` built its defence
  feature with `hero.drRoll()`, and `drRoll` is not a property of the hero - it is a fresh
  `Random.NormalIntRange` on every call, drawn once for Barkskin, once for armour and once for the
  weapon. So reading state advanced the RNG stream, twice per agent step (once from `reset`, once from
  `settle`), and the offset was permanent: the trainer's stream never lined up with the game's again
  from the first floor, and no recording made since could replay. It was also a noise feature - two
  identical worlds encoded to different vectors, showing the agent a number that carried no
  information about the state it had to act on.
  The feature is now the roll's *ceiling*, each component taken at its maximum, which is deterministic,
  is a real property of the loadout, and leaves the game's randomness alone.
  This was the last RNG consumer in the encoder package, and it is what `collectcheck` was failing on:
  that check runs headless and so could not see a fault in the headless path. It passes now, and it was
  the only red gate.

- **Headless sprites had no particle emitter.** `HeadlessSprite.emitter()` returned null, but 150 call
  sites chain through it - `sprite.emitter().burst(...)` - so they bypassed the class's own no-op
  `burst` and dereferenced null instead. Equipping a cursed item, burning, imbues and healing all
  reached one, and a run died with a NullPointerException the first time it cursed an item.
  The stand-in draws: `Emitter.start` takes `Random.Float(interval)` as its emission delay, so those
  call sites consume the game's randomness in a real playthrough and a draw-free stand-in would have
  left the same permanent offset the encoder had. What it does not do is remember - a live `Emitter`
  retains the factory and count of the last request, which outlived a floor boundary and stopped the
  same seed rebuilding the same floor.

- **The viewer's drain yields to the render loop.** `ReplayPlayer.driveToHeroReady` spent up to 400
  scheduler steps inside one `GameScene.update()`, and `Hero.actAttack` returns without calling `next()` -
  the completion that calls `Hero.onAttackComplete` is an animation callback on the render loop, which
  cannot run until this loop returns. So `curAction` stayed `Attack` and every recording reported
  `stalled - hero did not become ready within 400 turns`.
  The drain now returns `PENDING` when a step spends no time and leaves the hero's action alone, which
  is the signature of waiting on the render clock, and `update()` retries on the next frame. Yielding is
  safe because `GameScene.update` runs its frame driver *before* `PixelScene.update`, so an early return
  does not suppress the animation tick. A genuine stall still ends the episode after the existing budget.
  `awaitingSettle` carries a step across the yield so `settle()` still runs exactly once per step, and
  the comparison it makes is not skipped by the frame boundary.
  `CharSprite.isMoving` is deliberately not the trigger: it is only set by `CharSprite.move`, never by
  `attack` or `operate`, so it cannot see a pending attack animation.

- **The trainer settled a turn before the game did.** `LevelPipeline.runToHeroReady` treated any idle,
  non-resting hero with no pending action as unrecoverable and assigned `Hero.ready = true` directly
  (`recoverStrandedHero`). The game has no such shortcut: `Hero.ready()` runs when the hero is next
  picked with a null `curAction`, so the wait ends on its own, one turn later.
  `Hero.actPickUp`'s success branch is the common case - it clears `curAction` at `Hero.java:1153`
  without calling `ready()`, where every other terminating path does (`actMove`, `actInteract`, and
  pickup's own failure branches). Forcing `ready` ended that turn early, so a recording claimed
  `Actor.now() == 4.0` where the game reached `5.0`, and the desktop viewer correctly reported
  `DIVERGED at step 8 - INTERACT/0 in WORLD: engine time is 5.0, recording says 4.0`.
  Instrumenting `Actor.headlessStep`, which both environments call, showed the two runs byte-identical
  through the hero's second pickup - `curAction=null ready=false heroT=5.0` - differing only by that
  assignment. It fired 3 times across 1310 recorded steps, so the error was small and real rather than
  cumulative: the mobs denied a turn at `t=4.0` act in *both* environments, and only the hero's
  readiness was short-circuited.
  The guard is now `!wantsMore && steps > 8 && Dungeon.hero.resting`. A resting hero is the one genuine
  one-way door - `Hero.act` spends time and calls `next()` but never `ready()` - and `ActionMapper.apply`
  already clears `resting` when a non-`REST` action is chosen, so `STALLED` stays reachable as a backstop
  and `Outcome.STEP_LIMIT` still bounds the rest.
  Three fresh recordings (222, 1589 and 1421 steps) now reproduce exactly through `ReplayIO.verify`,
  and the viewer no longer reports a turn divergence on any of them.
  **Existing recordings are void**, because the turns they recorded were early.

- **Headless crashed on a surprise attack.** With drains no longer truncated early, attacks actually
  resolved and reached `Mob.defenseProc` -> `Surprise.hit`, where `Effects.get` dereferenced
  `icon.texture` - the effects sheet is never decoded without a renderer - and `Surprise.hit(int, float)`
  dereferenced `Dungeon.hero.sprite.parent` and a `recycle` result that is null headless. Both now
  return early instead. Purely cosmetic paths: the frame only selects which part of the sheet to show,
  so a blank image loses no gameplay state, and behaviour is unchanged in the real game where neither
  value is ever null.

- **Every vertical direction was inverted.** `MOVE_N` carried `dy = +1`, so north walked south, and
  `MOVE_SE` / `MOVE_SW` walked north. Only the horizontal pair was right, which is why it survived: the
  two axes were written from opposite assumptions and neither was checked against the game's.
  The convention is the game's - `Level.pointToCell` is `x + y * width` and `CellSelector` gives north
  `(0,-1)`, so north must *decrease* the row index - and the offsets now follow it.
  Worth being precise about the damage, because it was less than it looks and more than it seems. The
  action space is complete and every cell is reachable, so an agent trained from scratch still learns
  to navigate; it just learns that the action it believes is north goes south. No loss curve moves and
  no metric changes. What it corrupts is every north or south claim about a recorded run, which is
  exactly the sort of thing that gets believed instead of checked - it is why a trace here showed
  `MOVE_N` stepping from row 40 to row 42.
  New `actioncheck` gate, 3 cases, mutation-verified: reinstating the old signs fails 2 of 3, with the
  empirical failure reading `MOVE_N is named N but moved S`.
  **This renames the meaning of 4 of 8 movement actions, so existing checkpoints and every recording
  made before it are void.** The checkpoints are cheap; the recordings are not, and the ones recorded
  under the old mapping will not reproduce.

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

- **The viewer fidelity fix is an engine change and is not in this commit.** Four presentation draws
  move from `Random` to `PRandom` — `CharSprite.link`, `AttackIndicator`, `Wand.staffFx` and
  `MagesStaff`'s staff particle — one line each, and none can change an outcome. `instructions.md`
  §12.3 keeps engine logic changes out of a commit until they are proposed, so they sit in the working
  tree. Consequence, stated so nobody has to infer it: **this commit passes `verifyall` and fails
  `viewcheck` 14 of 17.** Accept the change and the gate is green on all seventeen, stable across
  repeated runs; leave it and the tree reproduces the fault exactly as documented. Evidence is in
  `superintelligence/ISSUE-viewer-frame-drift.md`.

- **`viewcheck` is outside `gates` and stays there.** It needs a display, it forks a JVM per recording,
  and it costs about 200 seconds sequential. That is a real gap rather than a decision: this class of
  regression returns silently between runs of it.

- **A replay recorded before this change with an empty `seed=` cannot be verified.** `SeedPool`
  deliberately leaks 10% of episodes onto fully random seeds so the agent cannot memorise the locked
  set, and those episodes were recorded with the *requested* seed, which is empty for them. `verify`
  resets onto a fresh draw and reports a divergence at step 0 — correct behaviour, and a message that
  reads as a broken seed lock. Any such file already on disk stays unfixable; re-record it.

## [0.1.0] - 2026-10-06

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

<!--
No compare links, deliberately. The versions here are the module's and they are not git-tagged: the `v*`
tags in this repository belong to the game and come from upstream. The two link definitions this file
carried before the move pointed at `v4.0.1...v4.1.0`, a tag that has never existed, so they rendered as a
404 rather than as a range. Tagging the module would mean a `v`-prefixed series that collides with the
game's by name; the fix is a separate namespace (`si-v0.4.0`) and is not worth doing for a module whose
release cadence is its changelog.
-->
