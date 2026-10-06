# Worker data flow — decision

Status: **decided — Option C.** Nothing built yet; the engineering plan is
[`PLAN-data-flow.md`](PLAN-data-flow.md). Everything below is measured on this repo, not estimated
from theory, except where marked.

Written to make one choice: **where does a worker's training data go, and who computes the gradient.**
It is made in §10.

---

## 1. What project is

Repo: Shattered Pixel Dungeon — roguelike, tile grid, turn-based, 26 floors, permadeath.

Added module: `superintelligence` — headless RL trainer around real game code.

- Game runs with renderer bypassed. No drawing. Same game logic as desktop build.
- Policy: CNN + LSTM actor-critic. 3,583,827 parameters.
- Learner: PPO. Written, gradients verified against finite differences.
- Not built: any training. Workers still act with `ScriptedPolicy`, a hand-written if/else.
- Goal from `research.md`: train one agent on one seed until it beats first boss (Goo, depth 5).

### Why processes, not threads

Game state is almost entirely `static` — `Dungeon`, `Actor`, `Random`, `Level` all hold globals.
Two environments cannot coexist in one JVM. So parallel speedup must come from separate processes.

One process = one environment = one simulation thread.

Consequence: every parallel decision here is really a decision about *processes talking to each other.*

---

## 2. What one step costs

`Transition` — one step of experience:

| Field | Size |
| --- | --- |
| Spatial grid, packed | 46,080 B (20 planes × 48 × 48) |
| Inventory (32 slots × 14 floats) | 1,792 B |
| Hero scalars (23 floats) | 92 B |
| 3 masks (action 17, slot 32, target 9) | 232 B |
| Scalars: reward, value, next value, advantage, return, log-prob, head, indices | ~2.3 KB total incl. object overhead |
| **Measured total** | **50,564 B ≈ 49.4 KB** |

**Grid is 91% of it.** Inventory, hero and masks together are under 4KB.

### Grid is binary, stored as bytes

`Transition.packGrid` writes `(byte)(v > 0.5f ? 1 : 0)`. Every cell is 0 or 1.

So one cell costs a whole byte. Bit-packing would give 46,080 → 5,760 bytes, an **8× cut** with
zero information loss. Not implemented. This is the single cheapest win available and it changes the
arithmetic below by 8×.

---

## 3. What the machine is

Measured:

| | |
| --- | --- |
| CPU | Intel i5-14600KF |
| Cores | 14 physical / 20 logical |
| RAM | 31.8 GB total |
| RAM free (during work) | ~16 GB |
| Pagefile | **2.0 GB** — small, so overshoot thrashes rather than pages |

Pagefile size matters. A design that overshoots RAM does not degrade gracefully here; it falls off a
cliff.

---

## 4. What the pool does now

20 workers, 16 episodes each = 320 episodes per generation.

Measured:

| | |
| --- | --- |
| Throughput | 3,000–8,500 turns/s, 40–70 episodes/s |
| Worker RSS total | ~9.2 GB (~440 MB each, mostly JVM overhead, not the buffer) |
| System CPU | peaks 90–100%, ~50% averaged over whole generation |
| Barrier cost | 45% of wall at 4 workers; 12–26% at 20 workers |

### The barrier

PPO is on-policy. Data must come from the weights being updated. Cycle is strictly serial across
workers:

1. every worker collects with policy π
2. all data returns
3. one update
4. push π′ — 14.3 MB per worker (fp32), so ~286 MB at 20 workers

During step 4 every core is idle by construction. Nothing overlaps. Raising `--episodes` amortizes it
(trade: coarser policy updates). This is why `--episodes` is separate from `--workers`.

### Where the idle comes from

- barrier (12–26%)
- stragglers: episode lengths vary a lot within a generation, so workers finish at different times
- remaining ceiling needs asynchronous learner

---

## 5. What the trainer does not have

Workers currently return: score, depth, turns, end reason, optional replay, CPU seconds, heap MB.

Workers do **not** return transitions. Trainer needs per-step
(observation, action, reward, old log-prob, value) to compute advantages and gradients.

That data is created inside each worker and discarded when the worker exits.

`Trainer.report` shows `policy=0.0000 value=0.0000` every generation, because the trainer's PPO
buffer is always empty. That is the visible symptom of this gap.

---

## 6. The arithmetic that decides it

If every step's transition ships to the trainer:

```
320 episodes × ~150 turns × 49.4 KB  ≈  2.4 GB per generation
```

**Corrected.** This section originally used ~500 turns per episode and got 7.9 GB. The measured mean
is ~150 (range 88–379), so the figure is 2.4 GB. The conclusion is unchanged and in one respect
strengthened, because 2.4 GB has to sit *fully resident* in the trainer while 9.2 GB of workers are
already resident — at or past free memory, on a 2 GB pagefile.

Note the transition buffer must be **fully resident**, not streamed. PPO needs the whole batch at
once to:

- compute advantages in one backward pass
- normalize them batch-wide (zero-mean, unit-variance)
- run 4 epochs of minibatches, re-running each observation each time

So 2.4 GB sits in trainer heap simultaneously, plus a copy in flight on the pipe.

Rough throughput of that pipe: ~1 GB/s optimistic, so ~2.4 s of pure transfer per generation with
nothing computing.

Bit-packing the grid alone: 2.4 GB → ~430 MB. Still large, but no longer a cliff.

---

## 7. Options

### A — Ship all transitions, trainer does the pooled update

- One learner, one gradient over all workers' data. Most stable scaling: bigger batch, less noise.
- Cost: 2.4 GB/generation transferred and held. ~430 MB with bit-packed grid.
- Needs: trainer heap ~1 GB, so fewer workers or smaller batches per generation.
- This is textbook PPO and the only option that keeps the guarantee intact.

### B — Workers update locally, send weights back

- Transport is already proven: 14.3 MB per worker per generation, and it already works.
- No observation transfer at all. Worker buffer is already capped at ~98 MB (`rolloutCap` 2048).
- 20 × 98 MB ≈ 2 GB total. Comfortable.
- Cost: no pooled gradient. N policies drift. Weight averaging across independently-updated nets is
  wrong — they are not in a shared basin — and averaging gradients is wrong once workers start from
  different weights. Need a rule (e.g. periodic full re-sync, so it becomes A every K generations).
- Honestly this is "A, batched every K generations", and its stability is roughly A's divided by K.

### C — Ship a subsample

- Trainer gets a fraction of steps; pooled gradient over that fraction.
- Cost scales with sample rate, not episode length. 5% of 2.4 GB ≈ **120 MB**. Comfortable.
- On-policy guarantee holds for the sampled steps; unsampled steps in the same episodes are discarded
  rather than trained on. Mildly less data per update, no bias.
- **Chosen.** Cheapest path to a correct pooled update.

### D — Asynchronous learner (IMPALA)

- Each worker runs continuously. Pushes trajectories upstream, pulls fresh policy from a server,
  never waits. Removes the barrier entirely.
- Needs off-policy correction (V-trace) because policies are stale by update time.
- More tuning: two learning rates, tracer decay, ratio clipping.
- `research.md:26` already lists this as the alternative to PPO.
- Removes the barrier, not the bandwidth.

### E — Threads

- Does not work. Static game state, one env per process.

---

## 8. Comparison

| | Transport/gen | Trainer RAM | Pooled gradient | On-policy |
| --- | --- | --- | --- | --- |
| A all transitions | 2.4 GB (430 MB bit-packed) | needs ~1 GB | yes | yes |
| B local update | 286 MB (already works) | ~0 | no | yes, per worker |
| C 5% subsample | ~120 MB | ~400 MB ceiling | yes | yes, on sample |
| D IMPALA | 286 MB | ~0 | yes, delayed | approximate |

---

## 9. What is actually blocking

Not a technical unknown. Every row above is implementable today.

The blocker was that three things pull in different directions and nobody had picked a winner:

1. **Bandwidth wants C or D.** 2.4 GB/generation is not viable unoptimized.
2. **Stability wants A.** Pooled gradient is the reason PPO scales with worker count.
3. **Effort wants B.** It already works end to end; it needs no new transport.

The choice is made in §10. Two cheap facts that informed it and are not yet acted on:

- Grid bit-packing is **8×** and lossless. Nobody has done it. It moves A from "impossible" to
  "possible without changing the machine". Deferred: the sample rate already cuts transport 67×, so
  packing would save ~100 ms per generation that nothing has yet shown to matter.
- `--episodes` already amortizes the barrier. Sizing it well may make the barrier a non-issue
  without changing learners.

A third factor was found while planning and was not in this document: the trainer's single-threaded
PPO update costs 9,600 forward+backward passes per generation at the intended batch size, and nothing
in either document measured it. It may well be the binding constraint rather than transport. See
`PLAN-data-flow.md` §4.

---

## 10. Recommendation

Short version:

1. ~~Bit-pack the grid. Lossless, 8×, helps every option. Do this regardless.~~ **Deferred.** The
   sample rate removes the bandwidth problem first, and packing's ~100 ms per generation is not worth
   the engineering until transport appears in a profile.
2. **C (subsample to the trainer)** — pooled gradient, on-policy, no new architecture. **Chosen.**
3. If the barrier is still the bottleneck afterwards, **D (IMPALA)** rather than B, because B gives
   up the pooled gradient entirely to solve a problem C solves better.

On the original ordering, the first item is demoted and a step is inserted ahead of C: **measure the
update cost first.** Option C's whole argument is bandwidth, and the update costs 9,600 forward and
backward passes per generation on one thread — a number no document in this repo had estimated. If
that dominates, the answer is a parallel minibatch update, not a different option, and it is cheaper
than anything D costs.

Risk in that order of confidence: 1 is a deliberate deferral with a stated trigger; 2 is
unknown-but-small work, with one genuinely open measurement; 3 is a real architecture change and
should not be started until both are measured.

---

## 11. Unrelated but adjacent

**Hero class appears not to affect score.** All five hero classes produced identical score and turn
count on the same seed. `GamesInProgress.selectedClass` is applied in `Dungeon.init`, so class does
reach the hero. Score is probably driven by turns and depth only. Not confirmed either way.

**Headless coverage is incomplete.** Running the real policy surfaced three crashes the scripted
policy never hit: blobs had no emitter (15 blob types NPE in `evolve()`), `GameScene.cancel()`
dereferenced null `cellSelector`, `GameScene.spellSprite()` read `scene.spells` unguarded. All
fixed. That was a class of bug, not three isolated ones — more will surface.

**Gradient is truncated at length 1.** No backpropagation through time. Exact for trunk, heads and
critic; LSTM gets exact gradients within a step, none across steps. Deliberate; exact would need every
timestep's pre-activation retained, which at this sequence length is gigabytes.
