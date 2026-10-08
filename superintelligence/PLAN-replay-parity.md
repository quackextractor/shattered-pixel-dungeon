# Replay parity follow-up - plan

Follow-on from `PLAN-replay-verification.md`, which is built. That work produced the tooling; this
document is the work the tooling found.

Status: **items 1, 2, 3, 4, 5 and 7 are done.** Item 1 did not find the fault it went looking for - it
disproved the hypothesis and produced a reproduction instead, which is section 1.5. Item 6, retraining,
is not started and is deliberately last: everything a checkpoint would learn from is still moving.

---

## 1. The remaining fault: execution-path-dependent draw indexing

### What is known

`verify` reproduces a 41-step recording exactly. `gradlew :desktop:replay` playing the same recording
diverges on health at step 27, and its RNG trace diverges from the headless trace at step 6 while the
**per-step draw counts are identical at every step**:

```
step | headless draws | windowed draws | same count? | same fingerprint?
6    | 1               | 1              | yes         | NO
7    | 2               | 2              | yes         | NO
```

Same count, different value. The first reading of this was that the renderer draws ~2800 values the
headless path does not. **That reading was wrong, and the tooling was what proved it.**

### 1.1 What call-site attribution actually found

`RandomTrace` now has an attribution mode: it infers the calling site from the stack and tallies draws
per site, and the viewer dumps the tally for the window before the first replayed step.

Measuring both sides gave:

| | headless generation | rendered pre-step window |
| --- | --- | --- |
| total draws | 2803 | 2805 |
| top site | `Patch.generate:56` — 2496 | `Patch.generate:56` — 2496 |

Identical. The renderer does **not** draw thousands of extra values. The one site-level difference in
the whole window:

```
=>  Dungeon.seedForDepth:431  3   (rendered)
<=  Dungeon.seedForDepth:431  2   (headless)
=>  Dungeon.seedForDepth:433  3   (rendered)
<=  Dungeon.seedForDepth:433  2   (headless)
```

Two extra draws, from `seedForDepth` being called once more in the rendered path.

### 1.2 And those two draws cannot matter either

`Dungeon.seedForDepth` draws inside `Random.pushGenerator(seed)` / `popGenerator()`, and
`pushGenerator(long)` constructs a **brand new** `java.util.Random` (Random.java:71). It is a pure
derivation: skip ahead on a private stream to derive a per-depth seed. Drawing on it cannot change any
outcome, in either direction.

### 1.3 Two instrument bugs this exposed

Both found only because the tool was checked against a case where it should have reported "no
difference".

**The fingerprint folded scratch draws into a gameplay comparison.** The first fingerprint covered every
draw, including those on pushed generators. Since the viewer asks for one more per-depth seed than the
headless path, a byte-identical simulation reported as divergent at step 6. The trace now fingerprints
the gameplay stream only.

**Base and pushed were classified backwards.** `baseDraws` was fed `!useGeneratorStack`, which answers
"was the far end of the deque read", not "was the base element read". On an empty stack both ends are the
same generator, so an ordinary gameplay draw was counted as *non*-base — and `baseDraws` sat at **zero
for an entire run**, which reads exactly like a simulation that consumes no randomness at all. It now
compares generator identity (`peekFirst() == peekLast()`), which is the question actually being asked.

### 1.4 Where that leaves item 1

The RNG stream is **not** offset. That hypothesis is dead, and it was the reason the previously open
behavioural question looked unanswerable — so the sequencing argument in section 1 collapses, and the
behavioural question is back on the table rather than blocked.

One architectural fact fell out and is worth recording: with base and pushed now distinguished
correctly, `baseDraws` is **zero across a whole 41-step run**. Almost every draw this game makes —
generation, painting, item rolling, throws — happens inside a `pushGenerator`/`popGenerator` pair, with
the persistent generator frozen and each subsystem deriving a private stream from `Dungeon.seed` or
`seedCurDepth()`. So the meaningful parity invariant is not "how many values did the base stream
produce" — it is that each subsystem's *derivation* is identical, which state comparison already
observes directly.

Which means the remaining fault is behavioural, and `rngtrace` has said everything it can. The next
step is to locate the step-27 health divergence by state rather than by randomness.

### 1.5 The fault, now reproducible

Rebuilding the corpus produced a minimal reproduction, which the previous work never had: a recording
made by the *current* build that does not replay.

```
verify: seed=mage-long hero=MAGE steps=119
[ERROR] replay diverged at step 17 of 119
        position: replayed 627, recorded 1181
        action MOVE_NW slot 0 mode WORLD
```

The recorded positions either side of it:

```
step16: MOVE_W  WORLD pos=1146
step17: MOVE_NW WORLD pos=1106
step18: MOVE_NW WORLD pos=1181     <- recorded 1181, replayed 627
```

A single north-west move takes the hero from 1106 to 1181, which is not a walk in any floor geometry,
and replaying it lands at 627 instead. So the divergence is not a small numeric disagreement that
accumulates: something happens on that step which the replay does not reproduce at all.

That is worth stating plainly because it reframes the remaining work. The hypothesis this plan opened
with — a thousand-value RNG offset shifting every downstream draw — was wrong, and the tool that
disproved it also produced the actual reproduction. **Seven of the eight recordings rebuild cleanly and
reproduce; one does not.** That is a much smaller surface than "the engine is subtly non-deterministic",
and it is now a specific step in a specific file.

Next step: read what `MOVE_NW` at that cell can reach. The likely candidates are the ones that relocate
a hero without a move action — a pit, a teleport, or a floor transition — because those are the paths
that produce a position the recording cannot explain by walking.

### Design

Attribute draws to call sites, then fix the sites.

1. **Extend `RandomTrace`** with an optional attribution mode. On each draw, infer the calling site from
   the stack and fold it into a per-site tally. This is the established technique: wrap the draw, derive
   `site` from the stack, aggregate `Counter(by_site)`. The one-line discipline that comes with it is
   *"fix site class, not index" - do not correct an offset by adjusting indices downstream.
2. **Report** draws-per-site over the window between level generation and the first replayed step, which
   is where the offset lives.
3. **Fix** whatever sites are drawing. If they are presentation, they belong on `PRandom`, which is the
   treatment already applied to particles, emote icons, music, colour jitter, sewer ambience and sound
   pitch. If they are gameplay-relevant generation happening only when a scene renders, that is a deeper
   fault and needs its own decision.

### Not doing, deliberately

The literature also offers the alternative remedy: replace the stateful PRNG with a **counter-based /
event-keyed** generator, so each draw is a pure function of a stable event identity rather than of
execution history. That is the principled fix for draw-index shift and it would make the offset
impossible by construction.

It is also a rewrite of the randomness substrate for the whole game, and it would invalidate every
recording, checkpoint and metric at once. It is the right answer *eventually* and the wrong answer
*now*. Noted so the decision is on the record rather than rediscovered later.

### Design already validated by this research

The existing trace design matches established practice and is kept:

| Here | Established practice |
| --- | --- |
| cumulative draw count per step | *"every RNG call increments a counter... 'the bug triggers after RNG call 847'"* |
| order-sensitive fingerprint alongside the count | per-draw 64-bit fingerprint compared draw-by-draw on a second run |
| report the first diverging draw | *"naming the first diverging draw"* |
| canary, not a full trace | *"it is a canary, not a trace: it tells you the runs diverged and at which draw, and the call-count tools take it from there"* |
| a short run that stops early is a failure | *"a replay that matches a prefix and then stops early fails too: the leftover record is the evidence"* |

One refinement available from that source and worth taking: the fingerprint can be taken from a probe on
a *clone* of the generator, so probing costs no randomness. Folding the drawn value is equivalent in
effect here because the trace already excludes the probe from the stream, so the change is not worth the
cloning.

---

## 2. A run that ends on the soft stall guard replays as a stall

`SPDEnv.checkFloorLimits` terminates when the hero's position and health have not changed for
`stallLimit` consecutive turns. The recording is complete and the hero is alive, but the viewer has no
environment to ask, so after the final step it waits for a hero that will never become ready and reports
`stalled - hero did not become ready within 400 turns`.

The header records no termination reason, which is the actual gap.

**Design.** `Replay` gains a termination reason; the recorder writes it; the viewer finishes rather than
draining when a recording ends in a termination it can see. Bump the header version. `ReplayIO.configFor`
is not involved - this is a field, not a setting.

**Precedent in the codebase.** `RewardModel.TerminateReason` already enumerates exactly these cases, and
`Replay.captureOutcome` already records score, depth, turns and generation at the end of a run. The
termination reason belongs beside them, written at the same moment.

**Rejected.** A guard mirroring `turnsThisFloor >= turnLimitPerFloor` was written and removed. The stall
guard fires first in practice, so no fixture could exercise it, and shipping a check that cannot be shown
to fail would contradict the argument the tooling exists to make.

---

## 3. `Main.rollout` is not gate-covered

`collectcheck` guards `checkRandomSeedEpisodeIsReplayable` for the collector path that training uses.
`Main.rollout` is a separate path and is not guarded - which is exactly where a real regression lived: the
empty-seed guard rejected the flag *and its absence*, making random seeds unreachable, and `rollout`
recorded the seed it was asked for rather than the seed the environment resolved.

**Design.** Extract the rollout loop from `Main` into something callable, so a gate can assert on what it
records without invoking a CLI. The gate covers: a random seed is resolved and recorded, a recording made
from a random seed verifies, an explicitly empty seed is refused, and a recording always carries a
non-empty seed.

**Anti-goal.** Do not duplicate the loop. A second implementation of "roll out an episode" is how the
viewer and the trainer drifted apart in the first place.

---

## 4. The committed replay corpus is dead

All five files in `replays/` are header version 1 and predate the RNG repair, so they diverge when
verified. They stay *readable* - v1 parses with defaults - which preserves the tooling fixtures and not
their fidelity.

**Design.** Depends on item 1. Once the streams agree, regenerate. Settle the policy question at the same
time; see section 6.

---

## 5. Documentation audit

Stale `recoverStrandedHero` references survive in `ActionMapper` and `RewardCheck`, describing a method
that no longer exists. More broadly the brief is: documentation states what the code does, not how it got
there. Remove chronology, narrative about the investigation, assumptions, and mistake logs - including in
the comments added by this work.

**Design.** Fix references that are factually wrong first, since those mislead a reader who goes looking
for the method. Then sweep the files touched by this work for narrative and remove it.

---

## 6. Retraining

Checkpoints and metrics were produced against a corrupted RNG stream and are void.

**Design.** Depends on everything above. Invalidate rather than quietly keep: stale artefacts whose names
do not say they are stale get used. Prove the pipeline end to end after the fixes - workers, collection,
checkpoint round-trip, metrics - with a short run, before committing to a long one.

**Honest scoping.** A full retraining run is a long-running operation, not a code change. What this plan
delivers is the invalidation, an end-to-end proof that the trainer works against the repaired stream, and
the command to start the real run. It does not deliver a converged model.

**Outcome.** There was nothing to invalidate: no checkpoints, weights or metrics survive on disk or in
git, so the artefacts that would have been silently reused are already gone. What remains was the proof,
and it passed:

```
started 2 worker processes
generation 0  episodes=4  seeds=1/100  shaping=1.00
ended   death 4 (100%)
ppo     policy=0.0046  value=633.2968  entropy=1.873  clip=0.35  kl=0.0270
saved   ...\weights.bin  (generation 1, adam step 16)
[OK]   wrote 2 best-per-seed replays
```

Workers, episode collection, the PPO update, the checkpoint round-trip and metrics are all working
against the repaired stream. A real run is not started here, because a checkpoint trained while
`mage-long` does not reproduce would encode a fault nobody has located yet.

**A crash fixed on the way.** That run first died on `heap.sprite` being null in `Item.onThrow` — the
third site to dereference a heap's sprite without checking, after two in `Heap` itself. The fix is at
the source rather than the third call site: a heap's sprite is assigned by `GameScene.add`, which never
runs without a scene, so `Level.drop` now assigns one itself, as its blocked-item branch already did. An
`ItemSprite` allocates no GL resources until it is drawn and nothing draws headlessly, so it costs an
object. Guarding each consumer instead would have left the fourth site waiting.

---

## 7. Decisions to settle

| Question | Recommendation | Why |
| --- | --- | --- |
| Corpus checked in, or generated on demand? | Check in a small generated corpus - 8 to 12 recordings across hero classes and shapes (short, death, stall, multi-floor) - and regenerate it when the engine changes | A corpus nobody regenerates goes stale silently and then fails for the wrong reason. Checked in plus an explicit regeneration is the same discipline as the golden traces, and it makes the gate's inputs reviewable. |
| Split T1's budget into a fast gate and a larger periodic job? | No, not yet | `playbackcheck` is 7 ms of playback against ~2.4 s of gradle overhead. There is nothing to split until the corpus grows large enough to matter, and a second job is a thing to forget. Revisit with the corpus. |
| Adopt event-keyed RNG? | Not now, yes eventually | See section 1. Correct in principle, a substrate rewrite in practice. |