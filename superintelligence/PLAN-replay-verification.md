# Replay verification tooling - plan

Goal: give this project the ability to *prove* that a headless rollout and the rendered game are the
same simulation, and that the desktop viewer's own control flow works - without a human writing a new
throwaway probe for every question.

Status: **planned, not started.** Every claim in section 3 was read out of the source; section 9 records
which were then confirmed by running something.

---

## 1. Why this is necessary

Three faults got through every existing gate during the replay-parity work. Each one is a hole in the
*verification strategy*, not a mistake in a single file.

### 1.1 A gate that shares its implementation cannot see a fault in it

`HeroEncoder` built its defence observation feature with `hero.drRoll()`:

```java
out[ F_DEFENSE ]  = hero.drRoll() / 60f;
```

`drRoll()` is not a property of the hero - it is a fresh `Random.NormalIntRange` on every call. So
**encoding an observation advanced the game's RNG**, twice per agent step. The trainer's randomness
stream was offset from the rendered game's from the first floor, permanently.

No gate could see this. `collectcheck`, `verify`, `verifycheck` and `statecheck` all re-execute a
recording through `HeadlessGame` + `SPDEnv` - the same headless path they are checking. A fault that
lives in that path is invisible to all of them by construction. `collectcheck` did go red, but for a
downstream symptom, and red for a reason disconnected from fidelity.

A gate whose implementation is shared with its subject is not weak evidence. It is no evidence.

### 1.2 Code with no coverage at all

`ReplayPlayer` is ~560 lines that apply a recorded step, derive the mode, drain the scheduler, decide
whether a turn is owed, settle, and compare position/health/turn/inventory. **Nothing executes it
except the windowed viewer.**

Three real faults lived there:

| Fault | Consequence |
| --- | --- |
| `readyToAct()` omitted `curAction == null` | The viewer applied a step over a pending one |
| `driveToHeroReady()` spun 400 steps inside one `GameScene.update()` | Every recording stalled on the first attack |
| The viewer drained on aim-arming steps | A turn spent that the trainer never spent |

All three were found by hand, by diffing traces, because no test could reach the code.

### 1.3 Silent resource consumption by code that only reads

The same fault shape appeared three times, in three layers:

1. `HeroEncoder` - `drRoll()` to read a property (§1.1)
2. the particle system, emote icons, music, colour jitter, sewer ambience - `Emitter.start` takes
   `Random.Float(interval)` as its emission delay, and none of it can change an outcome
3. 34 `Sample.INSTANCE.play` calls computing a **sound pitch** with `Random.Float(...)`;
   `Hero.move` draws one per step, the terrain it lands on deciding which

Each was found serially, several instrument-and-run cycles apart, each time concluded to be *the* root
cause. Having found one instance of "X quietly consumes Y", the correct move is to audit all of X before
theorising again.

### 1.4 No oracle, and no cheap check

There was no artefact produced by the rendered game that headless runs are compared against. Every
question - "are the streams aligned?", "which call site draws first?", "does this fix help?" - required
hand-writing a new probe, and each probe was itself a source of false readings (a duplicated `env.step`
produced three fabricated failures; a line-by-line trace diff reported an actor-order difference that did
not exist; stale jars produced a `NoClassDefFoundError`, a `NoSuchMethodError`, and an NPE for a crash
already fixed).

---

## 2. What the tools must guarantee

1. **Independent oracle.** A check that can observe `ReplayPlayer` and the renderer without sharing code
   with either.
2. **Externally produced expectations.** At least one artefact must come from the rendered game, because
   a build-wide fault is invisible to any check that re-derives its own expectations.
3. **Cheap enough to always run.** Sub-second, no window, no human. A check that is expensive is a check
   that does not run.
4. **Able to fail.** Every check needs mutation cases: alter something and assert the failure is
   reported. `VerifyCheck` already models this correctly and it is the template.
5. **Unambiguous about staleness.** "Is my change even live?" must never be a question. `desktop:replaycp`
   already documents this failing once (outputs with no inputs; gradle up-to-date; launcher pointing at a
   stale jar). It happened again three times during this work.

---

## 3. Constraints, read out of the source

| # | Finding | Evidence |
| --- | --- | --- |
| C1 | `ReplayPlayer` has **no UI dependency**. Its only `GameScene` mentions are in comments; `halt()` writes to stderr and sets a flag that `ReplayController` reads | `ReplayPlayer.java` - no `GameScene.`/`ReplayController.` call sites |
| C2 | `desktop` already depends on `:superintelligence` and `:core`, so a driver there can use `HeadlessGame`, `SPDEnv`, `ActionMapper` with no new module edges | `desktop/build.gradle` |
| C3 | World setup for a replay is `SPDEnv.reset(seedText, heroClass)` -> `LevelPipeline.startRun`. `ReplayPlayer.restart()` only rewinds *playback* - it does not rebuild the world | `SPDEnv.java:110`, `ReplayPlayer.java:546` |
| C4 | `ActionMapper` reads exactly two config values: `config.maxSlots` and `config.allowEquipping` | `ActionMapper.java:66,308` |
| C5 | **Neither is recorded in `Replay`.** A replay cannot guarantee it resolves slot indices the same way | `Replay.java` fields; `ReplayIO` header writes |
| C6 | `ReplayRecorder` is already a complete fixture source: `record()` captures action/slot/mode + quickslots for `SLOT`/`INVENTORY`; `afterStep()` captures position/hp/turn/inventory | `ReplayRecorder.java:40-64` |
| C7 | Existing gates record their **own** fixtures via `ReplayRecorder` + `ScriptedPolicy` - structurally unable to catch a build-wide fault | `VerifyCheck.recordAShortRun`, `VerifyCheck.java:67` |
| C8 | `Main rollout --seed ""` writes an **empty** seed, so such recordings cannot be verified at all | verified by running it |
| C9 | `cliTask` + a `gates` aggregator exist in `superintelligence/build.gradle`; `desktop` has `replay`/`replaycp` tasks to model on | `build.gradle` in both |

C1 and C2 together are what make T1 possible. If `ReplayPlayer` had any scene dependency, T1 would need
a different shape entirely.

---

## 4. T1 - Headless playback verifier

**Purpose:** an independent oracle for `ReplayPlayer`, covering the 560 lines nothing executes.

A driver with no dependency on `ReplayController` and no window:

```java
HeadlessGame game = HeadlessGame.install();
SPDEnv env = new SPDEnv( configFor( replay ), game );
env.reset( replay.seedText, HeroClass.valueOf( replay.heroClass ) );

ReplayPlayer player = new ReplayPlayer( replay, configFor( replay ) );
player.speed( 16f );                       // so the pacing timer is not the limit
while (player.playing() && frames < budget) player.update( 1f / 60f );

assert !player.playback().diverged();
assert player.haltReason().isEmpty();
```

`ReplayPlayer`'s constructor already sets `Actor.manualScheduling = true`, so the scheduler is
unambiguous with no scene present (C1). Restarting means re-running `env.reset` (C3), not
`player.restart()`.

**Prerequisite:** the constructor takes an `EnvConfig` instead of hardcoding `new EnvConfig()`
(`ReplayPlayer.java:120`). Without it the driver and whatever produced the recording can disagree on
slot resolution (C4) and the verifier will report phantom divergences.

**Gate** `desktop:playbackcheck`, modelled on `VerifyCheck`:

- record 2-3 short runs with `ScriptedPolicy` + `ReplayRecorder` (C6)
- assert each plays clean end to end
- **mutate** a recorded step - position, hp, turn, inventory, quickslots - and assert the driver reports
  that divergence

Without the mutation cases this is a check that cannot fail, which is the criticism that applies to
`collectcheck`.

**Also:** refuse an empty seed with a clear message (C8) rather than diverging at step 0.

**Would have caught:** the attack deadlock, `readyToAct` missing `curAction`, `owesNoTurn` firing on the
consuming step rather than the arming step, `settle()` skipped across a yield, quickslot restore order.

**First thing it will catch:** the probe already built for section 11 diverges at step 3 of a recording
the windowed viewer plays to step 33, because `ReplayPlayer` omits the
`GameScene.clearPendingCellListener()` / `SlotAction.clearPendingUseItem()` that `SPDEnv.step` performs.
That divergence is currently masked by the presence of a live `CellSelector`. Fixing it is T1's first
job.

---

## 5. T2 - Observation purity gate

**Purpose:** make the invariant *"observing the world must not change it"* executable.

A draw counter on the base generator in `Random`, free when its property is unset. One assertion:
encode the same unchanged world twice through `ObservationEncoder.encode` and assert the counter did
not move. Siblings for `ReplayRecorder.inventory()` and `Quickslots.capture()`, which are also read
paths.

Mutation-verified: reinstating `hero.drRoll()` in `HeroEncoder` must fail this gate.

**Would have caught §1.1 at the moment it was introduced**, rather than after the RNG stream, the
particles, the emote icons, the music, and the sound pitch had all been blamed in turn.

---

## 6. T3 - RNG stream fingerprint oracle

**Purpose:** catch *any* future divergence between headless and rendered randomness automatically,
instead of by hand-diffing traces.

Fold every base-generator draw into a running checksum in `Random`; expose `draws()` and `checksum()`.
Presentation randomness already lives on `PRandom`, so this measures gameplay randomness only - which
is exactly the quantity that must match.

Two emitters, one output format (`step,draws,checksum` per replay step):

- headless, via a new `rngtrace` subcommand - cheap, runs anywhere
- rendered, via `ReplayPlayer` behind `-Dspd.rngTrace` - needs a display

The artefact is golden traces committed under `replays/`. The gate asserts headless equals golden and
reports the **first** differing step.

**Caveats, stated rather than hidden:**

- Generation needs a display, so regeneration is a deliberate manual step.
- *Any* engine change invalidates the golden. This is a nuisance gate that people will reflexively
  regenerate, which would destroy its value. Mitigation: regeneration needs an explicit `--regenerate`
  flag and the diff must name the first differing step, so regenerating is a decision rather than a
  reflex.

**Recommendation:** build it, but land it as a gate only once the recording corpus is stable. Before
that it is worth more as a diagnostic.

---

## 7. T4 - One command, one truth

**Purpose:** remove the stale-build ambiguity that cost three cycles.

A `verifyall` task depending on every gate (superintelligence's 16 plus `playbackcheck`) and on the jar
and classpath tasks, printing a table of gate -> pass/fail and whether anything was actually rebuilt. It
should verify classpath freshness rather than trusting gradle's up-to-date decision, which is the exact
failure `replaycp` documents.

---

## 8. Foundations required before any of it

1. **Record the config that changes replay meaning.** Add `maxSlots` and `allowEquipping` to the replay
   header (C4, C5), bump `Replay.VERSION`, and have `Replay` reconstruct the config. Without this a
   recording can resolve slot indices differently on replay, and every replay-based check inherits the
   ambiguity.
2. **`ReplayPlayer` takes an `EnvConfig`.** Currently hardcoded (§4).
3. **State the invalidation.** Existing checkpoints and recordings were produced against a corrupted RNG
   stream and are void; the CHANGELOG already says so for the first two faults, and this must extend to
   the config change.

### 8.1 F1 - the viewer omits the trainer's per-step preamble

**Found by validation (§11), and fixed.** `ReplayPlayer.applyNextStep` dispatched without the
bookkeeping `SPDEnv.step` performs at :199-208, and called `mapper.refreshSlots()` *after* dispatch
rather than before.

The missing `GameScene.clearPendingCellListener()` is the one that mattered:

```
SlotAction.use(:93-98)      re-arms GameScene's aim listener when an item wants an aim
ActionMapper.resolveTarget  prefers that listener over the sprite-free castAt fallback
SPDEnv.step(:199)           clears it every step, so trainer throws always use castAt
ReplayPlayer                never cleared it, so the throw used the game's own missile path
                            -> recycles a sprite from hero.sprite.parent
                            -> no parent without a scene -> throw threw, turn never advanced
```

Measured: 229-step `RF` reported `USE/0 in TARGETING: engine time is 0.0, recording says 1.0` at step 3,
where the windowed viewer plays to 33. With the preamble mirrored, all 229 steps play with no
divergence in 55 ms.

The window hid it because it has a live `CellSelector` to aim with. **This is the argument for T1 in one
line: a viewer bug that a window masks is invisible to every test that needs a window.**

Fixed in `6d2f21e3c`.

### 8.2 F2 - an empty seed is silently unrecoverable

**Found by validation (§11).** `rollout` with an empty seed writes a header with no seed value, and
`verify` on that file then dies with an unhandled null-valued expression, five times over, naming
neither the file nor the step.

Needs guards in **both** commands: reject an empty seed in `rollout` before recording, and reject one in
`verify` before booting, each naming the file and the field. A check that cannot report *why* it failed
is the same failure as no check.

---

## 9. Sequencing

| Order | Item | Why there |
| --- | --- | --- |
| 0 | **F1** (§8.1) | A viewer bug the window masked; fixed, and it is what proved T1 viable |
| 1 | Foundations (§8) | T1 is not trustworthy without them |
| 2 | **F2** (§8.2) | Guards in two commands, independent of everything else |
| 3 | T2 | Hours, no dependencies, immediate net on the fault class that cost the most |
| 4 | T1 | The real prize: an independent oracle plus a mutation-proof gate |
| 5 | T4 | Small; makes everything above cheap to run |
| 6 | T3 | Only as a gate once the corpus is stable |

---

## 10. Open decisions

1. **T3 as gate or diagnostic?** Recommendation: diagnostic first.
2. **Corpus policy.** The repo has 5 fixtures in `replays/`. Proposal: 8-12 recordings across hero
   classes and shapes (short, death, stall, multi-floor), regenerated when the engine changes. Checked
   in, or generated on demand and compared by fingerprint?
3. **T1's budget** - small fast corpus as a gate, larger corpus as a periodic job?
4. **Scope** - all four, or T1 alone first, demonstrated catching a live divergence before the rest?

---

## 11. Validation log

Each entry: what was claimed, how it was checked, what came back. Checks ran against `f4d927a85`.

### C1 / C2 - `ReplayPlayer` is drivable headlessly - **CONFIRMED by execution**

A probe outside the repo booted `HeadlessGame`, built a world, and drove `ReplayPlayer` with no
window, no scene and no `ReplayController`:

```
loaded 229 steps, seed=RF hero=WARVIOR
world built, hero at 643
finished after 3 frames in 7ms
haltReason=DIVERGED at step 3 - USE/0 in TARGETING: engine time is 0.0, recording says 1.0
```

It loaded, built, applied, compared, detected a divergence and reported it - in 7 ms. T1 is viable
with no refactor of `ReplayPlayer` to make it headless-testable.

### **NEW FINDING** - the probe found a viewer bug the window masks

The windowed viewer plays `RF` to step 33. The headless driver diverges at step 3. Step 2 is the
`TARGETING` throw, which the recording shows spending a turn (0.0 -> 1.0); headless spends none.

Cause: `SPDEnv.step` performs per-step bookkeeping that `ReplayPlayer` never does.

| `SPDEnv.step` | `ReplayPlayer.applyNextStep` |
| --- | --- |
| `GameScene.clearPendingCellListener()` (:199) | absent |
| `SlotAction.clearPendingUseItem()` (:205) | absent |
| `mapper.applySecondary(...)` | present (:414, :416) |
| - | `mapper.refreshSlots()` (:421) - extra |

The windowed viewer gets away with this because a live `CellSelector` exists, so aim requests never
route through `pendingCellListener`. Headless has no selector, so they do, and the throw never
resolves.

This is the sharpest argument for T1: the viewer and the trainer do **not** perform the same
per-step state transition, and only running the viewer headlessly makes that visible. It should be
the first thing the new gate covers.

### C3 - world setup / restart semantics - **CONFIRMED**

`ReplayPlayer.restart()` rewinds `ReplayPlayback` only; it never touches the world. A driver that
wants a second pass must call `SPDEnv.reset` again.

### C4 / C5 - config not carried by the replay - **CONFIRMED**

`ActionMapper` reads `config.maxSlots` (:66) and `config.allowEquipping` (:308). Searching
`Replay.java` and `ReplayIO.java` for `maxSlots|allowEquipping` returns **nothing**. The header
carries version, seed, hero, challenges and turn limit only.

### C6 - `ReplayRecorder` is a complete fixture source - **CONFIRMED**

`ReplayRecorder.java:56,61,62,63` assign `step.heroPos`, `step.heroHp`, `step.turn`, `step.inventory`;
`record()` adds action, slot, mode and quickslots. Enough to build T1's fixtures with no new capture
code.

### C7 - gates share the path they check - **CONFIRMED**

`Main.java:138` and `Main.java:227` both call `HeadlessGame.install()`. Every gate re-executes through
the headless path, which is what makes §1.1 invisible to all of them.

### C8 - empty seed is unrecoverable - **CONFIRMED, and worse than stated**

`rollout --seed ""` writes `seed=` and nothing else:

```
header: SPD-REPLAY
header: version=1
header: seed=
```

Running `verify` on that recording does not fail gracefully. It raises an unhandled
`InvalidOperation: You cannot call a method on a null-valued expression` - five times, with no
indication of which file or step was at fault. So the claims in section 3 that this needs a clear
message are an understatement: it needs both the guard in `rollout` and one in `verify`.

### Summary

Nothing in section 3 was wrong. The one claim I would have had to soften - "the viewer's control
flow is untested" - is worse than untested: it is *differently tested by its only execution
context*, and that context is masking a real divergence from the trainer.