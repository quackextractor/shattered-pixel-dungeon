# Viewer fidelity: recordings diverge in the rendered viewer but verify clean headlessly

**Status:** OPEN. Diagnosed to a leading cause, not yet proven to the step, and not fixed.
**Module:** the fault is in the game (`GameScene`), which is outside `:superintelligence`. This file
lives here because everything that observes it lives here.

---

## The problem

The committed corpus of 17 recordings verifies **clean headlessly, all 17**, and diverges in the
**rendered viewer** for **7 to 9 of them**.

The failing set is not stable. Two consecutive `viewcheck` runs on the same machine, same corpus,
same build:

| Run | Count | Failing recordings |
|---|---|---|
| 1 | 7 of 17 | `duelist-mid`, `duelist-short`, `huntress-mid`, `mage-mid`, `rogue-mid`, `warrior-death`, `warrior-mid` |
| 2 | 9 of 17 | `cleric-mid`, `duelist-mid`, `duelist-short`, `huntress-mid`, `mage-alt`, `rogue-mid`, `warrior-alt`, `warrior-death`, `warrior-mid` |

`cleric-mid`, `mage-alt` and `warrior-alt` appear in the second run and not the first; `mage-mid`
passes the second and fails the first. **The membership varies, not merely the step at which a
recording fails.** Both vary. A recording that fails once cannot be assumed to fail again, which is
the central practical difficulty — see *What is missing*.

The failures are not rounding errors. Captured from a real `viewcheck` run of `mage-alt.replay`:

```
[replay] halted: DIVERGED at step 20 - INTERACT/0 in WORLD: hero at 1090, recording says 1126
```

and from `warrior-mid`, the clearest case of the actual failure mode — the hero loses health that no
recorded step accounts for:

```
[replay] halted: DIVERGED at step 17 - MOVE_E/0 in WORLD: hp is 19, recording says 20
```

The recording is not wrong and the trainer is not wrong: the same recording reproduces exactly when
replayed through the headless verifier. Something the viewer does that the trainer does not is
letting the world advance.

**Why this matters more than a broken viewer.** The viewer's whole purpose is inspecting training
runs — "what did the policy actually do on that run" is answered by playing it. If playback is not
faithful, then every question asked of a recording is answered about a run that did not happen, and
the failure is silent: it looks exactly like a run that died to a mob it should have dodged. The RL
loop itself is unaffected (training is headless and gated), so nothing catches this except a gate that
has to be excluded from the normal suite for the reason below.

---

## What is known

The shipped viewer does not drive playback directly. It installs itself as the game's **frame
driver** (`GameScene.setFrameDriver( ReplayController::pump )`), and `GameScene` calls that driver
from inside its own update:

```
GameScene.update  ->  pump  ->  player.update( Game.elapsed )
```

`GameScene.update` advances the world's actors on **its own schedule**, alongside the drain
`ReplayPlayer` performs to reach the hero. So mobs get turns on frames that are **not** applying a
recorded step. The recording was made against a trainer that only ever advances the scheduler
deliberately, one deliberate advance per recorded action. The viewer therefore hands out extra turns.

Because the extra turns ride on the render loop, the number of them depends on how many frames elapse
per recorded step — which depends on frame rate, and on anything else that makes the loop slower or
faster. That is the whole reason the failing subset varies between runs.

**The leading cause is established. The exact step-level mechanism is not.** Nothing currently
observes actor activity on frames that do not apply a step, so the claim "a mob acted here" cannot be
checked against the recording — it can only be inferred from a health total that came out wrong.

### Why the existing gates do not catch it

`playbackcheck` passes all 17. It is not a weaker gate here — it is a different path. It calls
`ReplayPlayer.update(float)` directly in a loop, with no `GameScene`, no scene, no render loop, and no
UI. Everything the fault depends on is absent by construction. This is the same class of fault
`playbackcheck` is structurally unable to see, which is why `viewcheck` was added.

`viewcheck` (`gradle :desktop:viewcheck`) plays the corpus the way a person plays it — real
`GameScene`, real frame driver, real GL context — with the window hidden (`-Dspd.hidden`) but never
faked. It reproduces, and it is **deliberately excluded from `gates`** because it needs a display.
It is expected to stay red until this is fixed.

---

## Current troubleshooting options

What exists today, and what each one can and cannot tell you.

| Tool | Invocation | Shows | Blind to |
|---|---|---|---|
| Viewer step trace | `replay-viewer <file> --trace` | One line per settled step: cursor, action/slot, mode, position, hp, `Actor.now()`, expected position | Anything between steps. Fires per *step*, so it structurally cannot show activity on non-step frames |
| RNG trace | `-Dspd.rngTrace=<path>` (viewer), `gradle :superintelligence:rngtrace` (trainer), `--rng-trace-golden` | Per-step cumulative base draws, an order-sensitive fingerprint, and per-step draw-site deltas attributed to call sites | Gameplay randomness, effectively. `baseDraws` is **zero across an entire 41-step run** — nearly every draw happens inside a push/pop generator pair |
| RNG trace compare | `RngTrace.compare()` | The only automatic trainer-vs-viewer diff in the repo: step-keyed, prints the first differing step with both sides | Anything above — it consumes the RNG trace, so it inherits its blindness |
| Trainer step trace | `StepTrace` (by hand, off the classpath) | Trainer-side per step: before/after/recorded position, `Actor.now()`, resting, backpack usage, bag names, and `<<<` on a position mismatch | Has no gradle task and no `Main` subcommand, so it must be launched manually. Its field set is **disjoint** from the viewer trace, so the two need manual alignment to compare |
| Episode diff | `gradle :superintelligence:episodediff` | Richest diff in the repo: three-way line comparison with mob roster, actor IDs, cooldown reschedules, RNG site deltas | **Never touches the viewer.** It compares two headless runs, so it cannot speak to this fault |
| Pickup/drop/interact trace | `-Dspd.pickupTrace` | `[interact] picked mob/heap/door/transition at <cell>`, `[drop] slot= item= equipped= bagRoom=`, `[pickup] cell= size= backpack= velvetDropped=…` | Not wired to any gradle property or `.bat` flag. Documented in-source as a temporary diagnostic |
| Viewer heartbeat | `replay-viewer <file> --heartbeat` | Liveness: frame count, step cursor, playing flag, window-open, every 2s | Whether the loop is *correct*, only that it is alive |
| Rendered gate | `gradle :desktop:viewcheck` | Which recordings fail, at which step, with which field | A state diff. Reports a step index and a reason string, nothing more |
| Batch sweep | `replay-batch.ps1` | CLEAN / DIVERGED step N / HALTED / TIMED OUT over a directory | Same — classification only, no state diff |

The nearest thing to a two-sided diff already exists in spirit: `StepTrace` is labelled in-source as
"trainer-side per-step trace of a recording, **to diff against the viewer trace**". That pairing was
intended and was never completed — nothing aligns the two outputs and nothing invokes the trainer half.

### What the RNG trace already ruled out

Worth recording so it is not retried: the RNG stream is **not** offset. A thousand-value drift in the
base generator was the original hypothesis, and the RNG trace disproved it (`PLAN-replay-parity.md`
§1.4). The remaining fault is behavioural. Anyone reaching for `--rng-trace-golden` first should know
the oracle it compares has almost no entropy to work with on this codebase.

---

## What is missing

The gap that matters is a single one: **nothing observes frames that do not apply a recorded step.**
Every existing viewer-side trace is keyed to a settled step, so the suspected cause is invisible by
construction. The tools can say *that* step 18 ended wrong; none of them can say *what happened
between* step 17 and step 18.

Specific, named:

- **No per-frame observation.** Turns taken on non-step frames, which actor took them, and the
  `Actor.now()` delta attributable to them, are not recorded on either side.
- **No frame/step relationship is recorded.** The step count is in the recording; the frame count is
  not. Nothing records how many frames a step consumed, so frame-rate dependence is inferred from
  the varying failure set rather than measured.
- **No automatic two-sided diff.** Viewer trace and trainer trace are never aligned or compared by
  any tool. `RngTrace.compare` is the only automatic comparison, and it compares randomness.
- **Step acceptance is not observable.** `ActionMapper.apply` / `applySecondary` return a boolean
  saying whether the action was taken, and **both callers discard it** — `ReplayPlayer` ignores it,
  and the trainer only converts a refusal into an `INVALID_ACTION` note. A move onto a non-adjacent
  cell, a slot that resolves to nothing, or an unreachable target is refused silently, in either
  path.
- **No world observation on the viewer side.** Mob roster, positions, HP, AI state and cooldowns are
  not captured during a real viewer playback. `episodediff` has this, but only for headless runs.
- **Inventory observation is partial.** `ReplayRecorder.inventory()` iterates the hero's backpack
  only. An item held as a bag — a `VelvetPouch`, say — is invisible in the recorded inventory and in
  the comparison built on it.
- **Menu/dialog handling differs structurally between the two paths.** The trainer's
  `WindowBridge` reads `GameScene.headlessWindow()`, which is only populated when there is no scene.
  In the viewer there is always a scene, so that slot is never set. Meanwhile
  `ReplayController.dismissUnanswerableWindow()` force-closes any non-`WndOptions` window every
  frame. Recorded `MENU` steps are therefore answered through a different mechanism in the viewer
  than in the trainer. Unconfirmed as a cause, and unexamined.
- **No deterministic reproduction.** The failing membership changes between runs on the same machine
  and the same corpus, as measured above. That makes it impossible to write a regression test that
  fails before a fix and passes after, and it makes "did my change help?" unanswerable: a change that
  fixed four recordings might simply have moved the failures onto three others.

---

## Suggestions

What would help, in rough order of how much each unblocks. Deliberately not a design or an
implementation plan.

1. **Make it reproducible first.** Everything else is blocked on this. Until one specific recording
   fails the same way twice, there is no regression test and no way to tell a fix from luck. The five
   recordings that fail in both measured runs are the only candidates, and nothing is known about
   whether they fail the same *way*.
2. **Settle whether the viewer should own the scheduler at all.** This is a design question about the
   game, not about the module, and it has two honest answers: either the viewer is prevented from
   advancing the world outside a recorded step, or the trainer is changed so that the frame-driven
   behaviour is legitimate and recordings stop assuming one-turn-per-action. The two imply very
   different work and the same investment into observability.
3. **Observe frames, not just steps.** Until turns taken on non-step frames are visible on both
   sides, the leading cause stays a hypothesis and every investigation starts by rebuilding the same
   evidence.
4. **Finish the two-sided trace that was already intended.** `StepTrace` and the viewer `--trace`
   were written to be diffed against each other. Aligning their fields and comparing them
   automatically is the cheapest path to a readable side-by-side, and it would have caught the field
   drift that has accumulated between them.
5. **Stop discarding step acceptance.** A refused action is currently invisible in both paths. This
   is a smaller question than the scheduler fault and would pay for itself across every other
   investigation.
6. **Decide whether `viewcheck` can ever be a gate.** It is excluded from `gates` because it needs a
   display. Until it is either reliably runnable in CI or accepted as a separate manual gate, this
   class of regression returns silently.

---

## Open questions

- Is the frame-driven scheduler advance a viewer bug, or is the trainer's one-turn-per-action
  assumption the thing that is wrong? The answer determines whether this is a small fix or a change
  to how runs are recorded.
- Should the viewer's purpose be *faithful* playback, or *good enough to watch*? The two conflict
  here: faithful playback is frame-rate independent by definition, watching is not. Nothing in the
  project states which is required.
- Does the `WindowBridge` / `headlessWindow` asymmetry affect any committed recording? Nothing has
  checked, because MENU steps are a small minority and the traces do not expose it.
- ~~Is the failure set varying, or the failure *step* varying within a stable failing set?~~ **Answered
  by the two runs above: both vary.** The failing membership changes between runs on the same machine,
  so there is no stable subset to investigate even before individual failure steps are considered.
- Why do `duelist-mid`, `huntress-mid`, `rogue-mid`, `warrior-death` and `warrior-mid` fail in *both*
  runs? Those five are the closest thing to a reliable reproduction available, and it is worth knowing
  whether they share a mechanism or are coincidentally persistent.

---

## Definition of done

- `gradle :desktop:viewcheck` is green on the full corpus, and stays green across repeated runs on
  one machine — which is the part that is not true today.
- A single named recording fails the same way, twice, before and after any change. That is the
  prerequisite for claiming the fix did anything.
- Whatever observer is added reports on the render-loop behaviour it was added for, so the next
  frame-timing fault does not require starting the evidence-gathering from scratch.