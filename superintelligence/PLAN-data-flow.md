# Worker data flow — implementation plan

Companion to [`WORKER-DATA-FLOW.md`](WORKER-DATA-FLOW.md), which sets out the dilemma and the
option analysis. This file is the ordered engineering plan and the reasoning behind the order.

Decision: **worker-side GAE + sampled transitions (Option C), bit-packing deferred, IMPALA deferred.**

Nothing here is built yet.

---

## 1. The finding that drives the whole plan

PPO's advantage estimator is a **backward recursion over consecutive steps**:

```java
for (int i = to - 1; i >= from; i--){
    Transition t = buffer.get( i );
    float nextValue = (i == to - 1) ? t.nextValue : buffer.get( i + 1 ).value;
    float nonTerminal = t.terminal ? 0f : 1f;
    float delta = t.reward + gamma * nextValue * nonTerminal - t.value;
    lastGae = delta + gamma * lambda * nonTerminal * lastGae;
    t.advantage = lastGae;
    t.returnValue = t.advantage + t.value;
}
```

`Policy.computeReturnsAndAdvantages`, `rl/Policy.java`.

`buffer.get( i + 1 )` is the crux: step *i*'s advantage is not defined without step *i+1* sitting
next to it. **Drop steps and the chain breaks.** A naive 5% subsample would compute advantages from
non-adjacent steps — numbers that look like advantages, are finite, and are wrong.

So subsampling cannot be a filter applied to an existing buffer. GAE has to move to the worker,
where the whole episode still exists.

---

## 2. Key consequence: GAE needs scalars, not observations

This is what makes the plan cheap, and it was not obvious up front.

GAE reads only `reward`, `value`, `nextValue`, `terminal`, `advantage`, `returnValue` — roughly
**21 bytes per step**. It never touches the observation.

The observation is the expensive part (49.4 KB, 96% of a transition) and GAE does not need it.

So a worker can hold a whole episode as:

| Kept | Bytes/step | Purpose |
| --- | --- | --- |
| GAE scalars, all steps | ~21 B | backward pass at episode end |
| Full observation, **sampled steps only** | 49.4 KB | ship to trainer |

That means:

- **A full episode costs almost nothing.** 40,000 steps of scalars = ~840 KB.
- **Observation memory is bounded by the sample rate, not episode length.** 5% of 40,000 steps =
  2,000 retained = ~98 MB. Worst case is fine, and it is the *same* worst case as `rolloutCap`
  already imposes.
- **Sampling must happen after GAE**, not before. Compute the whole episode's advantages, then pick
  which steps to retain observations for.

Without this split, a worker would need the full episode's observations resident (up to 1.9 GB) and
bit-packing would become a prerequisite instead of an optimisation.

---

## 3. Revised cost, with no packing at all

Measured: 20 workers × 16 episodes = 320 episodes/generation, mean ~150 turns/episode (observed range
88–379).

```
320 episodes × 150 turns × 5% = 2,400 sampled steps
2,400 × 49.4 KB ≈ 118 MB per generation
```

**~120 MB/generation, unpacked.** At pipe speeds that is roughly 120 ms of transfer.

Compare: 7.9 GB for shipping everything. The 67× cut comes from the sample rate, not from packing.

**This is the number that defers bit-packing.** Packing would take 118 MB → ~15 MB. Saving ~100 ms
per generation is not worth the engineering until transport shows up in a profile.

---

## 4. Plan, in order

Each step is independently shippable and leaves the system working.

### Step 1 — GAE in the worker

Move advantage computation to the worker. Largest unblock, smallest code.

- `Policy` gains a method that walks a worker's per-episode scalar list at episode end.
- `Worker` holds two structures: scalars for all steps, observations for sampled steps.
- gamma and lambda must reach the worker. **They are not currently in `MSG_PARAMS`** — they are
  `PPO` fields (`0.99`, `0.95`) and the worker's own `PPO` instance happens to use identical
  defaults. That agreement is coincidence, not configuration. Add both to `writeParams`, and have
  the trainer set them from its own `PPO` so they cannot drift.
- Sampling happens after GAE. Selection is uniform over steps, seeded from the worker's RNG so a
  seed still reproduces the same sample.
- `advantage` normalisation stays in the trainer. It is a separate batch-wide pass
  (`PPO.normaliseAdvantages`) and works over whatever subset arrives.
- Ship: `reward` is no longer needed once advantages are precomputed. The transition frame becomes
  observation + masks + action/slot indices + `oldLogProbability` + `advantage` + `returnValue` +
  `terminal`.

Gate: episodes unchanged, advantages identical to a full-buffer computation, `gradcheck` green,
determinism sweep unchanged.

### Step 2 — Ship sampled transitions to the trainer

- New message `MSG_TRANSITIONS`, carrying the frame above for each sampled step.
- Trainer appends to its own `PPO` buffer instead of discarding.
- Replay transfer moves to a separate, rarer message. Today `wantReplay` rides along on the episode
  frame; at 320 episodes/generation that is a second, larger pipe cost hiding inside the same
  channel.
- `Trainer.report` should print sampled-step count per generation. If that number drifts far from
  ~2,400, the sample rate is not doing what we think.

Gate: pooled update runs on real data for the first time. `policy` and `value` losses become
non-zero. This is the first moment anything in the project has learned anything.

### Step 3 — fp16 weight push, only if the barrier shows up

The barrier is `3,583,827 × 4 B × 20 = 286 MB` per generation, pushed serially per worker, during
which no worker can run. Measured at 12–26% of wall at 20 workers, 45% at 4 workers.

Send weights as fp16 and cast back to fp32 on arrival: **286 MB → 143 MB**, barrier roughly halved.
Around 40 lines, no algorithmic change, no new hyperparameters.

`--episodes` also dilutes the barrier and already exists.

Do this before considering IMPALA. It targets the same bottleneck at a fraction of the risk.

Gate: barrier percentage down, `gradcheck` green, throughput up.

### Step 4 — Bit-packing / RLE, only if transport shows in a profile

Deferred deliberately. §3 is the justification.

If it is ever needed: the grid is written as `(byte)(v > 0.5f ? 1 : 0)`, so every cell is one bit of
information in a byte — **46,080 B → 5,760 B, lossless, 8×**. RLE may beat that on real maps, which
have large uniform regions (walls, floors, unexplored dark), so try both and keep whichever is
smaller behind a one-byte header.

Two constraints that must survive implementation:

- **Packed form must be what stays resident**, not just what crosses the wire. Packing at send time
  saves bandwidth and leaves the memory cliff untouched.
- **Trainer drops `Transition.POOL`; worker keeps it.** Pooled objects are reused across generations,
  so a packing bug can silently read another episode's bits. The pool is right for a bounded hot
  buffer and wrong for a large cold one.

Also: zero float rounding anywhere in the path, or the cross-process determinism guarantee goes.

### Step 5 — IMPALA, only if 1–4 are measured and the barrier is still the wall

An architectural change, not an optimisation. Off-policy correction (V-trace), two learning rates,
tracer decay, ratio clipping. Gives up the on-policy guarantee that steps 1–2 preserve.

Note the sequencing risk: subsampling at 5% cuts generation time ~20×, so the *fraction* lost to the
barrier goes **up**, not down. Do not let that be misread as "C failed, IMPALA is required".

### Never — frame-differencing

Consecutive observations overlap heavily (camera follows hero; one tile changes). Delta encoding
would beat 8× by a lot.

It cannot be done. PPO shuffles minibatches across 4 epochs, so any delta encoding needs random
access to any observation. Delta and shuffling are mutually exclusive. Recorded here so it is not
re-proposed.

---

## 5. Expected effect on the machine

| | now | after steps 1–2 | after step 3 |
| --- | --- | --- | --- |
| transport/gen | ~0 (discarded) | ~118 MB | ~118 MB |
| barrier | 12–26% | higher (see step 5) | roughly halved |
| system CPU | ~50% avg, 90–100% peak | similar | similar |
| pooled gradient | no | yes | yes |

Throughput should *fall* after step 2 — real PPO work replaces free discard. That is expected and is
not a regression.

---

## 6. Risks

| Risk | Severity | Note |
| --- | --- | --- |
| Advantages differ from full-buffer computation | blocking | Step 1 gate. Cheap to test. |
| Worker/trainer gamma drift | medium | Currently masked by identical defaults. Fix in step 1. |
| Sample too small (~2,400 for 3.5M params) | medium | Needs a knob, and a measurement plan. Low end of workable. |
| 95% of collected experience unused | accepted | Fine to start with. Not free. |
| Replay transfer crowds the transition channel | low | Split the message in step 2. |
| GAE in worker breaks on chunk boundaries | medium | Scalars are complete per episode; `rolloutCap` chunks are independent of GAE. |

---

## 7. Open questions

- Sample rate: 5% gives ~2,400 steps. Is that the right batch for a 3.5M-parameter network? Needs a
  knob and a scan, not a guess.
- Should episodes longer than a threshold be capped at the source, given GAE cost grows linearly
  while value signal does not?
- Replay retention: currently every third episode. At 320 episodes/generation that is a
  disproportionate share of the pipe for a diagnostic feature.
