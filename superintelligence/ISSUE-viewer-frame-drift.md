# Viewer fidelity: recordings diverge in the rendered viewer but verify clean headlessly

**Status:** FIXED. `gradle :desktop:viewcheck` is green on the whole corpus and stays green across
repeated runs. Cause found and measured; the fix is four presentation draws moving off the gameplay RNG
stream. Those four are engine changes under `instructions.md` §12.3 and are committed, with the
proposal record in `ENGINE-CHANGES.md` — see *Definition of done*.

**Module:** the fault was in the game, not in `:superintelligence`. Two of the four sites are in
`:core` (`CharSprite.link`, `AttackIndicator`) and two more (`Wand.staffFx`, `MagesStaff`'s particle).
Everything that observes it lives here, which is why this file is here rather than in the engine.

---

## The problem, as it stood

The committed corpus of 17 recordings verified **clean headlessly, all 17**, and diverged in the
**rendered viewer** for **14 of them**.

Two consecutive `viewcheck` runs on the same machine, same corpus, same build:

| Run | Count | Failing recordings |
| --- | --- | --- |
| 1 | 14 of 17 | `cleric-mid`, `cleric-short`, `duelist-mid`, `duelist-short`, `huntress-mid`, `mage-alt`, `mage-mid`, `rogue-mid`, `rogue-short`, `warrior-alt`, `warrior-death`, `warrior-long`, `warrior-mid`, `warrior-short` |
| 2 | 14 of 17 | the same fourteen |

The failing *membership* was stable, which the earlier version of this file said it was not. The
failing *step* was stable too. But the failing **field value** was not: replaying `mage-mid` four
times gave `hp is 16` twice, `hp is 17` twice and `hp is 20` once, all at step 17. A gate that reports
only a step index could not see that, and it is why "did my change help" was unanswerable.

The failures were not rounding errors:

```
[replay] halted: DIVERGED at step 17 - MOVE_N/0 in WORLD: hp is 19, recording says 20
[replay] halted: DIVERGED at step 15 - INTERACT/0 in WORLD: engine time is 11.0, recording says 12.0
```

**Why this mattered more than a broken viewer.** The viewer's purpose is inspecting training runs —
"what did the policy actually do on that run" is answered by playing it. If playback is not faithful
then every question asked of a recording is answered about a run that did not happen, and the failure is
silent: it looks exactly like a run that died to a mob it should have dodged. The RL loop itself was
unaffected, so nothing caught it except a gate excluded from the normal suite for needing a display.

---

## The cause

**Four presentation draws were spending the gameplay RNG stream, and the rendered viewer is the only
environment that makes them.**

`Random.reseedBase` seeds the base generator from the run seed, so its output is computable. Generating
its first few hundred values from the recorded seed and looking up where each observed draw falls:

```
warrior-long, seed 2547414048295, scrambled 2466687093574357806

headless first gameplay draw = generator value #5
viewer   first gameplay draw = generator value #17
```

Both report `generationBaseDraws = 0`. Both reached the first recorded step with the base generator
freshly seeded and — on the trace's own account — never drawn. They were in fact 12 values apart
(zero-indexed positions 4 and 16; the same pair as "#5 and #17" above).

The sites, all presentation, none of them able to affect an outcome:

| site | viewer-only draws | what it decides |
| --- | --- | --- |
| `CharSprite.link:156` | 12 (base) | the random facing a sprite is given when it is linked |
| `AttackIndicator.checkEnemies:130` | 1 per step (base) | which mob the attack overlay highlights |
| `Wand.staffFx:453` | per cast (base) | which way a cosmetic particle flies |
| `MagesStaff.StaffParticle.update:558` | per frame (base) | cosmetic size jitter on a particle |
| `DungeonTileSheet.setupVariance:485` | 962 (pushed) | alternate tile visuals — inside `pushGenerator`/`popGenerator`, so inert |

`CharSprite.link` accounts for the whole base count: `HeadlessSprite` overrides `link()` and so never
reaches that line, which is precisely why the trainer never made the draw and the viewer always did —
12 values, once per actor the viewer attaches a real sprite to.

All four are now `PRandom`, the stream created for exactly this and already used by every other
presentation draw in the game. `DungeonTileSheet` was left alone: it draws inside a pushed generator
that is discarded.

**What that produced.** Every damage roll, defence roll and mob decision after the first draw came from
a different point in the stream than the recording was made from. On `warrior-long` the hero's recorded
`INTERACT` at step 13 became an attack that left the rat at 3/8 in the trainer and killed it in the
viewer, because one roll differed by enough. Everything downstream — health, position, engine time — was
that, once, and nothing else.

**What it was not.** Not a scheduler fault, not an ordering fault, and not extra turns. All of those
were proposed, and all of them were measured and refuted; `FINDINGS-viewer-fidelity.md` keeps the
refutations and the measurements.

---

## Why it went unnoticed, and for so long

Two instruments were wrong, and both were wrong in the same way: each reported agreement for less than
it claimed to compare.

**The RNG trace could not see the draws that caused the fault.** `Random.Int(int, boolean)` and
`Random.shuffle(List)` advanced the generator and never called `RandomTrace.record`. Only `Float()` and
`Long()` were counted. So `RngTrace` reported identical `baseDraws` at every step of every recording —
and `PLAN-replay-parity.md` §1.4 concluded from that, correctly given the instrument, that "the RNG
stream is not offset". Every hypothesis after that was about ordering, because a counter that could
not see the offset said there was none.

**The world diff never named the hero field that differed.** `WorldSnapshot`'s hero row joins its
fields with spaces while every other row joins with tabs, and `WorldDiff.fields()` split on tab only.
Every hero record therefore parsed as one key: the comparison of the record that carries position,
health and engine time — the one every divergence is decided on — was a whole-row compare reported as a
difference in `pos`. Measured, and it changed the answer: on `warrior-long` the sides differ at step 13
in exactly one hero field, `exp`, and the tool reported `pos` for a position that was identical in both.

Both are fixed. `observecheck` now asserts the first directly — all 17 public draw paths must move
the counter, mutation-tested — and `WorldDiff` splits on whitespace as well as tab.

---

## What is fixed, and how it was verified

- **The cause.** 17 of 17 play clean in the real viewer.
- **Reproducibility**, which the issue's own definition of done required and which did not hold before:
  two consecutive full `viewcheck` runs, plus every recording replayed three times individually, give
  the same verdict all 17 times each. The two recordings whose *field value* varied between runs no
  longer vary.
- **The named observability gaps.** Step acceptance is no longer discarded (`ReplayPlayer` counts
  refusals instead); per-frame records carry which actor advanced engine time and by how much; the
  generation window's draw tally is written into the RNG trace so the two sides' can be diffed;
  `WindowBridge` reads the live window when a scene exists, so a recorded `MENU` step is answerable in
  the viewer instead of silently resolving against nothing; `ReplayRecorder.inventory()` is
  path-qualified, so a bag's contents are compared rather than ignored.
- **The two-sided diff**, as one command. `gradle :superintelligence:viewdiff` reads a headless and a
  rendered snapshot and names the first differing step *and field*, plus the frames on which the world
  moved with no recorded step applied. This replaces a hand-written Python script that had itself been
  wrong twice.
- **`diag/StepTrace`**, deleted. `worldtrace` emits strictly more with a comparator attached and a gradle
  task; `StepTrace` had neither and was labelled TEMP in source.
- **A trap in `regenerate-corpus.ps1`.** It appended the freshly compiled classes *after* the stale
  `:superintelligence` jar on the classpath, so a jar silently shadowed the build the script had just
  made. It regenerated the corpus with old code and the gate then reported ten divergences that had
  nothing to do with the fault under test. Prepended.

---

## Suggestions, and what became of them

| # | Suggestion | Outcome |
| --- | --- | --- |
| 1 | Make it reproducible first | Done, and it held: membership and step were stable before any change; only the field value varied, which is now also stable |
| 2 | Settle whether the viewer should own the scheduler at all | Settled by measurement rather than by argument. The real game parks its scheduler; `headlessStep` does not; and it makes no difference to fidelity. The fault was the RNG stream |
| 3 | Observe frames, not just steps | Done. Frame records carry the drain outcome, the gate's inputs, which actor advanced engine time and by how much, and the two clocks |
| 4 | Finish the two-sided trace | Done, as `viewdiff`. `StepTrace` deleted |
| 5 | Stop discarding step acceptance | Done. `ReplayPlayer` counts and reports refusals |
| 6 | Decide whether `viewcheck` can ever be a gate | Decided **no**, and written down rather than left implicit: it needs a display. It is a manual gate at 203s sequential |

---

## Open questions, answered

- ~~Is the frame-driven scheduler advance a viewer bug, or is the trainer's assumption wrong?~~ Neither.
  The scheduler behaves the same on both sides; four presentation draws were on the wrong stream.
- ~~Should the viewer be faithful or merely watchable?~~ Faithful, and it now is. Nothing about the fix
  trades fidelity for watchability.
- ~~Does the `WindowBridge` / `headlessWindow` asymmetry affect any committed recording?~~ It could
  not: no recording in the corpus contains a `MENU` step. The asymmetry was real and is fixed anyway,
  because a viewer that cannot answer a recorded menu step is broken for any run that has one.
- ~~Is the failing membership varying, or the step within a stable set?~~ Both were stable. The
  *reported field value* was not, on two recordings, and is now.
- ~~Why do the five recordings failing in both runs fail?~~ All fourteen had the same cause.

---

## Definition of done

- [x] `gradle :desktop:viewcheck` is green on the full corpus — 17 of 17 — and stays green across
  repeated runs on one machine, which is the part that was not true before.
- [x] A named recording fails the same way, twice, before and after a change. `warrior-long` is the
  before-picture: `DIVERGED at step 15 - engine time is 11.0, recording says 12.0`, twice, and its
  full 126-step trace is compared against the headless one.
- [x] The observer reports on the render-loop behaviour it was added for. The frame records name which
  actor advanced engine time and by how much; `viewdiff` names frames that moved with no step applied.
- [x] **The fix itself is committed.** Four engine changes — `CharSprite.link`, `AttackIndicator`,
  `Wand.staffFx`, `MagesStaff`'s particle — move a draw from `Random` to `PRandom`, plus
  `Random.Int` recording its draws and `Random.shuffle(List)` routed through it. Each draw change is
  one line, none can change an outcome, and together they are the whole difference between the two
  states. Proposed in `ENGINE-CHANGES.md`, which also records what was deliberately left on the
  gameplay stream and why.
