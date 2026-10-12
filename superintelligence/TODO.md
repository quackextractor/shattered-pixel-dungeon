# Superintelligence TODO

Status of the work in [`docs.md`](docs.md) and [`research.md`](research.md), written against the
code as it stands. "Verified" means it was run and observed, not merely written.

Last updated: 2026-10-12, after `issues.md` "Training" 16 was closed. **A chasm fall was being dropped
entirely**, and nothing in the project had ever found out, because nothing in the project had ever put a
hero into one. A fall is the only floor transition that carries no `LevelTransition` — `Chasm.heroFall`
writes `InterlevelScene.mode` and asks for a scene switch, and nothing else — and
`LevelPipeline.handleTransition` tested `curTransition == null` before it tested the mode, which is true
on every fall by construction. So it answered "not a level transition", cleared the switch request, and
`Transition.FALL` and `handleFall()` were unreachable code. Walking a hero *at* a chasm threw a
`NullPointerException` out of `Chasm.heroJump` and out of the worker with it.

The second half took longer and was less visible: **the agent could never walk into a chasm at all**,
because the game's own "jump?" prompt gated both its option and its hide on a clock only a rendered
frame loop advances, and the environment discarded the dialog on any press regardless. Both are fixed, the first by `fallcheck` (5 cases) and the second by `dialogcheck` (3 cases),
all mutation-tested; §0.11 has the whole thing.

Last updated before that: 2026-10-11, after `issues.md` "Training" 15 was closed. **A recording the trainer writes
can now be played in the viewer, and `verify` says so instead of saying `[OK]`.** Four faults, found in
the order they had to be found: the trainer pinned a step's quickslot bindings *after* the step, so a
`DROP` — the one action that unbinds its own item — named a different item on playback than it did on the
recording; `ReplayIO.verify` resolved recorded slot indices against the live bindings rather than the
recorded ones and skipped the comparison entirely when the field was empty, which is why nine unplayable
recordings each reported success; `viewcheck`'s verdict was "did not diverge", so a player that halted
for any other reason scored a pass; and `Quickslots.restore` wiped bindings on equipped items, which no
slot index can name and which no recording can describe. §1.13 has the whole thing.

Last updated before that: 2026-10-10, after the reward function was re-priced around what the objective
actually is
(§1.8, closed below, and §1.9). The short version: **exploration was outbidding a descent**, which is why
`bestDepth` had been 1 in every generation of every run; killing a mob paid nothing while being hit cost
something; and the four terminal outcomes were priced so that two of them were cheaper than the run they
ended. `STALLED` is now worse than dying, `TURN_LIMIT` is worse than a death's worth of nothing, and
`SNEAK_ATTACK` is the largest combat payout because it is the only one that costs no health.

Last updated before that: after `issues.md` "Training" 13 was investigated — the stall is real but the
hero was looping rather than idling, and the recommended remedy of removing the stall would hide the
fault rather than fix it (§0.10). Two defects found alongside it are recorded in §0.8: `env.grid_height`
is documented and bound but only the encoder implements it, and none of the three geometry keys has a
range check.

Before that: the repository's documentation layout was split — the module's changelog and version line
moved out of the repository root and into this directory, and the module's series re-based from 4.x onto
**0.x** so it cannot be mistaken for a game release (§0.9). That work touched no code, so no gate result
is re-measured by it; `verifyall` was re-run afterwards and is green, and `:desktop:viewcheck` was re-run
and is 17 of 17. The entry before that — `issues.md` "Env" 6 and 7 investigated, 6 closed on measurement
— is at §0.8.

---

## 0.11 The "jump?" prompt the environment could not answer - FIXED

Found while closing `issues.md` 16, and the remaining half of that path. **The agent could never walk
into a chasm.** It was the environment's own question being asked and then thrown away, in two halves
that each had to be fixed before the other one mattered.

### The question was never aged

A hero who walks toward a chasm makes the game ask. `Hero.getCloser` finds the next cell is a pit and
calls `Chasm.heroJump`, which raises a `WndOptions` — "you are about to jump into a chasm, are you
sure" — through `Game.runOnRenderThread`, which the headless backend runs inline, which parks the window
in `GameScene`'s headless slot, which is what `WindowBridge.open()` reads. So the environment *did* get
the game's own question, and the agent was put into `MENU` with two options.

**The answer was then discarded, by the game's own code.** `Chasm`'s dialog overrides `onSelect` with

```java
if (index == 0 && elapsed > 0.2f){ ... jumpConfirmed = true; hero.resume(); }
```

where `elapsed` accumulates `Game.elapsed` in the window's `update()`. `PixelScene.update()` is the only
thing that ever calls it, and there is no `PixelScene` headlessly — so `elapsed` stayed at zero, the
option did not fire, and the hero stood where he was.

`WindowBridge.age()` calls it, **once per environment step, which is once per frame** — the same
approximation `ReplayPlayer` already makes about a recorded step. The gate is therefore the game's own
0.2 s rather than something invented here, and measured on `DIALOGCHECK-A` the answer takes **13 steps**.

**Those steps are not free, and an earlier version of this entry said they were.** Measured, on
`PROBE-A`:

```
13 answer steps:  turns 1 -> 3,  score -1.00 -> -15.00
                  NO_PROGRESS -12.00,  TURN_COST -3.00
```

`settle` returns before it scores a turn, so the 12 *refused* steps cost nothing — but the step that
**accepts** the answer resumes the hero's interrupted move, and that turn is scored as a turn which
advanced no engine time. **The real cost of one crossing is -15.00, not zero.** It is still cheap against
a descent at +1800.00, which is what makes it worth having rather than what makes it free.

Both answers measured, same run:

```
accept  (option 0): 13 steps, -15.00, then depth 1 -> 2 and +1800.00 DEPTH_ADVANCE
decline (option 1): 13 steps, -15.00, then four more waits for -67.00 and depth 1
```

Declining is neither a no-op nor a free retreat: `onSelect(1)` is empty, so the hero is left where he
was with the chasm still there — measured, he walks off it — but the 13 steps and the -15.00 are spent
either way.

### The question was then taken away

`WindowBridge.selectOption` cleared the parked window on any press, so a dialog whose handler declined
the click was reported as answered and removed anyway — and `Chasm`'s declines exactly as long as
`elapsed` is under 0.2 s. A parked window now lives in a one-slot `Group` rather than in a bare field,
so the game's own `Window.hide()` erases itself from its parent and closes the slot, and a handler that
declines leaves the dialog up. `GameScene.answerableWindow()` had to read the accessor rather than the
field for the same reason: a dialog the game had hidden was still being reported as answerable.

Uniform rather than special-cased: every dialog the game parks is aged the same way, and the ones with
no timing guard simply accrue a counter nothing reads.

### And a dialog opened mid-turn was reported a turn late

`settle()` only tested for a dialog at the top of its loop, so one raised from inside `Hero.act()` — the
jump prompt is — was noticed on the *next* step. The agent was handed the world for one more turn with a
modal question already up, and could spend it walking away from the chasm it had just asked about. A
rendered game blocks cell input the instant the window appears. `settle` now reports one before handing
back.

### Gated

`gradle :superintelligence:dialogcheck`, three cases, mutation-tested four ways:

| reverted | cases that refuse it |
| --- | --- |
| `WindowBridge.selectOption` clearing the slot again | 2 of 3 |
| `GameScene.headlessWindow()` ignoring the game's own `hide()` | 3 of 3 |
| `settle` not reporting a dialog the drain opened | 1 of 3 |
| `WindowBridge.age()` neutered | 2 of 3 |

The second case's fixture is `Chasm`'s shape rather than the chasm itself — a dialog whose `hide()` and
`onSelect` are both gated on its own elapsed clock — so the property is asserted against the shape rather
than against the one place it was found, and its second half is the positive control: without something
that closes the dialog, "no dialog" would be true of every press and the case would pass for the wrong
reason.

Measured, end to end: **the prompt opens, the agent answers it, the game accepts, the dialog closes
itself, and the next move puts the hero a floor down — `DIALOGCHECK-A`, 13 steps for the answer, depth
1 → 2.**

### What is still not right, and is now a much smaller thing than it was

**`Chasm.jumpConfirmed` is a one-shot, so a second chasm asks again.** Measured on `PROBE-A`: after one
accepted crossing and the fall that followed it (`jc=false` — `Chasm.heroFall` clears it, line 102), the
next chasm on depth 2 put the environment straight back into `MENU` with the game's question open. That
is the game's own behaviour and it was not changed; it is named here because it is a per-crossing cost
rather than a once-per-run one, and the -15.00 above is per crossing for the same reason.

**`Levitation.detach()` is guarded on a `GameScene`, and the pipeline now makes the decision that guard
was suppressing.** Measured: the hero stood on a chasm cell at 391 with `flying` false and 20 health on
depth 1 and stayed there for the rest of the episode — a state the game cannot produce.

The fix is supervisor-side and calls the game's own `Chasm.heroFall`, so the landing cell, the damage and
the fall flag all stay the game's; what is added is only the decision the game would have made inside a
renderer. It sits at the top of `LevelPipeline.runToHeroReady`'s drain rather than after a scheduler
step, because the buff ends *inside* `Actor.headlessStep()` and the following iteration is the one where
the hero is grounded on a chasm with the drain still running — checking only after a step misses it,
since the hero becomes ready on that same step. Two guards, both the game's own preconditions: the hero
has to be on the level being pressed, and off a `FeatherBuff` — an elixir of feather fall is the one
state in which `Chasm.heroLand` returns early and a hero is *meant* to be standing on a chasm.

**`Level.drop`'s `pressCell` has the same guard, and there is no supervisor-side fix.** A thrown or
dropped item presses nothing headlessly, so a stone lands on a trap and does nothing to it — measured,
an armed `WornDartTrap` still armed after a `Food` was dropped on it on four seeds out of four. `drop` is
called from a dozen places in the item tree, none of them ours, so pressing the cell from the
environment would mean intercepting every call site.

**I changed the guard anyway, and was wrong.** Dropping the `scene() instanceof GameScene` half looked
safe because the `Dungeon.level != null` half genuinely does keep level generation from pressing. But
the scene half is not redundant — it separates *gameplay* drops from *scene* drops, and two scene drops
are deliberately not gameplay drops:

- `AlchemyScene.java:761,864,1003` drops a crafted item on `Dungeon.hero.pos` — the cell the hero is
  standing on — so pressing would trample the tall grass under him (`Level.java:1282`).
- `InterlevelScene.java:815` drops the LostBackpack at `invPos`, the cell the hero died on, while the
  lines immediately above deliberately choose a respawn cell with no trap or plant on it.

Neither pressed before; both would have. Reverted.

`presscheck` is the gate for the divergence and is **out of `gates` and expected-red** until a fix is
chosen — `ENGINE-CHANGES.md` §7 has the three options. Worth being precise about what is *not* broken:
traps still fire when a hero **walks** onto one, because `Level.occupyCell` presses unconditionally and
is never scene-guarded. Only thrown and dropped items are inert.

---

## 0.9 The repository versioned one thing it has no right to version - FIXED

The repository had a root `CHANGELOG.md` running 4.1.0 → 4.3.3 and a root `README.md` badge reading
**4.3.3**, over a game pinned at upstream **v4.0.2**. Two incompatible answers to "what version is
this", in the two files a reader opens first.

**Upstream has never shipped a root changelog.** `00-Evan/shattered-pixel-dungeon` at `v4.0.2` has no
such file — its release notes live in `metadata/en-US/changelogs/`, one text file per version code.
Every version in that changelog was therefore *this fork asserting a game release it did not make*, and
the number had drifted 3 minor versions past the game it claimed to describe.

### What changed

| | Before | After |
| --- | --- | --- |
| Game version | 4.3.3, in a changelog that did not exist upstream | **4.0.2**, the newest `v*` tag |
| Module version | shared the game's series, at 4.x | **0.4.0**, its own line in `superintelligence/CHANGELOG.md`, starting at 0 |
| Changelog | `CHANGELOG.md` (root) | `superintelligence/CHANGELOG.md` |
| Root `README.md` | one badge | two badges: `version-4.0.2` and `superintelligence-0.4.0`, plus the module link that was already there |

Moved with `git mv`, so `git log --follow CHANGELOG.md` reaches the whole history through the rename.
Nothing was trimmed: below the new 0.4.0 entry the file is byte-identical apart from the six release
headings, the two in-text cross-references that cited them, and a header that says which subject it
versions and why.

**The module's series starts at 0, and that was the right call for a reason beyond tidiness.** At
`4.4.0` it sat in the same numeric neighbourhood as a `4.0.2` game — close enough that a reader
meeting both badges in one README could reasonably assume one line with a fork applied to it. Nothing
about the module's work is comparable to a game release, so a shared-looking number was a false
affinity. At `0.4.0` there is none.

**What the renumbering did and did not change.** The old numbers were already classified correctly as
major/minor/patch — the check was to confirm that, not to assume it, and the per-release reasoning is
in `CHANGELOG.md` 0.4.0. Every version moves by exactly −4.0, which is what makes the exercise a
verification rather than a judgement call. Two things follow:

- **No release becomes a major.** Nothing in the module's history changed a caller's meaning in a way
  that was not already announced as a fix or a feature, so the series can begin at `0.1.0` rather than
  at a `0.0.1` that would imply a release before any release existed.
- **Being pre-1.0 is a statement about stability, not about the digits.** A `0.x.y` carries no
  compatibility promise, so `0.3.0` was free to re-price `STALLED` from zero to the death penalty and
  invalidate the recording corpus — and did. What the `0.x` line prevents is `major` happening by
  accident: a breaking change here should be a minor bump on a line that already says nothing is
  promised, rather than a `5.0.0` that would read as an event.

**The game badge is now derived rather than written.** It is checked against the newest `v*` git tag,
which *is* the game's version — so an upstream merge brings the readme into agreement without anyone
editing it, and nothing hand-written can outvote it. That is the property the old badge lacked: it was a
literal that only moved when somebody remembered.

### The part that would have bitten quietly

`hooks/pre-commit` compared `README.md`'s badge to `CHANGELOG.md`'s newest heading. Left unchanged
after the split, it reads the **game's** badge and the **module's** changelog, finds `4.0.2` against
the module's release, and rewrites the game's readme to carry the module's version — then reports
`[OK]`. A check that writes the wrong answer and says it succeeded is worse than a check that fails,
because the failure it is meant to catch is the one it is now manufacturing.

The hook now reads each badge from its own file against its own source: game against the tag, both
module badges against `superintelligence/CHANGELOG.md`. All of them still correct-and-re-stage rather
than abort, which is what this hook has always done and why a one-line mismatch never teaches anyone to
reach for `--no-verify`. With no tags in the clone the game check skips with a warning instead of
guessing.

`hooks/pre-push` came along because the viewer was re-run for this entry and it no longer matched
what it printed. It announced *"divergences here are EXPECTED while ISSUE-viewer-frame-drift.md is
open"* and justified its non-blocking exit by the gate failing 7-9 of 17. The issue is fixed and the
gate is green. Corrected: the header states the measured state, and the real reason for not blocking is
that it needs a display. Left non-blocking rather than made blocking, because that is a behaviour change
nobody asked for and a pre-push hook that fails on a build machine gets deleted.

### Verification

- `verifyall` green after the change — `:superintelligence:gates` at 20, `:desktop:playbackcheck` at 21.
- `:desktop:viewcheck` re-run, not carried over: **17 of 17**, 219.7 s. The "17 of 17" every other entry
  in this file quotes is therefore measured rather than inherited, which is the claim the previous entry
  (§0.8) could not make — it never re-ran it because it needs a display.
- Root `README.md` diffed against `git show v4.0.2:README.md`: the game adds three lines and changes
  nothing else.
- Both badges re-derived by the new hook logic and matching; the derivation was run directly before the
  gates rather than only through a commit.

---

## 0.6 Watching a recording left a dead hero's belongings in the player's game - FIXED

Reported while reviewing the issue-10 work: the viewer creates `bones.dat`. It does, and it is worse than
untidy.

A recording that ends in death kills the hero for real. `Bones.leave()` then writes `bones.dat` into the
process's file root exactly as it would for a playthrough, and **that file is read during level
generation** — so the next run a player starts opens on a heap of belongings belonging to a hero who only
ever existed inside a recording. Three recordings in the corpus end in death, so this is the normal case
rather than an edge. Measured: `duelist-mid` played in the viewer left a `bones.dat` in
`%APPDATA%\.shatteredpixel\Shattered Pixel Dungeon` — the player's own profile, not anything scratch.

Two faults, either sufficient on its own:

1. **`Bones.clear()` is in-memory only**, and says so itself: *"Any `bones.dat` on disk is untouched, so a
   caller that also needs to forget a remains file written by an earlier process must remove that
   separately."* That caller was `RunState`.
2. **`RunState` removed the file only when `Gdx.files instanceof HeadlessFiles`** — true under the
   trainer, false under the viewer, which runs on the real backend. So the one caller running against a
   real file root deleted nothing, silently, without reporting that it had not.

The obvious replacement — `Gdx.files.local(...)` — is wrong in both directions, which is worth recording
because it looks right. `ReplayLauncher` calls `FileUtils.setDefaultFileProperties`, so `Bones` writes
through `FileUtils`, which resolves to `External` on a normal launch and `Absolute` under
`-Dspd.fileRoot`. The delete now goes through `FileUtils.getFileHandle`, the same resolver the write uses.

The viewer half is a separate fault with its own subtlety. The cleanup belongs where playback *ends*, and
the only place that is `halt()` — not the frame after it, because a headless driver stops calling `update`
the moment playback stops, and a quit never reaches another frame at all. A cleanup on the next frame
would have passed every rendered run and never once run in a gate.

Gated by `playbackcheck` case 21: play a committed death recording, assert no remains file survives.
Mutation-tested; removing the `halt()` call fails it. The case states what it does **not** cover — it runs
on the headless backend, so it exercises the branch that already worked and cannot reach the branch that
was broken. The viewer-side half is guarded; the backend half has no headless oracle, which is worth
saying rather than papering over with a case that appears to cover it.

---

## 0.8 The inventory the agent can address is narrower than the inventory the game gives it - RECORDED, not fixed

`issues.md` "Env" 7 reports that the inventory slots seem wrong, quoting a ceiling of 96. The ceiling is
right and the shipped `env.max_slots=32` is not. Nothing is fixed here; this entry records what was
measured and what the alternatives cost, because the fix is not the obvious one and the obvious one is
the expensive one.

### What the engine actually offers

Bags are obtainable and are already in every run, which is worth stating because the report reads as
though they were not: `HeroClass.java:111` hands every hero a `VelvetPouch` at run start, `ShopRoom.java:361`
(`ChooseBag`) stocks the other three behind a `Dungeon.LimitedDrops` one-shot each, and
`SandalsOfNature`, `MagesStaff`, `UnstableSpellbook`, `LiquidMetal`, `ArcaneResin` and `Dart` all return
a bag when they upgrade. All 17 committed recordings carry a `VelvetPouch`.

- `Bag.capacity()` is 20 (`Bag.java:55`).
- `Belongings.Backpack.capacity()` is 20 **+ 1 per bag held** (`Belongings.java:58`), so 24 with all four.
- Each bag is 19 (`VelvetPouch:47`, `ScrollHolder:49`, `PotionBandolier:45`, `MagicalHolster:48`).
- `ActionMapper.refreshSlots` iterates `hero().belongings.backpack`, and `Bag`'s iterator **recurses into
  nested bags** (`Bag.java:232`), so the whole tree is reachable in principle: 24 + 4x19 = 100 positions,
  of which 4 are the containers themselves, giving the 96 of the quote.

### What the agent gets

Measured on a hero holding all four bags, filled with non-stackable items each bag accepts:

```
backpack.capacity() 4 bags total = 24
items reachable iterating backpack = 68
config.maxSlots                     = 32
items the slot head can address     = 32
items the agent can never touch      = 36
```

68 is what this fixture reached, not the ceiling — it needed ~40 item prototypes to fill the bags, and
`Bag.canHold` filters by category so a bag that refuses a wand must be filled with something else. The
ceiling is 96. Either way `maxSlots=32` truncates.

**And it truncates silently.** `ActionMapper.java:91` does `slots.indexOf(null)`, gets -1 when the window
is full, and drops the item with no return value, no log and no flag. `mapper.slot(35)` returns null and
`SlotAction.execute` returns false on null. So the agent cannot see, use or drop those items, and the
`slotMask` reports every slot as legitimately full — there is no way to tell "I have nothing there" from
"there are 36 items I was never shown".

The properties file asserts the opposite: *"Backpack capacity is 20 plus one per bag, so 32 covers a
fully-loaded hero with room to spare."* Measured, that is false, and the false claim is the only place
the number was ever justified.

### The obvious fix, and what it costs

Raising `maxSlots` is not one array. It fans into `Network.java:89` (`extraInputs = maxSlots * 14 +
HeroEncoder.FEATURES`, feeding `trunk = new Dense(convFeatures + extraInputs, 256)`) and
`Network.java:101` (`slotHead = new Dense(128, maxSlots)`), and into `TransitionCodec.java:48` on the wire.

| width | input | +params | wire/step | 17 recordings |
| --- | --- | --- | --- | --- |
| 32 (now) | 448 | — | baseline | all verify |
| 64 | 896 | ~120k | +3.5% | all verify, replayed at 32 |
| 96 | 1344 | ~238k | +7% | all verify, replayed at 32 |

The +238k at 96 is larger than the entire convolution stack. `max_slots` is recorded in every replay
header, so recordings keep verifying at their own width — my earlier claim that raising it "invalidates
every recording's header" was wrong. What it does break is **cross-width playback**, which nothing in the
suite tests: a 96-slot recording replayed at 32 silently loses slots 32-95. `Checkpoint.java:221` refuses
a mismatched width by name, so training fails loudly; recordings are the soft failure.

The deeper objection is that 96 is a bad default for a different reason: it needs four shop visits and a
favourable `ChooseBag` ordering. A hero starts with a `VelvetPouch`, a `Waterskin`, a `Food`, one
`ScrollOfIdentify` and one weapon — six items. 32 already covers that five times over, so the width is
bought entirely for an endgame state at the cost of every early turn.

### Why the design does not achieve what D7 claims

`TODO.md` D7 justifies the separate slot head with *"Keeping slot out of the flat action enum means output
width does not change with the hero's carrying capacity."* That is true of the **action** head. It is
false of the **slot** head, whose output width *is* `maxSlots` (`Network.java:101`), and false of the
input width, which is `maxSlots * 14`. The capacity-independence D7 claims is not present in the
implementation. That is the actual defect behind issue 7, and it is why "just raise the number" treats
the symptom.

`research.md:47` mandates the flattened per-slot vector, but that paragraph is the weakest-specified claim
in the document — it asserts "neural networks require consistent input sizes" and stops. That is a
constraint on the first dense layer's input width, not on what may be fed into the network. The document
picks one implementation that satisfies it and reads the choice as forced.

### The alternatives, and what each costs

- **Set encoding — one shared vector per item, pooled.** A small shared MLP maps each item's 14 features
  to one 32-dim vector; mean/max/attention pooling reduces the set to a fixed summary. `maxSlots` leaves
  the network entirely, the trunk input *shrinks* to ~55, and the encoding is capacity-invariant by
  construction. Two costs are real: pooling is permutation-invariant, so it partly destroys the
  per-slot positional structure `research.md:31`'s memory requirement depends on ("remember that it left a
  health potion three rooms behind"); and the slot head becomes an attention read-out, which re-opens
  exactly the "which item did slot 12 mean" ambiguity that `slotcheck` and the quickslot divergence in
  `ENGINE-CHANGES.md` §1 were built to close.
- **Attention over learned per-item tokens, slot head preserved.** Keeps identity — attention weights *are*
  the slot choice — and is the highest-fidelity option. Also the largest: it touches `PPO`'s log-prob path,
  `TransitionCodec`'s wire format, the mask plumbing, and every gate that builds a fixture.
- **Bag-aware slots: keep 32, add an `OPEN_BAG` action** giving a nested view of a specific bag. 24 + 4 =
  28 addressable positions, inside the current window. Bags stop being invisible and become a navigation
  step, which is also how a human plays. Smallest change that makes the current architecture honest, and
  it keeps the slot head, the replay format and every existing gate.

### The environment's grid keys are not validated, and one of them is not implemented

Found while closing `issues.md` 6, and recorded separately because it is a defect rather than a
limitation: **both halves are fixable, and neither is a design question.**

**`env.grid_height` is documented and bound, and only the encoder implements it.** It is listed in
`superintelligence.properties`, read by `EnvConfigBinder.java:233`, and stored on `EnvConfig`. But
`ObservationEncoder.java:57,60,61` is the only place that uses it, and it uses it as
`gridWidth * gridHeight`. Every other consumer assumes a square: `Network.java:93` constructs
`new Conv2D(channels, config.gridWidth, 24, 4, 2, rng)` — `Conv2D` is square by construction, with an
im2col scratch of `(kernel*kernel*inChannels) x (outSize*outSize)` — and `Network.java:129`,
`TransitionCodec.java:47,159`, `PPO.java:156`, `EpisodeCollector.java:138`, `GradientCheck:72`,
`ParallelFixtures:50`, `GaeCheck:488` and `ReplayProbe:54` all compute `gridWidth * gridWidth`.

So `env.grid_height=64` at the shipped `env.grid_width=48` allocates a 48x64 encoder feeding a network
built for 48x48. Harmless today only because both are 48 — which is exactly the failure mode of a key
that has never been set to anything but its default. Two honest options: implement the rectangle
throughout (`Conv2D` would need generalising, and it is the im2col layout that decides whether that is
cheap), or **refuse a non-square pair and say so in the binder**, which is a small change and removes the
silent path. The second is worth doing regardless of what happens with the first.

**None of the three geometry keys has a range check.** `EnvConfig` has no `validate()` method at all, and
`PpoHyperparameters.validate()` (`PpoHyperparameters.java:116`) covers only `rl.*`, so `EnvConfigBinder`
does exactly one validation call (`EnvConfigBinder.java:214`) and it is for the PPO half.
`env.max_slots=-1`, `env.grid_width=0` and `env.grid_height=-5` are all accepted and fail later and far
from their cause: `ObservationEncoder.java:57` throws `NegativeArraySizeException`, `Conv2D.java:49`
computes `outSize = (inSize - kernel)/stride + 1` which goes **negative** for `gridWidth < 3` rather than
throwing something legible, and `ActionMapper.java:66` builds a zero-length slot list whose `slotMask` is
then uniformly zero.

`ConfigCheck` has sentinels for these keys (`ConfigCheck.java:528-530`) but only to prove the binder
*reaches* them — its out-of-range refusals (`ConfigCheck.java:344-347`) cover `rl.*` and parse failures
only. A sentinel that proves a key is read is not a check that the value is usable.

Two related dead ends worth noting so nobody re-derives them: `ObservationEncoder.java:116` computes a
`float scale` that is never read (the clamp is done per-pixel at `:126-132`), and the properties comment
describes the window as covering "the hero's 8-tile view radius", which does not correspond to
`MARGIN = 2` — the actual half-window is 26 tiles. The bound that matters is `level.width()`/`height()`.

### Why it is not being fixed yet

Sequencing, not caution. `TODO.md` 1.4 is the blocker: nothing has ever left floor 1, and
`duelist-mid` shows a policy that found a *zero-cost infinite loop* rather than one that mismanaged a full
inventory. There is no evidence inventory management is the bottleneck, so re-architecting the
observation before anything trains risks optimising the one thing never measured as important.

The cheapest honest step that is not a re-architecture is an **overflow signal** — report on every
`refreshSlots` that items were dropped, so truncation is visible rather than silent. That is worth doing
whatever the width ends up being, because at *any* fixed width the same truncation returns the moment a
hero carries more than the window.

---

## 1.11 The agent levels up and can never spend the point - REWARD DONE, ACTION MISSING

Found while checking what a talent does, prompted by the question of whether the hero grows at all.

### The two halves, and only one of them is true

**Levelling up: works.** `Mob.destroy` calls `Dungeon.hero.earnExp`, whose `while (exp >= maxExp())`
loop at `Hero.java:2059` raises `lvl`, `attackSkill`, `defenseSkill` and calls `updateHT(true)`. Every
kill levels the hero. `RewardModel` pays `LEVEL_UP` and `HeroEncoder` feeds `F_LEVEL` and
`F_EXP_FRACTION` to the network.

**Spending the point: impossible.** `Hero.upgradeTalent` exists, is public, and works. **Nothing
headless calls it.** The only path in the game is `TalentButton.upgradeTalent()` →
`TalentsPane` → `WndHero.TalentsTab`, and `WindowBridge.open()` returns true only for `WndOptions`.
There is no `Action` that reaches any of it.

### This is not an endgame gap

`Talent.tierLevelThresholds` is `{0, 2, 7, 13, 21, 31}`, and `Hero.talentPointsAvailable(1)` is
`1 + lvl - 2 - spent`. **At level 2 the hero has a point.** That is two rats on floor 1 — inside the
first floor, in the range the agent already operates in. Every talent point the hero has ever earned,
in every run and every recording in `test-replays/`, has been discarded.

### And it is the largest combat lever in the game

`hasTalent(...)` is false everywhere until a point is spent, and it is threaded through
`Hero.accuracyRoll`, `defenseRoll`, `speed()`, and dozens of buff procs. The choices are not
interchangeable: `LIQUID_AGILITY` 2 is **infinite evasion**, `VETERANS_INTUITION` 2 identifies the
hero's armour the moment it is taken, `GROWING_POWER` 1 is +75% speed. `Talent.onTalentUpgraded` fires
side effects on pick.

**It is also invisible.** `HeroEncoder.FEATURES` is 23 and none of them carry talent state, so the
agent cannot see that a point exists, let alone what it buys.

### What is done: the reward, so the action has something to pay

`RewardTerm.TALENT_UPGRADE` at `reward.talent_reward=20.0`, emitted from a diff of
`Hero.talentPointsSpent(tier)` summed across tiers — **not** from `hasTalent`, which is true for any
non-zero allocation and cannot tell one point from two.

Priced deliberately high: most of a floor's 25. `LEVEL_UP` pays 2 and that is right, because a level is
+1 attack, +1 defence and a little max HP happening automatically. A point is a permanent choice about
*which* gains to take, and paying it like a level would say the choice does not matter.

Gated by `checkSpendingATalentIsWorthALot`, which **spends points through the engine**
(`hero.upgradeTalent`, 38 of them) and asserts the term records and pays, against `LEVEL_UP` and against
a floor's worth of turns. Mutation-tested: removing the emission site fails it.

**That gate is doing the same job as `checkTheHeroCanDie`** — producing the event deterministically
rather than waiting for the game to produce it — so the site is proven live *before* the action that
reaches it exists. **A term wired and gated but with nothing calling it is the exact shape `KILL` had**,
and the difference here is only that it is written down rather than discovered.

### What is not done: the action

Option 2 of three considered, and the one that keeps the game faithful:

| | shape | cost | honest verdict |
| --- | --- | --- | --- |
| 1 | auto-spend on level, scripted per class | three lines | the build stops being a build |
| **2** | **a `TALENT` action and `EnvMode.TALENT`, mirroring `INVENTORY`** | **a head, a mask case, a mode** | **what a player does; the only one that is faithful** |
| 3 | log the points as a miss and move on | none | keeps the gap visible, teaches nothing |

The shape is the one `OPEN_INVENTORY` already uses: a WORLD action enters the mode, the mode's mask
offers `CANCEL` plus one entry per spendable talent, `applySecondary` calls `upgradeTalent`, and the
mode returns to `WORLD`.

**The part that is not a copy of `INVENTORY`:** a talent is chosen by *tier then talent*, so it is two
decisions where `INVENTORY` is one. Two heads (like the slot and target heads), or one head over the
flattened list of currently-spendable talents — the latter is simpler, and the "which talent did index
N mean" ambiguity is the one `slotcheck` exists to close, so it should be gated the same way.

`modecheck` will have to cover `TALENT`, and `EnvMode` gains a value, which touches the replay format's
mode column and therefore the committed corpus.

---

## 1.13 No recording the trainer wrote could be watched, and `verify` said they were perfect - FIXED

`issues.md` "Training" 15. The report had two halves - recordings the viewer could not play, and a
`verify` that called them perfect - and the second was hiding the first.

### What `verify` was actually asserting

Nine recordings the trainer had written carried zero quickslot bindings and stopped at their first
slot-bearing step; `verify` printed `[OK]` on every one. `ReplayIO.verify` guards each comparison with
the sentinel its field declares for *not recorded*, and an empty quickslots field is one, so the check
was skipped rather than failed. That is the sentinel weakness `TODO.md` 1.12 names, one level up: a
guard written to let an unrecorded field pass cannot tell *not applicable* from *lost*, and cannot be
the only thing between a recording and a viewer.

So `Verification` now carries `unwatchableAt` and `passed()` beside `diverged`, and `verify` exits 3
with the reason rather than 0. **And that exposed the second half of the same fault**, which is the one
that mattered: `verify` never applied the recorded bindings at all. It re-executed a slot index against
whatever bindings the process happened to hold, so it and the viewer were resolving a recorded index two
different ways and both were reporting themselves right. They now go through one `Quickslots.prepare`.

### The fault behind the fault, found by making `verify` stricter

`MSU-OTL-OAT` was the one post-fix trainer recording, and it reached 18 steps before diverging on a
`DROP`. The recorded hypothesis - drop resolution may be non-deterministic, or it may depend on which
item happened to be bound - was wrong, and the measurement took one diagnostic:

```
trainer   slot 1 -> ThrowingSpike   qs = ThrowingSpike->1 Food->-1 Waterskin->2 VelvetPouch->-1
viewer    slot 1 -> Food            qs = ThrowingSpike->-1 Food->-1 Waterskin->2 VelvetPouch->-1
```

Same seed, same step, same index. The trainer's live state at that step is correct and always was; what
was wrong is that **`Worker`'s listener called `ReplayRecorder.record` from
`EpisodeCollector.Listener.onStep`, which is after the step by definition**, and `record` is where the
bindings are captured. `ReplayRecorder.record` documents that it is called before `SPDEnv.step` - an
equip or unequip in the step rebinds a slot, so afterwards describes a different world - and the
listener could not report the difference, because there was only one callback and it was late.

`DROP` is where it shows because it is the one action that unbinds its own item: the item leaves the
backpack, stops being reachable from the iterator `capture()` walks, and its binding is gone from the
file before anyone reads it. Every other slot action leaves the bindings where they were, which is why
310 `USE/SLOT` steps in the corpus never showed it.

`EpisodeCollector.Listener` now has `onDecision` before the step and `onStep` after it. One callback
could not express "before and after", which is the fault in one sentence.

### The fourth fault, which only a real training run showed

With the recorder fixed, a fresh 2-generation run wrote a recording that `verify` **refused** at step
13: 211 SLOT and INVENTORY steps, one carrying bindings and 210 empty. The policy had dropped the
Waterskin at step 0 - the only thing bound - so nothing was bound for the rest of the run.

**An empty binding set is a state, not a missing field.** The refusal rule I had written asked whether
any step carries bindings, which cannot tell that from *the field was lost*, and it refused a recording
that plays perfectly: 211 SLOT and INVENTORY steps, one carrying bindings and 210 empty, because the
policy dropped the Waterskin at step 0 - the only thing bound - so nothing was bound for the rest of the
run.

**And that first rule was itself a false positive, caught by `rolloutcheck` failing intermittently.**
The question has to be of the *other* fields the same wire carried. That wire sent four of nine step
fields and left the rest at their sentinels, so health, turn **and** inventory are absent too, on every
step - nine trainer recordings carry `heroHp = -1, turn = -1.0, inventory = ""` throughout.
`Replay.wireDroppedStepFields()` is the discriminator: a recording with real health and real engine time
was written by a wire that carried everything, so an empty binding on it means the hero had none.

It failed only on the runs where a random episode happened to carry nothing, which is exactly why it
looked like a flake and not a bug - and it was found by a gate this change did not touch, failing for a
reason it did not expect. **Presence of evidence rather than absence of it, the second time in one
change that a sentinel-driven rule had to become a signature.**

Two other fixtures made the same mistake from the other direction. `PlaybackCheck`'s "no resolvable
slots" fixture blanked only the bindings, so it became a healthy recording and stopped being unwatchable.
`ReplayCatalogCheck`'s `write()` helper wrote every step field at its sentinel, so a fixture meant to
stand for a healthy recording carried the four-field signature. Both fixed; the playback case is now
**stronger**, asserting the player refused *at* the first slot-bearing step, which a string-match on the
halt reason could not tell from playing the run and mentioning it afterwards, and its converse
("nothing bound still plays") is a new case. And `ReplayIO.readHeader`'s new body scan compared against
a nine-field step and found none, because an empty inventory is a *trailing* field and `String.split`
discards trailing empty strings - so a step with no inventory is eight fields.

### And the gate that was green because it could not ask

`viewcheck`'s verdict was `!playback.diverged()`. That recording - the one refused at step 13 - was
reported `[OK] rendered playback: 1 recordings played clean` after **3.7 seconds**, having played almost
none of 1499 steps. A gate that stops early and reports success is worse than one that reports nothing,
because the next reader believes the file was watched.

And under it all: the corpus could not express the question. 17 recordings, 310 SLOT-mode steps, and the
entire SLOT vocabulary was `USE/SLOT` - `ScriptedPolicy` never chooses `DROP`, and an untrained policy
only drops by accident. `duelist-drop.replay` and `gradle :superintelligence:dropfixture` fix that in a
directory both viewer gates already read, and the command **verifies the recording before writing it**,
so a fixture that no longer plays cannot be committed in place of one that does.

### And then the part that was still missing: nothing in `trainer-replays` worked

Closing the wire is not the same as showing it. Every one of the ten recordings in `trainer-replays/`
predated the fix, so **picking anything from that section of the catalog ended in a halt** - and the
section blurb said so only once, for all ten, which is a sentence that becomes wrong the moment a fixed
recording exists, because it condemns a file that plays.

Two things followed. `APR-SLJ-DXM` is checked in beside the files it explains - 1514 steps, 245
slot-bearing of which **49 are `DROP`**, every step carrying health, engine time and inventory, verified
and played clean in the real viewer. And the catalog now labels each recording from the four-field
signature rather than the section as a whole, because `ReplayIO.readHeader` can reach the answer without
parsing every step - and because a blanket sentence is a claim about eleven files, ten of which it got
right and one of which it condemned wrongly.

**All ten are now gone from the directory**, replaced by five recordings the fixed trainer wrote, so
that every recording in `trainer-replays/` is watchable. A picker offering nine dead ends out of ten is
worse than no picker: the label made it honest and honest is not the same as useful.

**`MSU-OTL-OAT` was the one file the listing got wrong**, and it is worth naming rather than leaving:
it carries bindings, so the header scan passed it, and nothing in the file records *when* they were
captured - which is precisely the fault. It is gone from `trainer-replays/` now, so the listing has no
overstatement left to make; but the underlying question is real and **a recording still cannot say when
its bindings were captured**, so a future one written by a regressed recorder would be labelled
watchable and would not be. `verify` and `viewcheck` catch it, because both apply the recorded bindings
rather than trusting the field. Closing it in the listing would need a version bump on the format.

### The search flood, which was a price rather than a preference

Reported from a trainer recording: the bot "keeps searching the area like crazy". True, and the
cause is arithmetic rather than taste.

**`TURN_COST` was charged per _step_, and a step is not a turn.** `Actor.now()` is a duration and
`Hero.search` calls `spendAndNext(TIME_TO_SEARCH)` where `TIME_TO_SEARCH` is **2** - a search buys two
turns of the engine clock, in which other actors act and the hero can be hit, for the price of one. And
a search is the action that most reliably finds nothing: `Dungeon.level.discover` runs inside the
*success* branch of `Hero.search`, so the second sweep of a room is worth exactly nothing, forever.

Measured on `BTK-WZR-QFW`, reward per engine turn actually consumed:

```
SEARCH   189 steps  387 turns   -0.11 per turn
MOVE_E    63 steps   73 turns   +0.10 per turn
```

189 of 940 steps - a fifth of the episode - took **half the run's engine time at half price**. Now
charged by what the clock says was spent, floored at one turn.

**The floor is not a fudge, and dropping it would have been worse than the bug.** `WAIT` and `REST`
cost the episode a decision without advancing `Actor.now()` at all, so charging purely by the clock
made idling free - 121 stalls charged 0.004 - which is the degenerate strategy
`checkStallingIsNotCheaperThanDying` exists to forbid. That case caught it, not review.

Measured after, same seed, 6 generations at 8 workers: `SEARCH` went from 189 steps / 387 turns to
**39 / 78**, and the after-run's four recordings are different ones with a shortest of 182 steps
against 615 before.

### The report's other claim: drops are charged — by a different term than the one named

`ITEM_PICKUP +65.50` against `ITEM_DROPPED -70.50` is a **net -5.00**. That pair has refunded
correctly since `issues.md` 8, and the round trip was never the profit.

The profit was **`IDENTIFIED +27.50`**: it paid +0.5 for carrying an already-identified item and
refunded **nothing** when it was dropped, so `DROP`-then-`INTERACT` banked +0.5 on the same heap
forever. A count compared one way — the same shape, and nearly the same fix, as the `ITEM_PICKUP`
count `issues.md` 8 found — and it survived because `rewardcheck`'s pickup/drop case only inspected
the two terms it was written for. A gate that looks at one term cannot see a second one paying for the
same event.

Now a signed diff, and the case asserts that **no other term** pays for the round trip. Its fixture
also drops an **identified** potion, because an unidentified one contributes 0 to `countIdentified`
either way and the loop proves nothing — which is why the mutation passed the first time it was tried.
Mutation-tested: restoring the one-sided comparison fails it with `moved IDENTIFIED by 0.5`.

### And the turn cost, which was small enough that nothing else mattered

`reward.turn_cost` was **0.002**. A floor's whole 1500-turn budget cost **-3.00** against a **+25**
descent, so idling a floor was nearly free. That is the root the search flood and the inventory churn
were both growing out of.

Now **1.0**, with every other term rescaled onto the same footing. Leaving them alone would have
inverted three of `rewardcheck`'s properties rather than fixed one: death at -100 would have been
*cheaper* than playing 100 turns, and a free action at -0.05 would have been 20x cheaper than a real
one. **Two of the new numbers were set by the gate rather than chosen** — `turn_limit_penalty` and
`talent_reward` both failed on the first attempt at this scale and were moved until the ordering and
the opportunity-cost properties held.

Same seed, 6 generations at 8 workers:

```
                        steps   SEARCH   zero-progress   deepest
  before (0.002)         1508      66      587 (39%)       1
  after  (1.0)            392      63      133 (34%)       2
```

**The first depth-2 run in this project's history, on a seed that had never produced one**, and the run
that got there took 392 steps rather than timing out a 1508-step floor. `DEPTH_ADVANCE +2500.00`.

### And a fault the retrain found that had nothing to do with either

`ReplayPlayer.settle()` reported **N-1 of N steps** on a recording whose death lands on its final
step: it found a dead hero where it expected to compare one and called `finish()` without `advance()`.
`issues.md` 2's symptom, one path from the one its fix closed. Confirmed pre-existing by stashing the
reward change.

**The corpus had no recording that reached it**, which is the finding rather than a fixture detail.
Three recordings declare `termination=DEATH` with `hp=0` on their last step, and all three are dead
*by* that step (`huntress-mid` is on 2 HP at 57 of 59), so the drain handles them first - and a case
built from one of them **passes when the fix is reverted**, which it did when tried. `duelist-death-last`
(1 HP at 1228 of 1230, dead at 1229) is the file that reaches it.

### Verified

- `verifyall` - `:superintelligence:gates` at 20, `:desktop:playbackcheck` at 21, both green.
- `:desktop:viewcheck` - **18 of 18**, 36.2 s at 8 children. Re-run rather than carried over, because the
  gate's verdict changed and a recording joined the corpus.
- **End to end, on real trainer runs** - 2 generations, 2 workers, 2 episodes, `--max-turns 120`:

  ```
  SGD-CKH-JUO.dat, 1499 steps   [OK] verify reproduced 1499   [OK] viewcheck played clean
  APR-SLJ-DXM.dat, 1514 steps   [OK] verify reproduced 1514   [OK] viewcheck played clean
                                245 slot-bearing steps, of which 49 are DROP
  ```

  That is `issues.md` 15's headline claim, closed. `APR-SLJ-DXM` is checked into `trainer-replays/`, so
  the evidence outlives the temp directory a reboot empties — and so the trainer section of the catalog
  is not 10 of 10 unplayable.
- Mutation-tested five ways, each reverted after: moving `onDecision` after `env.step` fails
  `collectcheck`; removing `Quickslots.prepare` from `verify` fails `collectcheck` and `verifycheck`;
  restoring `Dungeon.quickslot.reset()` in `restore` fails `slotcheck`; blanking the fixture's `DROP`
  bindings fails `viewcheck` with `DIVERGED at step 2 - DROP/1 in SLOT`; dropping the catalog's
  per-recording watchability label fails `replaycheck`.
- **`verifyall` five consecutive times.** Four of the defects above were intermittent by construction -
  each depends on what a given random episode happens to be carrying - so one green run would not have
  been evidence of anything.

### Still open

**Nothing is unplayable any more.** The nine recordings written before the wire fix carried no
quickslot bindings and nothing could recover them, and they sat in `trainer-replays/` offering nine
dead ends out of ten in the picker. They have been **replaced** with five recordings the fixed trainer
wrote, all of which verify and play clean; their seeds and step counts are in `issues.md` 15 and the
files are in git history at `5356a8abb`. A directory of unwatchable evidence is worse than no
directory, because a reader cannot tell which is which without a label, and the label is the thing that
made the picker honest rather than the thing that should have to be read.

**`MSU-OTL-OAT` is listed as watchable and is not.** It carries bindings, so the header scan passes it
and nothing in the file records *when* they were captured. It is the one file the listing overstates;
`verify` and `viewcheck` catch it, and fixing the listing would need a version bump on the format.

---


## 1.15 The first full-length training run, and two engine crashes it found

A 150-generation run at 16 workers, seed 20261011, `--max-turns 300`, 4 episodes per generation.
**1h 16m 50s wall**, and it wrote 106 recordings to `trainer-replays` rather than to a temp directory.

### It reached depth 2, in 104 of 150 generations

```
gen     meanScore   bestScore   bestDepth
0          -7,921      -3,313          1
10         -4,019      -1,804          2
29         -4,073       1,226          2
74         -4,922      -2,613          2
119        -4,134      -1,779          2
149        -4,459      -2,953          1
```

**Best depth 2, and 104 generations recorded it.** `bestDepth` has been 1 in every generation of every
run in this project's history; `TODO.md` 1.4 called a single depth-2 in a smoke test progress and
nothing more. This is 104. It is not stable - the last generation ends at 1 - and 2 is not 5, so the
milestone from `research.md:40` is still out of reach. What has changed is that descending is now
something the policy does rather than something it has never once done.

Score improved across the run: the first 10 generations average -5966 against the last 10 at -4467.

### Two engine crashes, both only reachable because it descended

**`SentryRoom$Sentry.act` cast to `SentrySprite` unguarded** and threw `ClassCastException`, killing the
worker and ending a 32-minute run at generation 45. Seventeen lines above it the same method guards the
identical call with `if (sprite instanceof SentrySprite)` for exactly this reason - headless sprites are
`HeadlessSprite` - and the second cast was simply missed. It needed an agent on floor 2 to meet a
`SentryRoom` at all, which is why nothing in this project's history had found it.

**`ArmoredStatue.sprite()` had the same unguarded cast**, fixed in the same pass on the reasoning that
it is reachable at any depth: an armoured statue appears on floor 1. It had not crashed in any run, so
that part is a judgement about reachability rather than a measurement, and the honest guard is one that
walks every sprite cast in `core` rather than whichever one a descent happens to reach next. Four further
unguarded casts remain, all reachable only through abilities the agent cannot use yet -
`HeroDisguise`, `PowerOfMany`, `Feint`, and `Char`/`Hero`'s own fall and sprint - so `TODO.md` 1.10's
missing `TALENT` action is what is keeping them dormant.

**Both fixes are in `core` and are uncommitted**, per the engine boundary in `AGENTS.md`.

### Two recordings do not verify

**Now `issues.md` 16, which has the measurement and the four refuted hypotheses.** Summarised here because
this entry is where the run was read.

`GRB-YWQ-ZJH` (score +844, floor 3) and `LNL-PDP-CJK` (score +1646, floor 2) diverge at 153 of 189 and
61 of 88 respectively. **Every other one of the 106 verifies.**

These are the two best runs the run produced - the only positive scores in it - and they are the two
that walk furthest, which is the pattern this whole project keeps hitting: a fault only becomes reachable
once something works. Both diverge on an ordinary WORLD action (`MOVE_S`, `INTERACT`) rather than on
anything exotic.

**Diagnosed as far as measurement allows, and no further.** The hero stands on the same cell on floor 2 in
both, the floor's generation is seeded by `seedForDepth(depth, branch)` with `branch=0` on both, and the
special-room pools and `pitNeededDepth` are proven seed-deterministic by
`resetcheck.checkSpecialRoomPoolsDoNotDependOnOrder`. So floor 2 differs, and nothing measured explains
why.

A control that reproduces perfectly - 3312 steps, **383 floor transitions**, both sides traced, all 368
`specials` records identical - says the divergence is **not** seed-determined and needs a worker that has
already run other episodes. `--trace-world` now traces the recording path so a failing recording can be
diffed against its replay; the last obstacle is that the trace key is per worker rather than per run.

---
## 1.12 Every replay the trainer wrote was missing five of its nine fields - FIXED

Found by looking for a run that had genuinely reached depth 2 and then reading the file it produced.
The depth was real; the file could not have detected a starving hero or a one-turn drift.

`Worker.writeReplay` and `TrainerWorkers.readReplay` were two hand-written methods on opposite sides of
a process pipe, and the writer sent `action`, `slot`, `mode` and `heroPos` where the reader declares
nine fields. `reward`, `quickslots`, `heroHp`, `turn` and `inventory` were dropped and kept the
sentinels `Replay.Step` declares for *not recorded*.

**It could not have failed louder, and that is the lesson.** `ReplayIO.verify` guards each comparison
with its own sentinel, so a recording with no health, no engine time and no inventory verifies on
position alone and prints `[OK]`. Every gate was green. `collectcheck` was green. The corpus is
regenerated and green. The bug was only findable by reading a recorded file and noticing that the
columns health and turn were added to record were the ones nothing ever wrote.

Two things follow, and the second is the one worth keeping.

**A sentinel guard defeats itself as a detector.** Its job is to let an *unrecorded* field pass, and
it does that perfectly — including for a field that is unrecorded because it was dropped. The guard
cannot distinguish "not applicable" from "lost in transit", so it must not be the only thing checking
a recording. Anything with a sentinel needs a positive assertion that the sentinel is absent, or the
sentinel is a way of not noticing.

**A format asserted in two places is not a format.** Writer and reader agreed, which is why nothing
crashed: the pipe was internally consistent and wrong. Both sides now go through `train.ReplayCodec`,
versioned, refusing an unknown version rather than desynchronising quietly.

The gate round-trips a populated replay through the codec and requires every field back, and
separately drives a real `ReplayRecorder` episode - because the original fault was an inline field
list, and a codec unit test cannot see someone writing the next one.

The recorder half deliberately does **not** assert "most steps carry an inventory". A real run came
back with 347 of 660 steps empty, and every one traced to the untrained policy dropping things on the
floor - a hero carrying nothing is a legitimate state, so that assertion would have been a flaky gate
firing on correct behaviour. It asserts instead that step 0 is non-empty (every class starts with a
VelvetPouch) and that the value changes at least once, which distinguishes *dropped* from *never
written*.

---

## 1.9 Giving up is priced below dying, which prices it as an option - FIXED

`issues.md` 9 asked for stall and death to be balanced; `TODO.md` 1.8 asked what surviving is worth.
Both were answered by the same arithmetic error, and it was not that the constants were the wrong size.

### What was actually true

`STALLED` was `-deathPenalty`, the *same* number as dying. That had been deliberate — it reversed
`PLAN-reward-signals.md` §3.2 and was recorded as a decision — and it balanced the price while losing
the distinction. To a learner, two outcomes that cost the same are one outcome with two labels, and the
label that survives is the useful one: **quitting is survivable.** issues.md 13 had already recorded
what quitting looks like in practice — `duelist-mid` spending 120 consecutive steps alternating equip and
unequip on one `Rapier` at `dEng = 0.0` — and the guard was right that it is a stall. What was missing
was anything making those 120 steps cost.

`TURN_LIMIT` was `-turnCost * 100`, which is **-0.2** against a **+10** depth reward. A hero could spend
1500 turns mapping a floor, bank the exploration reward for it, and be charged a fifth of a point for
never reaching the stairs. With exploration paying per tile (§1.8), idling out the clock was a
*positive-value strategy*, not a failure to progress — and the runs that did it were not misbehaving,
they were optimising.

### What changed

| Key | Was | Now | Why |
| --- | --- | --- | --- |
| `reward.stall_penalty` | `-deathPenalty` (100) | **150** | Worse than dying. Dying moved the run forward and closed a floor; a stall is a run that stopped on an alive hero who was asked to do nothing difficult |
| `reward.turn_limit_penalty` | `-turnCost * 100` (0.2) | **25** | Idling out a floor must cost more than the exploration it banked, or it is not a failure |
| `reward.no_progress_penalty` | *did not exist* | **0.05** | A step that advanced no engine time. Equipping costs nothing in the real game too — `WndUseItem.onClick` calls `item.execute` and returns — so a free infinite action has to cost something |

The ordering is now **stall (-150) < death (-100) < turn limit (-25) < a cleared floor (+25)**, and
`rewardcheck` asserts the ordering rather than the constants, so retuning any one of them cannot
silently invert it.

### Priced, not prevented, and that is the part worth being explicit about

The `no_progress_penalty` term reads `Actor.now()` and charges a step that did not advance it. It does
**not** tighten the stall guard to count engine time, which is what §0.10 says is the real fix.

Moving the guard would change *when recordings end*, which invalidates the committed corpus and needs
its own gate. Charging the step prices the action without moving any boundary — so `duelist-mid` still
plays clean, `viewcheck` is still 17 of 17, and the loop is 50× more expensive than it was. The guard
change remains §0.10's open item; what is closed here is that the loop is no longer free while it is
open.

### Gated

`rewardcheck`, 9 → 13 cases. Four are new and each is a property rather than a constant:
`checkTerminalsAreOrdered`, `checkDepthBeatsAPerFloorTurnLimit`, `checkStallCostsMoreThanDying` (which
replaces an equality assertion), and `checkFreeStepsCostSomething`.

---

## 1.8 Exploration outbid a descent, which is the reason `bestDepth` was 1 - FIXED

### The arithmetic, which is the whole of it

A regular floor holds several hundred walkable tiles. §0.8 measured 1040 floors over 40 seeds: 419
exceed 48 on at least one side, the largest is **81 tall**, and only 17 exceed 64. At the shipped
`explore_reward` of **0.05**, mapping a floor without ever touching the stairs paid **more than the
+10** that descending paid.

That is the objective, restated as a ratio: the agent was being paid to wander, and wandering is what
it learned. Every measurement in this project's history is consistent with it — `meanScore` climbing
186 → 59 across six generations on a run where `bestDepth` never moved off 1, and the best score ever
seen (~44) being almost entirely tile exploration rather than progress.

`depth_reward` is now **25** and `explore_reward` **0.01**: mapping 120 tiles pays 1.20 against a 25.00
descent. `rewardcheck` asserts that a *small* floor's exploration is worth less than a descent, so the
property holds with room to spare rather than on a number that happens to be true at the current floor
sizes.

**This is the number to change first when depth does not move**, and the properties file says so at the
key rather than in a changelog nobody reads.

### `damage_penalty` was so low that dying carried no gradient at all

The one that is not about *what* the reward pays for but about whether it can be learned from at all.

`reward.damage_penalty` was **0.25 per HP** against a `death_penalty` of **100**. A floor-1 hero has
20 HP, so losing an entire health bar cost **5** — five per cent of dying. Two training runs died in
**100% of episodes**, and at that rate every episode returned the *same* terminal reward, with the only
term that varying worth a twentieth of it. There was no gradient. Nothing in the loss curves said so:
`policy=0.0002`, `valueLoss` moving, entropy steady — a healthy-looking run over an objective that
could not be learned.

Now **2.5**, so a full floor-1 bar costs half a death and the agent feels every hit on the way down
rather than only the ending.

**Sized as a relationship, not picked as a number**, and the first attempt proved why. At **5.0** a
full health bar cost exactly one death — which fixes the gradient and immediately breaks something else:
killing a rat (8 HP) cost `5.0 × 8 = 40` and paid `kill_reward` of 3, a net **−37 to fight**. So the
sequence matters and both halves had to land together:

| | damage/HP | a full bar costs | kill | sneak | a melee rat kill nets |
| --- | --- | --- | --- | --- | --- |
| shipped for most of the project | 0.25 | 5 (5% of a death) | 3 | 6 | about +5, for no reason |
| first attempt | 5.0 | 100 (one death) | 3 | 6 | **−37** |
| now | 2.5 | 50 (half a death) | 12 | 22 | about −2, and a **sneak kill nets +34** |

The last column is the one that matters, and it is why the melee kill is left close to break-even rather
than positive: **the profitable kill is the one that costs no health.** A reward structure that pays a
traded fight and a free kill about the same teaches trading blows, and trading blows is what the recorded
deaths look like.

**Known weakness, recorded rather than designed away.** A linear per-HP penalty is depth-dependent by
construction, because the bar grows: at 2.5 a floor-1 bar is half a death and a floor-5 bar (~60 HP) is
one and a half. Making the damage penalty and the combat payouts both scale with depth is a
`RewardModel` change, not a config change, and is not done.

`checkKillingIsWorthDoing` asserts both halves of the relationship — that a full health bar is a
meaningful fraction of a death, and that a mob worth ~8 HP does not cost more to kill than it pays. The
second assertion is what caught the −37.

### `KILL` was declared, plumbed and never emitted

`RewardLedger.countKill()` was written and never called. `RewardModel` snapshotted
`Statistics.enemiesSlain` into `prevKills` and never compared it. So **fighting was worth exactly
nothing while being hit cost something** — the cheapest way past a corridor was to walk down it, and
the recorded deaths have that shape: the hero does not lose a fight, he runs out of room.

`reward.kill_reward` = 12.0, emitted from the engine's own counter. Gated by `checkKillingIsWorthDoing`.

### `SNEAK_ATTACK`, which is the largest combat payout on purpose

New term, read from `Statistics.sneakAttacks` — which `Mob.defenseProc` increments exactly when
`Mob.surprisedBy` holds, so it is the engine's own answer and not a re-derivation of it.

`reward.sneak_attack_reward` = 22.0, well above the kill. A kill is paid for damage dealt, which depends on
the weapon and on rolls the policy cannot influence. **A surprise attack is paid for arriving unseen**,
which is a function of where the hero walked — and it is the one combat outcome that costs nothing,
because `Mob.surprisedBy` skips the defender's `defenseSkill` outright. Paying the kill alone teaches
trading blows with whatever is in reach.

**Building the gate for this took three attempts, and the failures are the useful part.** The first
drove randomly from the action mask and met no mob on its seed — it reported success having measured
nothing. The second hunted every enemy on the level and walked the hero into a dead end for 900 turns,
taking 99 damage and killing nothing: the level is rooms joined by doors, so an enemy the hero can see
but cannot reach is an ordinary situation and the driver could not tell it from one it could reach. The
third places a mob through `Level.createMob` + `LevelPipeline.spriteFor` + `Actor.add` — the three steps
`SummoningTrap` uses, and all three matter, because `Actor.findChar` searches `Actor.chars` and nothing
else, so without `Actor.add` the hero walks into the mob and nothing happens.

A gate that cannot reach the emission site it guards is not a gate. Each failure was one that would
have passed, and saying so is more useful than shipping the version that happened to work.

### Measured

Four generations, 20 workers, 2 episodes each — a smoke test, not a training run:

```
gen   score    depth        turns   ended
0     -107.6   mean 1.0     443     death 40 (100%)
1     -111.5   mean 1.0     751     death 40 (100%)
2     -109.8   mean 1.0 best 2       death 40 (100%)     <- first depth 2 in any run
3     -104.2   mean 1.0     829     death 32 (80%) turn_limit 8 (20%)
```

Then 12 generations, 20 workers, 4 episodes each:

```
gen   score    turns   ended                          depth
4     -115.0   664     death 80 (100%)               best 1
5     -119.2   902     death 78 (98%)  turn_limit 2   best 1
7     -111.5   702     death 74 (93%)  turn_limit 6   best 1
8      -99.1   857     death 63 (79%)  turn_limit 17  best 1
9      -70.8  1335     turn_limit 65 (81%) death 15   best 1
11    -121.1  1234     death 77 (96%)  turn_limit 3   best 1
```

**Deaths 100% → 19%, mean turns 377 → 1335, score −107 → −71.** The agent learned to survive, which is
the first time any run has moved on that axis.

**Depth still does not trend, and that is the honest result.** `bestDepth 2` in a smoke test and 1 in a
longer one is not progress; what it shows is that floor 2 is *reachable* once exploration stops paying
better than descending. Whether the ratio is now right is a 40-generation measurement, not a 4-generation
one, and §1.4 is where it is tracked.

---

## 0.10 `duelist-mid` ends `STALLED` after a zero-cost equip loop - INVESTIGATED; loop priced, guard unchanged

`issues.md` "Training" 13 asks why the recording stalls when the hero does not look idle, and offers
"remove stalled and regen the recording" as a worst case. The question is right; the remedy would not work,
and the reason is the useful part.

### What actually happens

Replaying the recording through the real environment and sampling `Actor.now()` on every step:

```
step 286 USE/0  mode=SLOT   pos=317  hp=9  eng=12.0  dEng=0.0
step 287 USE/0  mode=WORLD  pos=317  hp=9  eng=12.0  dEng=0.0
...
step 295 USE/0  mode=WORLD  pos=317  hp=9  eng=12.0  dEng=0.0
120 consecutive steps spent zero engine time (first at step 176)
```

`USE` on slot 0, which holds a `Rapier`. `SlotAction.use` (`SlotAction.java:77`) routes an equippable,
non-targeting item to `toggleEquip`, which equips; the next `USE` unequips; the next equips. `hero.next()`
releases the hero each time, so the drain returns `READY` and the environment believes the turn resolved.

**`STALLED` is a true verdict.** `checkFloorLimits` (`SPDEnv.java:504`) tests
`hero.pos == lastStallPos && hero.HP == lastStallHp`; both are constant across the loop, so 120 counts end
it. The guard is not broken and the hero is not idle — it is looping on an action that costs nothing.

And the action costs nothing *in the real game too*: `WndUseItem.onClick` calls `item.execute` and
returns, spending no turn. So this is not a harness artefact. The policy found a genuinely free action and
the environment let it repeat 120 times before the position/HP heuristic noticed.

### Why "remove stalled" is the wrong remedy

It removes the only thing that stopped the loop and extends it by `turnLimitTotal = 40000` more zero-cost
steps. It also destroys the signal: a run that made no progress would then end `TURN_LIMIT` and be
recorded as a normal truncation. The recording is a symptom; the loop is the fault.

### The fix that does help, and is not done

Make the stall guard count **engine time**, not only position and HP. A step that does not advance
`Actor.now()` is a step in which nothing happened in the game, and that is a stronger and more general
predicate than "the hero did not move" — it covers equip toggles and any other free action rather than
only this one, and it makes the recorded reason say what actually occurred.

Not done because it changes termination behaviour for every recording in the corpus, needs its own gate,
and interacts with the `REST` question below.

### `cleric-mid` is a second, unrelated `STALLED`

Same header value, different producer, and conflating the two is what makes this look like one bug.

| | `duelist-mid` | `cleric-mid` |
| --- | --- | --- |
| turns vs cap | 271 of 150 | 49 of 150 |
| engine time | **0.0 per step** for 120 steps | 1.0-3.0 per step |
| position | fixed at 317 | 427 → 463 → 427 |
| ends | `checkFloorLimits`, `SPDEnv.java:504` | resting backstop, `LevelPipeline.java:259` |
| resting | false | **true** |

`cleric-mid` never accumulates 120 same-cell steps, so `checkFloorLimits` cannot be what ended it. It is
`!wantsMore && steps > 8 && Dungeon.hero.resting`, which fires on a hero resting legitimately who was
about to heal — and `REST` is a legitimate strategy, being the only way to regenerate. This is the same
conflation `PLAN-reward-signals.md` §7 documents for the one-way-door version of the same backstop.

### The gap underneath both: `termination` cannot be triaged

`Replay.termination` (`Replay.java:168`) is a bare enum name. Three distinct outcomes —
`checkFloorLimits`, the resting backstop, and `STEP_LIMIT` drain exhaustion — all write the string
`STALLED`, and the viewer cannot tell a free-loop stall from a legitimate rest from an exhausted drain
budget. `issues.md` 10 was diagnosed this way: "the recording says STALLED" had to be resolved by
replaying and instrumenting, because the header could not say which.

**Recording which producer fired is worth doing before either fix**, because both fixes otherwise have to
be reasoned about through a field that cannot distinguish the case being fixed from the case not being
fixed. It is also the cheapest change on the list.

---

## 0.7 A rare viewer divergence, reported and **not** reproduced

`duelist-mid` played by hand at speed 1 in a fullscreen window halted at step 25 with

```
DIVERGED at step 25 - INTERACT/0 in WORLD: engine time is 21.0, recording says 22.0
```

Position and health matched; the clock was one turn short. The reporter could not reproduce it on a
re-run, and neither could this investigation, in roughly fifty attempts: hidden at speeds 1, 2, 4, 8, 16
and 40; visible and windowed; audio on; and **20 concurrent visible children at once**, which was the
shape most likely to make frame time irregular and came back 20 of 20 clean with near-identical frame
counts (2589–2644). That last one is the useful negative result — 20 windows did not make frame timing
irregular enough to matter on this machine, so load is not a lever for hunting it.

Leading hypothesis, **unmeasured**: a recorded action pending across a frame boundary, while another
actor's attack animation resolves in `GameScene.super.update()`, where `Mob.onAttackComplete` lands the
hit and `Hero.damage` calls `Hero.interrupt()` — discarding the pending action. That accounts for the
signature exactly: a turn that is spent by nobody, with position and health unchanged. The trainer cannot
reach it, because `HeadlessSprite.attack` resolves synchronously inside one `headlessStep`, so the hero's
action is never exposed across a frame boundary there.

Recorded as open rather than fixed, because fixing it needs a measurement nobody has yet, and because the
two things that would make it reachable are already written down elsewhere and are still open:
`FINDINGS-viewer-fidelity.md` O-2 (the animation clock cannot be pinned from the viewer — `Game.elapsed`
is derived in `Game.update` before any game code runs) and `ViewCheck.parallelism()` (*"playback is
timing sensitive — anything that changes how fast a child renders changes what the child finds"*). Note
what follows from that: **`viewcheck` forks every child hidden at speed 40, so the gate has never run
the timing path a human run takes**, and a green `viewcheck` is not evidence that a slow, visible
playback agrees.

---

## 0.5 `issues.md` "Training" 10 — the agent's first action on a new floor was being eaten - FIXED

**The report:** *"a run that descends and then walks back up the stairs diverges from its recording."*
Both sides agreed on the symptom and disagreed on the world, by exactly one cell and one step:

```
DIVERGED at step 161 - MOVE_SE/0 in WORLD: hero at (11, 9) pos 317, recording says (10, 8) pos 282
```

**The two suspects were both innocent, and the interesting part is how they were eliminated.**
`InterlevelScene.ascend()` calls `Mob.holdAllies(Dungeon.level)` and `Dungeon.saveAll()` and
`LevelPipeline.handleTransition()` calls neither — that is real, and `Mob.holdAllies` still uses
`Collections.shuffle` (`ENGINE-CHANGES.md` §7), but it is not this. The regenerated floor came back
**identical in both environments, roster for roster**, which the world trace shows directly. A premise
that had been carried through several documents turned out to describe a difference that could not
produce the symptom it was blamed for.

**The fault is one the recording cannot show.** The hero arrives on a new floor where everything is at
time 0 — `Dungeon.newLevel()` calls `Actor.clear()` — so the game's own scheduler picks him for exactly
one act before the player can act at all. `Hero.act()` runs `Hero.checkVisibleMobs()`, and *that* is
where the new floor's sleeping mobs notice a hero standing among them; a mob seeing him for the first
time calls `Hero.interrupt()`, which discards whatever action is pending.

`SPDEnv.settle` returned from the transition without giving the hero that act — deliberately, and for a
reason that was right at the time. Draining on arrival meant working the new floor's sleeping mobs up
to the hero's stale clock, and the trainer recorded `engine time 129.0` where the viewer read `0.0`
(the fourth instance of the class `PLAN-viewer-fidelity.md` §6 names). `Actor.fixTime()` removed the
stale clock; the "do not drain" workaround outlived it, and with it went the arrival act.

So the agent's action was injected first, and the hero's very first act on the new floor interrupted
it. On `duelist-mid`, which ascends at step 159:

```
step 160  MOVE_SE  ->  282  engine time 0.0     the trainer: interrupted, no time spent
```

and the viewer, replaying the same recording in the real game, moved to 317.

**Fixed** by `SPDEnv.settleLanding()`: one scheduler step after a transition, not a drain.
`Hero.act()` with no action pending calls `ready()` and returns without spending anything, which is
also what stops the loop on its first iteration — "one act" and "a drain" are different things here
rather than the same thing at a different size. It costs no turn, and the transition's own turn is
already charged by the branch around it.

### Why no gate saw it

`transitioncheck` looked at the floor the hero lands on and the clock he lands with, and never at
whether he *acted* on arrival. Nothing in the suite injected an action on the frame a transition
completed — every case spent a `WAIT`, which a noticing mob cannot interrupt into anything different.

Two cases were added (7 -> 9). The first drives a descent *and* an ascent and then asserts the agent's
first action on the floor it comes back to actually moves the hero. The fixture is found at run time
rather than hard-coded, because a hero arrives at a floor's **entrance** and level generation keeps
entrances clear: the arrival FOV of all nine seeds tried was empty of enemies, so a case built on a
descent alone would have passed with the fault present. Climbing back up lands in an ordinary room,
which is exactly the shape `issues.md` 10 diverged on.

The second drives the ascent itself, which no gate had ever exercised — every one of the other seven
descends, and an ascent is the one direction with a second engine behaviour attached to it
(`InterlevelScene.ascend()` reads a floor off disk when the depth is in `generatedLevels`, and
descending is what puts it there).

Both are mutation-tested. Removing the landing act fails the first with *"the agent's first action on
depth 1 moved nobody"*. Refusing to service an ascent fails the second, and fails the first too,
because it cannot build the fixture — worth noting as a case in its own right: a fixture that cannot be
built is reported rather than skipped.

### Corpus

`duelist-mid` was the only recording that descended, so it is the only one the change touches, and it
was regenerated. It still descends at step 157 and climbs back at 159, so the path stays covered by the
corpus as well as by the gate. The other sixteen are byte-identical, which is the determinism claim
being made by the regeneration script rather than a coincidence.

---

## 0.1 `issues.md` "Training" 9 — descending ended the episode - FIXED, including the ascent

**The report:** *"the same duelist-mid recording shows him descending to a new floor, with full health
too. But immediately as he descends, the recording ends as stalled."* It was, and the cause was the
simplest thing in the project and the hardest to see, because nothing in it was broken.

### The fault

`Game.switchScene(...)` sets a flag and returns. A real game reaches `Game.step()` from
`Game.render()`, and *that* is what turns the flag into "a scene wants to take over".
`HeadlessGame.render()` is empty — that is what "headless" means here — so `HeadlessGame.step()` never
ran, so the acknowledgement was never raised, so `LevelPipeline.runToHeroReady` never saw the
hand-off. `Actor.headlessStep()` already honoured the *request*, so the scheduler stopped dead while
the pipeline kept asking for an acknowledgement that could not arrive. The drain burned its 20,000-step
budget, returned `STEP_LIMIT`, and `SPDEnv` maps that to `STALLED`.

A hero at full health walks onto the stairs and the run is over, mid-sentence. **`duelist-mid` had
`termination=STALLED` and its last step was `INTERACT` onto the exit at cell 338.** The rendered viewer
does render, so it really did descend — which is what the report describes seeing.

**Fixed** by `HeadlessGame.switchRequested()` reporting the request as well as the acknowledgement.
Gated by **`transitioncheck`**, 7 cases, mutation-tested: reinstating the old one-liner fails all 7.

### Two faults it was hiding, both of which were only reachable because nothing ever descended

**`DEPTH_ADVANCE` could not fire.** `SPDEnv.onFloorTransition` called `reward.resetSnapshot()` *after*
the depth had changed, so the next `reward.step` re-took its snapshot at the new depth and compared it
against itself. The primary goal term — the one `research.md` says survives into the sparse phase — was
dead, and it was dead *because* nothing ever descended.

**The environment drained where the game does not.** `InterlevelScene` builds the floor and hands the
player straight back; `SPDEnv` kept draining, which meant working the new floor's sleeping mobs up to
the hero. `Dungeon.newLevel()` calls `Actor.clear()`, which zeroes the clock, so "the hero's clock" was
the whole previous floor's length — 128 turns on `duelist-mid`. The trainer recorded `engine time
129.0` where the viewer read `0.0`.

### And the fifth "something only one side does"

`InterlevelScene`'s descend thread opens with **`Actor.fixTime()`**, which pulls every actor's time
back by the smallest of them before `Dungeon.newLevel()` zeroes the clock. Without it the hero arrived
carrying the previous floor's time. Same signature, same fix route, same class as the quickslot
bindings and the intro flag: `PLAN-viewer-fidelity.md` §6. `transitioncheck` case 7 is the clock.

### Status

`:superintelligence:gates` is green at 20, `playbackcheck` is green at 20, `:desktop:viewcheck` is
**17 of 17**. It was 16 of 17 when this was written, and the one failure was not a regression but the
far side of this same fault — see §0.5.

### Ascending after descending

**Closed. See §0.5.** In short: the regenerated floor was identical in both environments, roster for
roster, so neither of the differences below could produce the symptom, and the fault was that the
environment never gave the hero a turn on the new floor. `Hero.act()` is where
`Hero.checkVisibleMobs()` wakes the floor's sleeping mobs, and a mob seeing the hero for the first
time calls `Hero.interrupt()`, which discarded the agent's first action on every new floor.

What is left of the two differences recorded here:

- **`InterlevelScene.ascend()` calls `Mob.holdAllies(Dungeon.level)` and `Dungeon.saveAll()`; the
  pipeline calls neither.** Still true, and not this. The regenerated floor came back identical in both
  environments, so neither could have been the cause.
- **`ascend()` reads `depth<n>.dat` off the platform file root** when the depth is in
  `generatedLevels`. Still true, and still the reason a viewer run that descended and climbed back up
  stopped on `Cannot read save file`. `ReplayPlayer` clears `Dungeon.generatedLevels` on the frame a
  transition is pending; the transition's second form — the divergence — was elsewhere.
- **`Mob.holdAllies` still uses `Collections.shuffle`** and is still unseeded (`ENGINE-CHANGES.md` §7).
  An engine file, and still not tested, because nothing reachable by the scripted policy calls
  `holdAllies`.

---

## 0.2 `issues.md` "Training" 8 — picking an item up and putting it down - FIXED

**The report:** *"I saw a hero pick up and drop an item in a recording, presumably cheating score. This
has not yet been confirmed though."*

Confirmed. `RewardModel` compared a **count** of carried items, one way: `items > prevItemCount` paid
out, and a decrease paid nothing. `DROP` is an action the policy can take (`WORLD` → `SLOT` → `WORLD`),
and picking the same pile up again is one more `INTERACT`, so `INTERACT, DROP, INTERACT, DROP` raised
the score on every pickup while the world did not change. Three steps per cycle, no limit on cycles,
and `research.md:57` asks for exploit mitigation for exactly this shape.

Now a **net diff of what the inventory is worth**, in both directions, with a new `RewardTerm.ITEM_DROPPED`
for the loss so the round trip is visible in the per-term report rather than showing up as a total that
does not add up to its parts. Valuing rather than counting also makes the reward correct rather than
merely non-exploitable: dropping a `WandOfFire` to make room for a `VelvetPouch` is a net gain and is
scored as one, which a count cannot distinguish from throwing away the only thing you were carrying.

Gated by a new `rewardcheck` case that puts a known item on the floor through `Level.drop`, has the hero
walk onto it, and asserts the matching drop refunds exactly what the pickup paid. Mutation-tested:
removing the `ITEM_DROPPED` branch fails it.

**The recording named in the report no longer shows it.** `duelist-mid` was regenerated twice during
this work and carries no `DROP` step at all now. The fault was in the reward function, not in that file,
and the case is driven through the real environment rather than through a corpus file that a
regeneration can change.

---

## 0.3 `issues.md` "Training" 9, second half — stalling should cost what dying costs - FIXED

*"Also stalled should punish as much as death to restore balance between these two endings."*

`RewardModel.terminate(STALLED)` is now `-config.deathPenalty`, through the same constant `DEATH` uses
so the two cannot drift apart.

This **reverses `PLAN-reward-signals.md` §3.2**, which set it to zero. The reversal is deliberate and
is recorded rather than quietly applied: §3.2 was right that a cheap terminal is a degenerate strategy,
and it argued for zero because "truncated, priced by turn cost alone" was an honest description of what
a stall then was. That description stopped being true when the stall guard was found to fire on every
descent — "the episode ended on a timeout" was a harness bug wearing an outcome's name, and pricing it
at zero made the bug free. It cannot re-create the trap §3.2 closed either: stalling used to be cheaper
than surviving to the turn cap and now costs a hundred times more.

`rewardcheck`'s stall case was rewritten to assert the equality rather than the absence of a penalty,
and its turn-cost half still asserts that idling is charged separately.

**One gate assertion was itself wrong, and is fixed.** `checkStallingIsNotCheaperThanDying` tested
`stall.score < death` and printed "cheaper", but a score is negative, so `-0.24 < -100` is false — the
condition fires on a stall being *more* expensive than dying, and never fired on the -5.0 that the case
was written to catch. It now tests `stall.score > death`, which fires on -5.24 and on -0.24 and passes
on -100.24. **A gate that asserted its own name backwards for as long as it existed is worth
recording**, because it is the same failure as `Graph.bar` widening an axis it did not measure.

`STALLED` stays a **truncation** — the harness cut the episode off, not the game — which is
`PLAN-reward-signals.md` §3.4's position and is unchanged. That leaves a residual tension worth naming:
a -100 penalty that GAE bootstraps through is the same contradiction §2.4 identifies, which was fixed
by setting it to zero. It is recorded rather than resolved, because resolving it means changing
`isNaturalEnding` and with it every advantage a stall produces.

---

## 0.4 Two smaller faults found on the way, both recorded rather than claimed as fixes

**A recording's `depth` header is the depth the hero died at, not the depth it reached.**
`duelist-mid` reaches floor 2 and dies on floor 1, and reported `depth=1`. `Replay.depth` and the
trainer's per-episode `depth` now carry `Statistics.deepestFloor` — the game's own figure, reset by
`Dungeon.init`, so per-run. This matters beyond tidiness: `TODO.md` has spent several entries reasoning
about "every recording is depth=1", and a header that can say 1 for a run that went down keeps saying it.
The corpus now reads `depth=2` for `duelist-mid` and `warrior-death`, which descend.

**`ViewCheck --one` was not muted.** The gate forks every child with `-Dspd.mute=1`, and the
single-recording path inherited nothing, so re-running one recording to investigate a failure played it
out loud on the developer's speakers with the window visible and left open. `playOne` now states the
same defaults the forked children get, and only where they are unset, so an explicit
`-Dspd.hidden=0` still wins. `ReplayLauncher.MUTE` is a `static final`, so it has to happen before that
class loads rather than at the first call that needs it.

**`replay-viewer.bat` takes its flags before the file.** `--mute --close --fast 40 --windowed --at X Y
<replay>`. Given after the file they are silently ignored — the launcher prints `audio=on` and you
believe you muted it. Noted because it cost a cycle here, not because it is worth a code change.

---

## 0.0.1 The viewer issues, 1-5 - FIXED

Worked from [`issues.md`](issues.md) "Viewer / recordings". Each is recorded with its cause, because
every one of them reads as a symptom and none of them is where the fault was.

**1. `R` after a finished recording leaves it finished, and SPACE then waits the hero.** Two faults,
one behind the other, and neither is where it looks. A restart nulls `Dungeon.hero` and re-enters the
interlevel scene, which takes at least a fade to build floor 1 — and the frame driver runs throughout,
because it is `GameScene`'s update hook. So the first frame after `R` found a null hero, and a null hero
is what ends a run everywhere else in that class: the drain called `playback.finish()` and halted
"run ended - hero is dead" before the rebuilt level existed. The HUD reported a finished replay over a
cursor that had just been rewound, which is why pressing `R` appeared to do nothing at all. Playback
now waits for the rebuilt level rather than reading a missing hero as an ending.

The SPACE half is separate and was about window rather than state. `InterlevelScene` calls
`KeyEvent.clearListeners()` every time it hands off to the game scene, and the viewer's key overrides
were only re-asserted when the HUD was rebuilt — so in the gap the game's own `SPDAction.WAIT_OR_PICKUP`
binding on SPACE was live and unopposed, and a pause keypress waited the hero. That spends a turn no
recorded step asked for, so the next comparison diverges. The overrides are now re-asserted every frame
while the viewer is installed, and the listener is kept rather than rebuilt.

**2. A death recording's last step did not play.** The test for "the recording ends here" sat at the
*top* of the drain loop, so it fired on the frame the final action was injected — the action went in,
and playback ended before the hero ever performed it. Three of the seventeen committed recordings end in
death and all three stopped with the hero still standing, one step short of the death they describe.
The check is now made after the drain has run and the hero has acted.

**And that exposed a third fault, which is the more interesting one.** Draining the last step meant
comparing it for the first time, and `cleric-mid` failed immediately — seven turns and eight health
away from what its recording says. `ReplayPlayer`'s drain had none of the trainer's resting-stall
backstop, so it sat out a rest `LevelPipeline.runToHeroReady` had already given up on: `Hero.act()`
handles a resting hero with no action by spending `TIME_TO_REST` and calling `next()` without ever
becoming ready, so the drain simply waited until the rest ended on its own. The recording's
`termination=STALLED` is that backstop firing. The viewer now carries it too, counting scheduler steps
the way the trainer's does — as a field, not a loop variable, because the viewer yields the frame after
three blocked iterations and a local counter never reached the threshold.

It went unseen for as long as it did because the only step it affects is the last one, and the last one
was skipped rather than drained. `playbackcheck` now plays the committed corpus for exactly this
reason: every other case there builds its own fixture, and a freshly recorded run does not end on a rest.

**3. "Turns" is actions, not engine turns.** The HUD and the launch banner both said `turns N`,
where `Replay.turns` is `SPDEnv.turnsTotal()` — a count of decisions, not of game turns. The
engine's own clock is `Actor.now()`, which is fractional (a heavy weapon costs two, haste less than
one), so the two are not interchangeable and a recording's `turns` is not the hero's turn count.
Both are now labelled for what they are: `actions N` for the decision count and `engine time T`
for `Actor.now()`.

**4. Score was a single number with no per-step history.** `Replay.Step.reward` was recorded and
never displayed; the viewer showed only the run's final total, so a live view could not say which
action lost 100 points or when the score started falling. The HUD now shows the running score, the
per-step delta and a running gain/loss split, and `ReplayPlayback` computes it from the recording so
the same numbers appear in a headless check.

**5. Positions were one number.** `heroPos` is `y * width + x`, and the viewer printed it raw. Both
the HUD and the divergence message now print `(x, y)` with `x` to the right and `y` downward —
engine order, so it matches the tile map — and the raw index is kept alongside for anyone comparing
against a trace.

Gated by new cases in `playbackcheck` (10 -> 18 checks) and by `viewcheck` over the whole corpus.
Every one of the new checks is mutation-tested: restoring the old drain ordering fails the death case,
disarming the restart wait fails the rebuild case, flipping the y axis fails the coordinate case, and
reporting the net instead of the gain half fails the score case. The old death check was passing for the
wrong reason and had to be rebuilt — it forced the recorded health to zero on the last step of a run
whose hero was at full health, which was a lie the drain had been skipping the comparison for.

---

## 0.0.3 The HUD is anchored top left, and movable - FIXED

**`A` cycles the HUD through all four corners; the default is now top left.** It was pinned to bottom
left, which puts the block directly over the hero — and a recording spends most of its time on a hero
that has not moved. Top left is the one corner the game keeps clear: the depth banner is centred, the
item log runs up the right edge.

Cycled rather than toggled between two corners. Which corner is right depends on the window and on what
else is on screen, so a two-way toggle makes someone press the key twice to reach the other three.
Cycled in reading order — top left, top right, bottom right, bottom left — which keeps the block on
one side of the screen between presses instead of jumping across it.

Two things the move forced, both of which were already latent:

- **The help line had to stack inward from the current corner.** It was stacked always upward, which was
  correct only while the HUD was pinned to the bottom. At a top anchor it would have been drawn off the
  top of the screen. `drawBlock` now takes the anchor and an inset, and reports the height it used so
  the next block stacks against it.
- **`maxTextWidth` had to become anchor-independent.** It was `width - x - 4`, measured once from the
  left. A right-anchored line measures from the far end of the same run of text, so the bound is now
  `width - 2 * MARGIN` and serves both sides.

The geometry lives in two package-visible pure functions, `anchorX` and `anchorStartY`, rather than
inline in `drawBlock`. That is what makes it checkable at all: the drawn result needs a window, and the
arithmetic is where this can go wrong. The 20th `playbackcheck` case asserts the default is top left,
that every corner places text inside the window, that a top anchor grows downward and a bottom anchor
upward, that cycling reaches all four corners and returns, and that the help line stacks clear of the
HUD everywhere. Mutation-tested three ways.

---

## 0.0.2 The HUD ran off the screen - FIXED

**Reported as "the data doesn't line break, it goes off screen". The cause was that `BitmapText`
cannot line break at all.** Its font is built from `BitmapText.Font.LATIN_FULL`, which has no newline
glyph, and both `measure()` and `updateVertices()` feed every character straight through
`font.get()`. A `\n` therefore has no glyph, no line advance and no break: it draws as a blank and the
text keeps running to the right until it leaves the window. The HUD had built its rows with `\n` from
the start, so it was never a new bug — but it was invisible while the HUD was short enough to fit most
window sizes, and issue 4 above put a score line on it long enough to overflow any ordinary window.

The corroborating evidence was all around it. No `BitmapText` anywhere in `core` is ever given a `\n`,
because the game's own multi-line component — `RenderedTextBlock`, which the windows use — exists
precisely because `BitmapText` will not wrap. The viewer's HUD was the sole exception, and had been
since before any of these issues were filed.

Now a pool of one-line gizmos, wrapped on spaces and measured against the real UI font through
`BitmapText.measure()` rather than an assumed characters-per-line. Two details that are load-bearing:
the pool *grows* rather than having a fixed size, because the same text needs more lines on a narrow
window and a fixed pool would silently truncate the overflow — the failure being fixed; and a word too
wide to break on spaces (a deep seed hash) is kept whole rather than dropped, since dropping it would
silently hide the seed, which is the one value saying which recording is on screen.

`wrapText` takes its width measurer as a parameter so it can be checked headlessly, where there is no
font and every real measurement is zero. The nineteenth `playbackcheck` case asserts that no line comes
out wider than the limit, that wrapping does not alter the content, and that an explicit `\n` still
forces a break. Mutation-tested by disabling the width test, which fails it. `viewcheck` re-run over
the whole corpus: 17 of 17 clean.

---

## 0.0.0 The viewer and the trainer ran on different randomness - FIXED

**Found and fixed.** 14 of 17 committed recordings diverged in the rendered viewer while verifying
exactly headlessly. The cause was not the scheduler and not the step gate, both of which had been
proposed and measured and refuted. Four presentation draws were spending the gameplay RNG stream,
and only the rendered game makes them:

- `CharSprite.link` — a random sprite facing, drawn once per actor. `HeadlessSprite` overrides `link()`
  and never reaches it, so the trainer never drew and the viewer always did: 12 values.
- `AttackIndicator.checkEnemies` — which mob the overlay highlights, once per step.
- `Wand.staffFx` and `MagesStaff`'s staff particle — cosmetic direction and size jitter.

Every damage and defence roll after the first draw therefore came from a different point in the stream
than the recording was made from. Located by computing the base generator's expected output from
`scrambleSeed(Dungeon.seed)` and looking up where each side's first gameplay draw fell: headless at
value #5, the viewer at #17.

**It went unseen for as long as it did because the instrument was wrong.** `RandomTrace` reported
identical draw counts at every step of every recording, because `Random.Int(int, boolean)` and
`Random.shuffle(List)` advanced the generator without recording. `PLAN-replay-parity.md` §1.4 concluded
from that — correctly, given what it could see — that the stream was not offset, and five subsequent
hypotheses about ordering were refuted by measurement.

**Status.** `gradle :desktop:viewcheck` is green on all 17 and stable across repeated runs. The four
fixes are **engine changes**, per `instructions.md` §12.3, and are now committed with the proposal
record in `ENGINE-CHANGES.md`. `observecheck` gates the class of fault — every public draw path must be
counted, mutation-tested — and `viewdiff` is the automatic two-sided diff.
See `ISSUE-viewer-frame-drift.md`, `FINDINGS-viewer-fidelity.md` and `ENGINE-CHANGES.md`.

---

## 0. P0 - RESOLVED - the environment is not reproducible across processes

**Fixed.** Recording a run and re-executing it in a fresh process now reproduces exactly.

Verified: 120 rollouts - 4 seeds x 5 hero classes x 6 repeats - produce 1 distinct score per
seed/hero pair, and a recorded 499-step run verifies 4/4 in fresh processes. Three rollouts of one seed
across three fresh JVMs produce byte-identical `.replay` files, and a sweep of 32 recordings over 8 seeds
x 4 hero classes in one process replays exactly, twice.

### 0.4 A dead hero's remains reached the next run - found by the parity sweep

The last one, and the only one that needed a *sweep* rather than a repeat to see. `Bones` keeps a fallen
hero's belongings in statics plus a `bones.dat` under the process's file root, and
`RegularLevel.createItems` reads them to drop a `REMAINS` heap. For a player returning to a dungeon that
is the feature; for an environment playing thousands of independent runs in one process it means the
world is a function of process history as well as of its seed.

`diag.ParityCheck` recorded 32 runs and verified each twice. Two diverged, both on inventory, both on a
remnant - one recording carrying a HUNTRESS's `BowFragment` where its own replay had a ROGUE's
`CloakScrap`, one the other way round. Same seed, same hero, same actions; only what had died in
between differed.

Two things are worth recording about the fix rather than the fault:

- **Every other gate was structurally blind to it.** Each either plays one episode per process or never
  lets the hero die. `resetcheck` proves a reset is a function of its arguments, which is about the
environment; this was about the recording, and about game state that is supposed to persist.
- **The first attempt at the fix was wrong in a way every other test passed straight through.** Clearing
  the remains *after* `startRun` looks correct - it is where the other three statics are cleared - but
  remains are read during level generation, so the new floor was already carrying the previous hero's
  heap. `RunState` is now called before `startRun`, and `resetcheck` has a case that kills a hero and
  asserts the next floor carries none of it. That case also needed a positive control of its own: for
  its first few revisions it used a seed whose hero *survived*, so there was nothing to inherit and it
  passed without testing anything.

`Bones.clear()` is the one addition to the game, and it is additive only - nothing in the game calls
it.

A second pass found more identity-hash iteration after the first fix: `Char.buffs(Class)` handed
callers a `HashSet`, `Random.chances(HashMap)` chose secret room contents off a `Class`-keyed
`HashMap`, `Mob.chooseEnemy` resolved ties by iteration order, and `CursingTrap` / `VaultLevel` /
`Hero` used `Collections.shuffle`, which ignores the seeded generator entirely. All made
insertion-ordered or switched to `Random.shuffle`.

One trap worth recording: `GameSettings.getString(key, def, maxLength)` treats an over-long stored
value as corrupt and overwrites it with the default, and `SPDSettings.customSeed()` reads with a
20 character cap. A 23 character seed was therefore silently discarded and the run continued on a
*random* seed - which looked exactly like residual nondeterminism and cost real time to
rediscover. `LevelPipeline.startRun` now verifies the seed round-tripped and fails loudly.

The symptom when broken was that recording a run and re-executing it usually reproduced, but not
always - six runs of one seed produced four distinct position traces. An earlier smoke test had
reproduced 249/249 twice and I took that as proof. It was luck, and it took a dedicated audit to
find.

Three causes, all now fixed:

**0.1 The base RNG generator was unseeded.** `Random.resetGenerators()` pushed a no-argument
`new java.util.Random()`, and `Dungeon.init()` called it *after* pushing the seeded stack,
discarding it. Only level generation was deterministic, because `Level.create()` wraps its build in
`Random.pushGenerator( Dungeon.seedCurDepth() )`. Everything after it - mob turns, combat rolls,
item drops - drew from an unseeded generator.

This was never a game bug: within one process the base generator is created once and consumed
sequentially, so a live playthrough is self-consistent. The game was simply never designed to be
reproducible across processes, which is what replay verification needs.

Fixed by adding `Random.reseedBase(long)`, which reseeds the base generator in place without
disturbing anything pushed on top of it, and calling it from `Dungeon.init()` with the run seed.

**0.2 An unseeded generator inside level generation.** `EntranceRoom.placeEarlyGuidePages` pushed
an unseeded generator, deliberately - its comment reads *"so meta progression doesn't affect
levelgen"*. Correct intent, but it meant the first guidebook page landed on a different tile in every
process, which alone made floor 1 irreproducible.

Seeded from the floor's own seed with a fixed offset. Still isolated from meta progression, still
its own generator, now deterministic.

**0.3 HashSet iteration order in the turn scheduler and over actors.** `Actor.all`, `Actor.chars`,
`Level.mobs` and `Level.blobs` were all hash-based collections keyed on identity hash codes, so their
iteration order differed between JVM runs. `Actor.headlessStep` broke time ties on iteration order,
so *who moved first* varied; `Level.mobs` iteration order varied wherever it fed a game decision.

`Actor.all` and `Actor.chars` are now `LinkedHashSet`, `Level.mobs` is a `LinkedHashSet` and
`Level.blobs` a `LinkedHashMap`, so all four iterate in insertion order. Membership semantics are
unchanged. `Actor.headlessStep` additionally breaks remaining ties on `Actor.id()`, which is
assigned in creation order and therefore stable.

### Remaining risk

- `Mob` and `Char` still do not override `hashCode`. Nothing found iterates them through a hash
  collection during play, but that is an invariant future changes could break. Worth a comment at
  the `Level.mobs` declaration.
- `ColorMath.random` is used for particle colours only. Harmless, because headless never creates a
  particle.
- Determinism has been verified at 400 turns on a handful of seeds. Not verified across all 26
  floors, boss levels, or the shop/alchemy paths, which are exactly where exotic code lives. A
  long-horizon soak across many seeds is the right next check, and `paritycheck -PparityArgs="--turns
  1500 --seeds 8 --heroes 4"` is already the command for it.
- **State that persists between *processes* is only as isolated as the file root.** `Bones` was found
  because a sweep let the hero die repeatedly; `Bones.clear()` plus deleting the file handles it. There
  may be other save-shaped state the game reads during level generation - a sweep across more floors and
  more heroes is what would find it, not another audit of the reset path.

---

## 1. The critical gap: nothing has learned anything

**Partly closed.** The network runs, its gradients are correct, and the PPO update now executes on
real data collected by workers: `policy=` and `value=` are non-zero, and the weights move.

**But nothing has been trained, in the sense that matters.** Every run so far was a smoke test of the
plumbing — 2 to 25 generations of a few dozen episodes, discarded. Across all of them the agent never
left floor 1, against a milestone of beating Goo on depth 5. The best score seen is ~44, which is
almost entirely tile exploration rather than progress, and several runs ended in episodes of 2 to 14
turns: the hero dying at once, which against a `deathPenalty` of 100 means a real share of the
"experience" was learning only that the immediate neighbourhood is lethal.

So the remaining gap is *not* a missing subsystem — it is a persistence and tuning one, and the
ordering matters.

`gradle :superintelligence:gradcheck` finite-difference checks the analytic gradients against
central differences on 112 sampled parameters across all seven layers, and exits non-zero on a
mismatch. It is confirmed to fail when a derivative is deliberately removed.

`train.Worker` no longer loads weights into a `Network` and then hands control to `ScriptedPolicy`. It
runs `EpisodeCollector`, which plays the episode with the real policy, computes GAE over the whole of
it, and ships a sample of the transitions. See `PLAN-data-flow.md` steps 2 and 3.

| # | Task | Size | Notes |
| --- | --- | --- | --- |
| 1.1 | ~~Call `PPO.collect()` in `Worker.runEpisode`~~ | done | Superseded. `EpisodeCollector` replaced the collection half of `PPO` entirely; see `PLAN-data-flow.md` step 2 and §5 below. |
| 1.2 | ~~Run one generation end to end and check the losses are sane~~ | done | `policy` and `value=` are non-zero, and advantages arrive with a real spread. Reported per generation. |
| 1.3 | ~~Save and load the trained weights~~ | done | `Checkpoint` writes weights, Adam moments and the optimiser step count; `--save` / `--resume` / `--checkpoint-every`. A resumed run continues both the generation and the adam-step numbering. `checkpointcheck` refuses foreign, truncated, trailing-byte and wrong-config files. `PLAN-data-flow.md` step 4b. |
| 1.7 | ~~Instrument the loop so a run can be judged~~ | done | `clip=` now reports the real ratio-clip fraction, and `MetricsHistory` writes `metrics.csv` per generation plus an end-of-run trend. `PLAN-data-flow.md` step 3b. |
| 1.4 | Train long enough to see depth move off 1 | L | The actual milestone from research.md:40. **Partly unblocked.** The reward priced the objective wrongly in three separate ways and all three are fixed (§1.8, §1.9); `bestDepth 2` has now been observed, and a 12-generation run moved deaths 100% → 19% and mean turns 377 → 1335. **Depth still does not trend.** A 40-generation run is the honest next measurement, and the thing to watch is whether `bestDepth` moves once survival stops being the binding constraint. `graphcheck` gates the axis, so a `2.0` in a trend is a measurement this time. |
| 1.8 | ~~Make surviving worth more than stalling~~ | done | **Closed — see §1.8.** The question was asked and answered, and the answer was that the reward was not pricing the objective at all: exploration outbid a descent, killing paid nothing, and two of the four terminal outcomes were cheaper than the run they ended. |
| 1.5 | Verify the seed gate: 1 locked seed until Goo (depth 5), then 10, then 100, then random | M | `SeedPool` and `Trainer.advanceSchedule` are written; the gate has never had real depths to act on. Still blocked on 1.4. |
| 1.9 | ~~Five harness faults that made the agent unable to have a correct episode~~ | done | Every one found by driving paths a weak policy almost never reaches, and every one hidden behind the one before it. REST was a one-way door (36/36 episodes stalled, idle guard fired zero times); headless had no texture and every TextureFilm dereferenced the null, killing workers on hunger damage; every death blew the stack, so DEATH - the ending the reward function is built around - was unreachable; a reset did not clear the static pending cell listener, so 4 of 10 recordings diverged when verified in sequence and all 10 verified clean alone; and OPEN_INVENTORY fell through to INTERACT's cell handling, so opening the inventory also acted on a neighbouring cell - which reached training. All fixed and gated: PLAN-reward-signals.md sections 7-8 and 10, with resetcheck, graphcheck and a new modecheck case. |
| 1.6 | ~~Parallelise the update across minibatches~~ | done | --update-threads N. Measured 8.98 -> 2.73 ms/sample at 1 -> 4 threads (3.29x), 2.59 at 8 (3.47x). parallelcheck is a gate and mutation-tested. Required three fixes first, of which the dCell gradient leak was the real blocker. PLAN-data-flow.md step 4. |
| 1.10 | **Let the agent spend talent points** | M | **The agent levels up and can never spend the point.** §1.11 below. Reward side is done — `TALENT_UPGRADE` at 20, wired and gated. The action is missing. |
| 1.12 | ~~Every replay the trainer wrote was missing five of its nine fields~~ | done | **Closed — see §1.12.** The wire; and the two faults behind it, which only became visible once a post-fix recording reached a `DROP`. |
| 1.13 | ~~No recording the trainer wrote could be watched~~ | done | **Closed — see §1.13.** The recorder captured a step's bindings after the step, `verify` resolved indices against the live bindings and skipped the comparison when the field was empty, `viewcheck` passed any halt that was not a divergence, and the corpus contained no `DROP` in `SLOT` to ask about. A fresh trainer run now verifies and plays clean end to end. |
| 1.14 | ~~Recordings go where the catalogue reads, and cannot overwrite each other~~ | done | `trainer-replays` by default instead of `<--out>/replays`, `--replay-dir` overrides, and `-r2`/`-r3` suffixes so a second run of a seed cannot destroy the first. `d675e4815` destroyed one by hand and said nothing; the overwritten file is restored as `IPW-QMA-AFY-r2.dat`. |
| 1.15 | ~~Train for a real hour and see what happens~~ | done | **Partly.** 150 generations at 16 workers, 1h 16m, wrote 106 recordings. **`bestDepth 2` in 104 of 150 generations** - it has been 1 in every run in this project's history. Two engine crashes found, both only reachable by descending. Two recordings (the two best-scoring) still do not verify. §1.15 |

**Ordering was wrong and has been corrected.** 1.6 was originally next. It is still required — the
measurement does not care what else is on the list — but two smaller items now come first:

1. **1.7, because a run could not be judged.** `PPO.update` reported `clip=` as the fraction of
   samples with `|advantage| > clipEpsilon` on a *normalised* advantage, which is
   `P(|N(0,1)| > 0.2)` = **0.8415** — a constant. Observed 0.81/0.83/0.85/0.90 across real
   generations: that constant. `Policy.accumulatePolicyGradient` computed the real thing
   (`clipBinding`) and threw it away. Separately, `Trainer` kept only `lastEpisodes` — one generation —
   and discarded it; `diag.Graph` was dead code. **Now done:** the corrected metric ranges 0.047–0.737
   over 9 generations and `metrics.csv` accumulates per generation.
2. **1.3, because it is smaller than 1.6, on the critical path to 1.4, and is what makes two runs
   comparable at all.** Also now done.

**1.6 is finished too, so all four of the data-flow plan's steps are done.** What remains is 1.4 -
actually training something - and 1.5.

**1.6 was not the threading work it was scoped as.** It needed three correctness fixes first, and
the one that mattered was a bug nobody had looked for: Network.backward cleared dPrev but not
dCell, and LSTM.backward reads the incoming dCPrev before overwriting it. Every sample's
gradient was therefore a function of whichever sample preceded it - measured on conv.gb[15], four
samples in sequence gave 0.0892, 0.2075, 0.3372, 0.4235 where the same four individually gave
0.0892, 0.0419, -0.1487, 0.0517. Not additive.

That was invisible for the whole of the previous implementation because the buffer is shuffled, so a
leaked gradient arrived attached to an unrelated sample. Two bugs cancelling is the most expensive
kind to find, and the only reliable detector is one that varies a single thing at a time.

**What checkpointing turned out to need, beyond writing `Network.layers()`.** The plan called it
"plumbing, not design" and that was right about the format but wrong about the contents. Weights are
the obvious part and the easy part; two things that are *not* weights decide whether a resume is the
same run or a worse-looking one:

- **Adam's moments.** `Network.layers()` does not carry them, and a checkpoint without them restarts
  the optimiser's averages from zero. The run still trains. It just trains worse, with nothing in the
  metrics to say why — the same failure class as the `clip=` constant, one level up. `Network.moments()`
  and `loadMoments` now expose them. They are *not* sent to workers: a worker runs forward passes
  only, so pushing four times the floats per generation would cost ~57 MB per worker for nothing.
- **The optimiser step count.** Adam's bias correction divides by `1 - beta^step`, so restarting at 1
  makes that correction ~0.1 instead of ~1 — a full-size step on a barely-warmed average. Cheap to get
  wrong because nothing crashes: `Network.adamSteps(int)` restores it.

Two more things the format needed that were not obvious in advance:

- **Atomic writes.** A checkpoint half-written by a power cut is *newer* than the last good one, so it is
  exactly the file a resume would pick up. Writing to a sibling temp file and renaming means the
  file at the target path is always a complete previous checkpoint or a complete new one.
- **Refusing, loudly, in four cases.** A foreign file, a truncated one, one with trailing bytes, and one
  from a different `EnvConfig`. The last names the field — "trained with gridWidth=32, this run has
  48" tells you which flag to change, where "incompatible" tells you nothing.

Verified: a 6-generation run then a resumed 2-generation run continues the numbering 0-7 with no CSV
row collisions, and the resumed checkpoint's header reports generation 6 / adam step 32 as expected.
`checkpointcheck` (8 cases, now a gate) is mutation-tested — dropping the moments, the trailing-byte
check, or the config check each fail it. `weightsdiff <file>` is the complement: it reports how far a
checkpoint is from a freshly initialised policy, which the round-trip test cannot answer.

**`PPO.collect()` is gone, and so is the cap it implemented.** `rolloutCap` (default 2048) bounded
collection at ~98 MB because a step is ~49 KB and `turnLimitTotal` is 40000 - uncapped, one long
episode is ~1.9 GB against a 1536m worker heap. Its replacement is two caps with different units:
`--max-sampled-per-episode` bounds one worker's retained *observations*, and
`--max-samples-per-generation` bounds the trainer's update buffer across all workers. The observation
memory is now bounded by the sample rate rather than by episode length, because GAE runs on scalars
and never needs an unsampled step's observation.

**Worker-to-trainer data flow is resolved.** Chosen and built: the worker computes GAE and ships a
sample of the transitions; the trainer pools them and updates once. Reasoning, measurements and the
three bugs it surfaced are in `PLAN-data-flow.md`. Three consequences worth keeping in view:

- **The update, not the transport, is the bottleneck.** `gradle :superintelligence:updatecost`
  measures 11.3 ms per sample, which at a 5% sample rate and 4 epochs is ~107 s of trainer CPU per
  generation against ~0.12 s of transport. Every option in `WORKER-DATA-FLOW.md` was argued on
  bandwidth, which is not what binds here.
- **Episodes are much shorter than the arithmetic assumes.** An untrained policy produces 7-90 turn
  episodes, not ~150, so a 20-step tail is most of the episode and a requested 5% arrives as near
  100%. Expected to correct as the policy learns to survive; the sampled count in the report is the
  honest figure until then.
- **The update has been running at ~6× the cost of collecting the data**, visible as `barrier 606%`
  in a real run. Before this work the barrier was 12-26% of wall; now the trainer's single thread is
  the critical path and the other 19 logical cores idle through it. This is what makes 1.6 necessary
  rather than merely desirable.

**Headless coverage was the next likely source of crashes, and it was.** Running the real policy
surfaced three that the scripted one never touched: blobs had no emitter (15 blob types NPE in
`evolve()`), `GameScene.cancel()` dereferenced null `cellSelector`, and `GameScene.spellSprite()`
read `scene.spells` unguarded. Then the data-flow work surfaced **two more**, both fatal rather than
cosmetic:

- **The libGDX natives were never loaded in any headless JVM.** Desktop gets them implicitly from
  `Lwjgl3NativesLoader`; the headless backend replaces that, and a natives jar on the classpath does
  not load itself. Reached from `TextureCache.createSolid` / `createGradient` / `create`, which are
  *not* guarded on `Gdx.gl == null` the way `getBitmap` is - from any `Flare` or `ColorBlock`. Fixed by
  `GdxNativesLoader.load()` in `HeadlessServices.install()`.
- **`BitmapText` threw on every measuring call with a null font.** `HeadlessPlatform`
  `getGeneratorForString` returns null by design, so `PlatformSupport.getFont` returns null at its
  early-out. Its no-argument constructor already built a null-font instance, so nothing in the class
  guarded it. Reached from `Bag.execute` -> `WndQuickBag` -> `InventorySlot`.

Both are the same class of bug as the original three: a hand-written policy simply never reached the
code. Expect more. The useful observation is that each was a *deliberate headless substitution*
rather than a game bug - a null GL, a null font generator, a null scene - that something downstream
did not expect.

**Unverified: hero class appears not to affect the score.** Every hero class produced an identical
score and turn count on the same seed. `GamesInProgress.selectedClass` is applied in
`Dungeon.init`, so the class does reach the hero, and the score is probably driven by turns and
depth rather than anything class-specific. Not confirmed either way.

research.md:40 sets the first real milestone: *"Train the AI on a single seed until it can
consistently beat the first boss (Goo)."* The agent has never left floor 1, and because weights are
never persisted (1.3) no attempt has been resumable either. Nothing has been beaten.

**Seed text is capped at 20 characters.** `GameSettings.getString(key, def, maxLength)` discards
an over-long stored value and `SPDSettings.customSeed()` reads with a 20 character cap, so a
longer seed is silently dropped and the run falls back to a random one. `LevelPipeline.startRun`
now verifies the seed survived the round trip and fails with an explanation. Keep generated seed
text inside the cap.

---

## 2. Reward signals

Two different problems, and they were conflated until the first 20-generation
training run on the corrected update made them separable.

### 2.1 The agent learned to stall — **RESOLVED**

`meanScore` converged on exactly **-5.0**, which is `STALLED`'s terminal reward.
`meanTurns` collapsed 62 → 8. **The agent found a way to end an episode
deliberately, and ending it that way is twenty times cheaper than dying.**

The path is `WAIT` × 120 → `STALLED` → -5.0, against `deathPenalty` of -100 and
a `TURN_LIMIT` cost of -80. Every loss metric looked healthy throughout, which is
what made it worth writing down: the update was working and the objective was
wrong.

Three compounding defects, all fixed, with the analysis and reasoning in
[`PLAN-reward-signals.md`](PLAN-reward-signals.md):

| # | Defect | Fix |
| --- | --- | --- |
| 2.1.1 | `WAIT`, `REST` and `SEARCH` returned `false` from `ActionMapper.apply`, so `SPDEnv.step` classified valid actions as `INVALID_ACTION` | They return `true` |
| 2.1.2 | `STALLED` cost -5.0 — 20× cheaper than death, making a timeout guard the cheapest way out | Zero. Idling is priced by `TURN_COST`, which is what turn cost is for |
| 2.1.3 | `SPDEnv.settle` called `terminate(STALLED)` **twice**, charging -10.0 on that path | One call |
| 2.1.4 | `STALLED` was penalised *and* bootstrapped by GAE (`truncated = true`), so the critic never settled on it | Zero, and it stays truncated — the episode was cut off, not lost |

`rewardcheck` is a gate asserting the property that was violated: **`WAIT` ×
`stallLimit` must not come out cheaper than dying**, replayed through the real
environment rather than checked as constants. Mutation-tested.

**Measured over the same 20 generations at 8 workers:**

| | before | after |
| --- | --- | --- |
| meanScore, gen 19 | -4.46 | **+3.29** |
| meanScore, converged to | exactly -5.00 | no constant |
| meanTurns, gen 19 | 11 | **56** |
| valueLoss, gen 19 | 3.53 | **0.44** |
| stall share | 100% | 100% — but free, not profitable |

The last row is the honest one. **The agent still stalls every episode; it just
no longer profits from doing so.** `valueLoss` falling 3.53 → 0.44 is the critic
finally fitting its targets, which is what removing a penalty it could not
reconcile with a bootstrap should do. Depth is still 1. That is now 1.8.

> **Both columns of that table were correct and the conclusion drawn from them was
> wrong.** The agent was not choosing to stall. `REST` was a one-way door: it set
> `hero.resting` without setting a `curAction`, so `Hero.act` took the rest branch
> forever and never called `ready()`, and the recovery path of the time refused a
> resting hero because a *player* escapes rest by choosing another action. Every episode
> that rested was over. Instrumenting the two stall guards showed 36 of 36 stalls
> came from `LevelPipeline` and **zero** from the idle guard this section is about,
> at turns 10-51 rather than 121 — which is the whole tell.
>
> So 2.1's reward fix was correct on its own terms and none of the above measures
> the stall. `PLAN-reward-signals.md` §7 has the correction; §8 has two further
> faults the stall was hiding. Worth keeping in mind that every case in this
> section passed while the agent was trapped by something else — a check on
> reward arithmetic cannot detect a bug in the mechanism that builds the episode.

### 2.2 Reward terms that never fire

Declared in `reward.RewardTerm`, but with no emission site. Each is a documented requirement.

| Term | Document | Status |
| --- | --- | --- |
| `CRAFTED_MEAT_PIE` | docs.md:24, research.md:11+35 (called out specifically) | dead |
| `CRAFTED` | research.md:11 | dead |
| `CURSE_REMOVED` | docs.md:23 | dead |
| `EQUIP_TOO_STRONG` | docs.md:31, research.md:12 | dead |
| `KILL` | - | **not dead — tracked, but does not fire** |
| `MENU_NOOP` | research.md:57 (shop-loop guard) | dead |

**`KILL` is a special case and the row above is now wrong.** `RewardModel` already tracks `prevKills`
(it snapshots `Statistics.enemiesSlain`) but has no emission site for it, and
`RewardLedger.countKill()` is written and never called. So the plumbing is half-present rather than
absent — unlike `CRAFTED_MEAT_PIE` and friends, which have no observation point at all.

**Deliberately not fixed yet.** The emission site is small, but wiring it now would change the reward
function in the same run that just had its terminal reward corrected, and the result would be
uninterpretable. It waits for 1.4 — a run that can be judged.

Also relevant, and easy to misread: **four scripted 600-turn rollouts (`RS-1`..`RS-4`) produced only
`TURN_COST` and `TURN_LIMIT`**, scoring -1.25 to -1.40. But RL-policy training episodes have scored up
to **+44**, so positive terms *do* fire for the learned policy — the scripted heuristic simply does not
explore or loot. The reward model is not dead; the scripted policy is a poor proxy for it.

**Dead configuration.** `EnvConfig.allowAlchemy` and `EnvConfig.allowTrading` are read by nobody.
Alchemy and shops are reachable only because `Hero.handle` happens to resolve them.

These are all casualties of the state-diffing decision (see Deviations, D2). A diff cannot see
"an item was created in the alchemist", because the alchemy pot is a terrain tile and the result is
one item delta indistinguishable from a pickup. Fixing them needs real observation points in the
craft, equip and scroll/potion paths - i.e. the hooks research.md:8 actually asked for.

**Both are now externally configurable, which is not the same as fixed.** `env.allow_alchemy` and
`env.allow_trading` can be set in `superintelligence.properties` or through `SPD_ENV_ALLOW_ALCHEMY`,
and `configcheck` proves the values reach the settings they name - but nothing reads those settings, so
turning them changes nothing observable. That is deliberate: the key is visible in one documented place
rather than inferred from behaviour, and the dead wiring is a row in a table rather than a gap in the
source.

---

## 3. Missing subsystems

| # | Task | Document | Size |
| --- | --- | --- | --- |
| 3.1 | libGDX trainer UI in the `desktop` module | research.md:16, docs.md:10 | L |
| 3.3 | Live play with real-time reward/term monitoring | docs.md:10 | M |
| 3.4 | "type totals/subtotals" in the dashboard | docs.md:41 | S |

**3.2 is DONE.** Desktop replay viewer built: `gradle :desktop:replay --args="--file <replay>"`.
Recorded actions go through `ActionMapper` to `Hero.handle`, the same call the cell selector makes,
so action resolution is identical to a human playing. HUD shows step, seed, depth, actions, engine
time, live score with its per-step delta and gained/lost split, and divergence. Needed two engine
hooks: `Game.lockCellInput` and `Game.setSceneClass`. Enters via `InterlevelScene` because its
transition to `GameScene` is hardcoded, so a subclass would never be entered. See
`PLAN-replay-viewer.md`.

**And it now plays the corpus back faithfully**: `gradle :desktop:viewcheck` is green, 17 of 17, stable
across repeated runs. That took finding the fault below, which was not a viewer fault at all.

`diag/RunReport` already emits a per-term table and per-floor totals. "Subtotals" (a grouped
category rollup, eg. all combat terms together) is not implemented.

---

## 4. research.md sections not addressed

| # | Section | Status |
| --- | --- | --- |
| 4.1 | Garbage collection and memory pooling (research.md:60) | **Unaddressed.** |
| 4.2 | 1,000 simultaneous simulations (research.md:16, docs.md:42) | Never run at scale. |
| 4.3 | IMPALA as the alternative learner (research.md:26) | Not built. Listed as an optional alternative; decide whether it is still wanted now that the PPO path exists. |

4.1 in detail: the documents ask for an audit of the core game loop and pooling of volatile classes
(`FloatingText`, `Speck`). Zero references to either exist. I do pool `Transition` and reuse all
observation buffers in my own code, which measurably reduced allocation, but the game loop itself is
unaudited. This is the most likely thing to fail at high worker counts - see 4.2.

4.2 in detail: `Trainer` spawns worker JVMs over a binary stdin/stdout protocol. The path is now exercised at
two workers for two generations - handshake, params push, episode frames, weight push, replay write -
after the trainer was split into `Trainer` / `WorkerPool` / `Protocol` / `TrainOptions` / `Episode`.
What remains untested is `WorkerPool`'s parallel weight push under a real 20-worker pool, and whether
the per-worker buffers behave at that count.

The dead `byte[] payload = new byte[0]` initialisation in `pushWeights` is gone; it was replaced
earlier.

---

## 5. Known rough edges

Small things that are wrong but not blocking.

- **The reward curriculum is inert, and it is a design question rather than a bug.** `Curriculum` is
  fully written (fade from depth 3 to depth 10, `shapingScale()`) and `SPDEnv:325` applies it correctly
  to the ledger — but nothing ever calls `Curriculum.observe()`, so `deepestSeen` stays 0 and the scale
  stays 1f forever. On the trainer side `Trainer.curriculumScale()` returns a hardcoded `1f`, so the
  console prints a constant under the label `shaping=`.

  **Deliberately not "fixed" yet.** Wiring `observe()` is three lines and would make the label honest,
  but it would also start fading dense rewards by depth 3 — i.e. change the reward function — with no
  evidence that the dense terms help at all. The agent has never left floor 1, so the fade has never
  had a regime to act on. **Decide after 1.7**, when a run can be judged, and 1.4, when there are
  depths to fade across. Until then a wrong fade is worse than no fade, because it silently distorts the
  first real runs.
- `PPO.approximateKL` takes `t`, `logits` and `mask` parameters it does not use.
- Some engine states end a rollout as `STALLED` early. Seed `HERO` terminates after 2 turns, where
  most seeds run the full budget - an encounter reaching a state the action space cannot answer.
  Coverage is uneven across seeds.
- `TargetHealthIndicator`, `AttackIndicator`, `QuickSlotButton` and `GameScene` gained null guards
  for headless operation. Each is correct with a renderer present, but they are now load-bearing for
  a code path most contributors will never run.
- **`PPO.collect()` and `PPO.rollout()` are gone,** along with `rolloutCap`, `episodeInProgress` and
  `recurrentStateStale`. They had no callers once workers collected their own episodes, and leaving
  them would have meant two step loops to keep in agreement. `PPO` is the learner now, at 325 lines.
- **The `network` field on `Worker` is now only a parameter sink** — it holds the weights the collector
  uses, and nothing else reads it.
- `Worker`'s `network` field could be dropped and `EpisodeCollector` given the weights directly; it
  survives only because `readWeights` needs a `Network` to load into.
- **`GaeCheck` does not drive `EpisodeCollector`.** Its sampling checks restate the collector's two
  decisions - a uniform draw during collection, a rolling tail at the end - rather than exercising
  the loop that applies them, because that needs a live environment and a whole episode per case. A
  mutation inside the collector's retention logic would pass. Verified instead by observation: a
  multi-worker run reports a sampled count consistent with the tail dominating short episodes. Stated
  in the class comment rather than left implied.

---

## 6. Deliberate deviations

These were choices, not oversights. Each has a reason and a cost. Revisit any of them if the reason
stops holding.

### D1 - Single convolution layer, not a convolutional stack

**Was:** research.md:30 implies a CNN over the grid, plural channels.
**Now:** one 4x4/stride-2 convolution over all 21 planes, then fully connected.

*Reason:* dungeon rooms are not the same shape, so translation invariance across repeated motifs is
worth less here than the relationship between hero, walls and stairs. One layer gives local spatial
features at a fraction of the parameters.
*Cost:* less spatial generalisation. A real stack may learn "gap in wall" better.

### D2 - State diffing instead of hooks in the item and buff trees

**Was:** research.md:8 - *"placing hooks directly into the game's core logic packages"*, with
`Belongings.java`, `ScrollOfIdentify.java`, `Potion.java`, `Hunger.java` named.
**Now:** `RewardModel` diffs observable state between turns.

*Reason:* no edits to hundreds of item classes, and it measures what an action *achieved* rather
than which code path it took - a potion healing through a wand and a scroll healing through an
upgrade both read as "hero gained health".
*Cost:* **this is why every crafting, curse-removal and equip-rejection term is dead.** The diff
cannot distinguish alchemy from a floor pickup. If those rewards matter, D2 must be partly reverted
- see section 2.

### D3 - Truncated BPTT in the PPO update

**Was:** research.md:31 - an LSTM "strictly necessary" for memory.
**Now:** the LSTM is present and trained, but gradients are truncated at length one.

*Reason:* an exact through-time gradient needs every timestep's pre-activation state retained until
the update. At that sequence length this is gigabytes per worker.
*Cost:* the trunk, heads and critic get exact gradients; the LSTM gets exact gradients within a step
and none across steps. Whether this is enough to actually remember a dropped potion three rooms back
is an open empirical question, and it is the single assumption most likely to make the memory layer
fail.

### D4 - Worker processes, not threads

**Was:** docs.md:42 and research.md:16 imply 1,000 concurrent simulations.
**Now:** one run per JVM, pooled by `Trainer`.

*Reason:* not a preference. The game's simulation state is almost entirely static - `Dungeon.hero`,
`Dungeon.level`, `Actor.now`, `Level.visited`, the whole actor registry. Two environments cannot
coexist in one process.
*Cost:* ~1.5 GB and a JVM per worker. 1,000 workers is not practical on one machine; that needs
multiple hosts and a scheduler change.

### D5 - Console dashboard, not a libGDX UI

**Was:** research.md:16 and docs.md:10/41 both ask for a trainer interface.
**Now:** ANSI in a terminal, honouring `NO_COLOR` and non-tty output.

*Reason:* it works everywhere including CI logs, and it was buildable before the engine was.
*Cost:* the graphical trainer is not delivered - see 3.1.

### D6 - `verify`, not `replay`, for re-executing a recording

`ReplayIO.play()` was renamed `ReplayIO.verify()`, and the `replay` subcommand and Gradle task are
now `verify`. Nothing is drawn and nobody watches; the operation exists to catch the moment a
recorded path stops reproducing.

*Reason:* `play` implies a human watching the game, which is 3.2 and does not exist.
*Note:* the `Replay` *format* keeps its name. A recorded run that can be re-executed is a replay by
any normal definition, and the file magic is `SPD-REPLAY`.

### D8 - Four engine fixes proposed, then committed separately

**Was:** `instructions.md` §12.3, which keeps engine logic changes out of a commit unless proposed.
**Now:** applied to the viewer-fidelity fix rather than argued about in the abstract, and then resolved.
Four presentation draws moved from `Random` to `PRandom` — `CharSprite.link`, `AttackIndicator`,
`Wand.staffFx`, `MagesStaff`'s staff particle — one line each, none able to change an outcome. Plus
`Random.Int` recording its draws and `Random.shuffle(List)` routed through it, without which the trace
that was supposed to catch this was blind to the draws that caused it.

*Reason:* §12.3 exists precisely for this. The evidence went into `ISSUE-viewer-frame-drift.md` and
`FINDINGS-viewer-fidelity.md`, the proposal record into `ENGINE-CHANGES.md`, and the fixes were then
committed in their own commit once sanctioned.
*Cost, accepted deliberately:* the tree sat green on `verifyall` and red on `viewcheck` — 14 of 17 —
for the length of that proposal. A gate reporting a real fault is the correct behaviour; the delay was
the price of §12.3, and it is recorded here so the next reader knows the red state was chosen.

### D7 - Targeting and inventory as separate heads

**Was:** research.md:52 poses this as an open question - target every turn, or pause?
**Now:** the environment pauses (`EnvMode.TARGETING`, `SLOT`).

*Reason:* predicting a target on the ~95% of turns that are not aiming spends capacity and invites
spurious correlations. Keeping slot out of the flat action enum means output width does not change
with the hero's carrying capacity.
*Cost:* a step is not always one game turn, which complicates any tooling that assumes it is.

### D8 - Aim targets limited to the eight neighbours

*Reason:* shots resolve against a `Ballistica` collision cell, so a shot at range has to start from
one of these directions anyway.
*Cost:* the agent cannot express "throw at that specific far cell" without a two-step path.

---

## 7. Stale class names in the source documents

Worth knowing before either document is treated as a specification. They reference classes that do
not exist in this v4.0.1 checkout:

| Document | Referenced | Reality |
| --- | --- | --- |
| research.md:5 | `FogOfWar.java` (in `tiles`) | Exists, in the right package: `tiles/FogOfWar.java`. Reached through `Level`, not called directly. `Level.heroFOV` is what I read. |
| research.md:5 | `ShadowCaster.java` (in `mechanics`) | Exists, in the right package, but is reached through `Level`, not called directly. |
| research.md:5 | `InputHandler.java`, `ControllerHandler.java` (in core) | Not in core. They live in `com.watabou.input` in `SPD-classes`. Bypassed either way. |
| research.md:55 | `Alchemy.java` | Exists, as the blob at `actors/blobs/Alchemy.java` — not a scene. The pot is `Terrain.ALCHEMY`; the UI is `scenes/AlchemyScene`. Both readings of the reference are reachable, so this one needed no correction. |
| research.md:5 | `tiles` package | Exists. Eleven files, including `FogOfWar.java` and the tilemap classes. |

An earlier version of this table reported `FogOfWar.java`, `Alchemy.java` and the `tiles` package as
absent. All three exist; what is true is narrower — they are reached through `Level` and the scene
layer rather than called directly, which is why the literal instruction still did not apply.

The *intent* of every reference is achievable. The literal instruction is not always expressible.
