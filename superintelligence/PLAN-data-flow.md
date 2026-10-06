# Worker data flow — implementation plan

Companion to [`WORKER-DATA-FLOW.md`](WORKER-DATA-FLOW.md), which sets out the dilemma and the option
analysis. This file is the ordered engineering plan and the reasoning behind the order.

Decision: **worker-side GAE + sampled transitions (Option C), bit-packing deferred, IMPALA deferred.**

Nothing here is built yet.

Revised 2026-10-07. §2 and §4 contradicted each other on sampling order, §4's cost projections ignored
the learner's compute entirely, and two things the plan needs were missing: a measurement of the update
and global gradient clipping. All four are corrected below, with the reasoning.

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

## 2. GAE needs scalars, not observations

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
- Without this split, a worker would need the full episode's observations resident (up to 1.9 GB) and
  bit-packing would become a prerequisite instead of an optimisation.

### 2.1 Correction — sampling happens *during* collection

An earlier draft of this plan said "sampling must happen after GAE, not before", and justified it by
§2 above. That justification argues the opposite: because the backward pass reads only scalars, and
every retained step's scalars are kept in full, the retained set of *observations* has no effect
whatsoever on the advantages computed for it.

The two requirements were contradictory, and the original ordering was the wrong resolution. The
collector draws its Bernoulli sample during the loop, keeping the scalars for every step and the
observation only where the draw succeeded. Advantages are identical either way. Two consequences
follow, both of them the reason for the change:

- **No second simulation pass.** Sampling after GAE would mean either holding every observation until
  the episode ends (the 1.9 GB cliff) or re-simulating the episode to fetch the ones that were
  selected — at ~2.3 ms a turn, re-simulating is a comparable cost to collecting.
- **No all-observations residency**, which is what keeps bit-packing an optimisation rather than a
  prerequisite.

The only reason to prefer sampling afterwards would be *biased* selection, by advantage magnitude
say. That introduces bias into the gradient estimator and needs importance weights to undo. Uniform
sampling during collection is unbiased and needs none.

### 2.2 Correction — one forward pass per step, not two

`PPO.collect` forwards the network again after every step, purely to fill `t.nextValue`:

```java
t.reward = (float) env.step( action, secondary );
network.forward( env.grid(), env.inventory(), env.heroFeatures() );   // rlp/PPO.java:209
t.nextValue = network.value();
```

But the GAE loop above reads `t.nextValue` at exactly one index: `i == to - 1`. Every other step's
bootstrap is `buffer.get( i + 1 ).value`, which is the value the *next* step already computed from
the same state. So an episode of *n* steps needs **n + 1** forward passes, not 2*n*: one per step for
its own `value`, and one more at the end to bootstrap a truncated episode.

The extra pass is not merely redundant. It advances the LSTM over the post-step observation as well,
so under `collect` every observation is absorbed into the recurrent state **twice** — once before the
action, once after it. Deleting it makes the trunk see each observation once, which is what the
network was designed for and what `gradcheck` measures against.

Value, therefore: collection throughput roughly doubles, and a latent artifact disappears. Nothing
that runs today depends on the old behaviour — `PPO.collect` and `PPO.rollout` have no callers.

---

## 3. Revised transport cost, with no packing at all

Measured: 20 workers × 16 episodes = 320 episodes/generation, mean ~150 turns/episode (observed range
88–379).

```
320 episodes × 150 turns × 5% = 2,400 sampled steps
2,400 × 49.4 KB ≈ 118 MB per generation
```

**~120 MB/generation, unpacked.** At pipe speeds that is roughly 120 ms of transfer.

Compare: 2.4 GB for shipping everything. The 20× cut comes from the sample rate, not from packing.

**This is the number that defers bit-packing.** Packing would take 118 MB → ~15 MB. Saving ~100 ms
per generation is not worth the engineering until transport shows up in a profile.

---

## 4. The finding the earlier draft missed: the update is the real cost

The 120 ms above is the *cheap* half of a generation. The expensive half is the trainer learning on
what it just received, and nothing in the first draft of this plan measured it.

An update costs one forward and one backward per sample per epoch. At the default
`epochs = 4` and ~2,400 sampled steps that is **9,600 forward+backward passes per generation, on a
single thread** (`PPO.update`, `rl/PPO.java:297`).

Per sample, the shape is expensive. The convolution is 20 planes of 48x48 im2col'd into a
12,696×320 matvec; the trunk is 12,696×256; the LSTM is 256→128.

**Measured.** `gradle :superintelligence:updatecost`, on this machine, 3,583,827 parameters, one
thread:

| | per sample |
| --- | --- |
| forward only | 4.30 ms |
| forward + backward | 11.02 ms |
| + average, clip, Adam, amortised over a 32-sample minibatch | **11.29 ms** |

Projected against a generation of 48,000 collected steps (20 workers × 16 episodes × 150 turns):

| sample rate | sampled steps | 4 epochs | 1 epoch |
| --- | --- | --- | --- |
| 1% | 480 | 21 s | 5 s |
| 2% | 960 | 43 s | 11 s |
| 5% | 2,400 | **107 s** | 27 s |
| 10% | 4,800 | 214 s | 54 s |
| 100% | 48,000 | 35 min | 9 min |

**So the bandwidth argument for Option C was never the binding constraint at these sample rates.**
Transport is 120 MB per generation, ~0.12 s. The update at the intended 5% and 4 epochs is ~107 s —
roughly **900× the transport**, and ~15× the ~7 s it takes to collect the data in the first place.
The trainer's single thread would be the critical path with 19 of 20 cores idle.

Two things follow that are not optional:

1. **The update must be parallel.** Not "if the measurement says so" — the measurement says so. It
   also means the batch configuration has to be chosen against this table rather than against taste:
   5% at 1 epoch is 27 s, 5% at 4 epochs is 107 s, and the difference is whether a generation is
   minutes or a minute and a half even after parallelising.
2. **The optimiser is not the problem; the forward and backward are.** The Adam pass, the gradient
   norm and the gradient scaling together add 0.27 ms per sample — 2.4% of the cost — because they
   run once per minibatch rather than once per sample. An earlier version of this harness timed
   `network.step` inside the per-sample loop and reported 25.5 ms/sample, nearly 2.3× the truth, which
   is exactly the kind of number that would have sent this project looking for a cheaper optimiser.

Two further consequences of a first real gradient, both cheap:

- **Global gradient clipping did not exist.** `Network.gradClip = 0.5f` was declared with the comment
  "applied by the caller before step()" and no caller applied it; `PPO.update` never computed a norm.
  It is implemented now, and the measured norm is reported per minibatch so a run that starts to
  diverge is visible before it happens rather than after.
- **The reported losses were running sums.** Each minibatch averaged over its own samples and the
  totals were summed across 300 minibatches, so `policy=` grew with update length rather than
  measuring anything. They are now means over the samples seen.

---

## 5. Sampling policy

Uniform over steps, plus a deliberate exception.

- **Always retain the last K = 20 steps of every episode.** γλ = 0.9405, so an advantage is
  effectively supported over ~1/(1−γλ) ≈ 17 steps. The `deathPenalty` of −100 and the `depthReward` of
  +10 live at exactly one step, and under uniform sampling that step survives with probability ≈ 0.05
  — meaning most episodes contribute no terminal signal at all. Retaining the tail costs 20
  transitions per episode and makes the largest advantages in the batch reliably present. This is a
  fixed, position-based subset, not an advantage-ranked one, so it introduces no selection on the
  quantity being estimated.
- **Uniform Bernoulli over the rest**, drawn from a dedicated `Random` seeded per generation from the
  trainer, so a seed reproduces the same sample set.

Both are knobs, not constants: `--sample-rate`, `--max-sampled-per-episode` (2048, ~98 MB, the same
worst case `rolloutCap` already imposed) and `--max-samples-per-generation` (8192, ~397 MB) as a
trainer-side valve. Drops are counted and printed, never silent.

---

## 6. Plan, in order

Each step is independently shippable and leaves the system working.

### Step 0 — Split the trainer, changing nothing

`Trainer.java` is 824 lines against the project's own 500-line rule (`instructions.md:57`), and the
work below adds to it. Extracted first, as pure moves, so the behavioural commits below have a small
diff to review: `train/WorkerPool` (launch, `WorkerHandle`, watchdog), `train/GenerationReport`,
`train/TrainOptions`, `train/Protocol` (message constants, today duplicated as bare literals across
`Worker` and `Trainer`).

Gate: `build`, `gradcheck`, `modecheck`, `restartcheck` and a 60-rollout determinism sweep, all
unchanged.

### Step 1 — Measure the update, and clip gradients — **done**

`diag/UpdateCostCheck` (`gradle :superintelligence:updatecost`) times forward, backward and a whole
minibatch, and projects seconds/generation across a sample-rate × epochs grid. §4 has the numbers.
Global-norm clipping landed here, because the first real gradient needs it whether or not the
measurement is bad.

Result: the update is ~900× the transport it was supposed to be cheaper than. Steps 3 and 5 do not
address it; step 4 does.

Gate, met: the numbers are in this file, and step 3's sample rate and `epochs` are chosen from them.

### Step 2 — GAE in the worker

Move advantage computation to the worker, applying §2.1 and §2.2.

- `Policy` gains a method that walks a worker's per-episode scalar arrays at episode end. The existing
  `Transition`-based method stays: `gaecheck` asserts the two agree.
- New `rl/EpisodeRecord` (scalars for all steps, observations for sampled steps) and
  `rl/EpisodeCollector` (the step loop), so `PPO` stops being both the collector and the learner.
- gamma, lambda, sampleRate and maxSampledPerEpisode must reach the worker. **They are not currently
  in `MSG_PARAMS`** — gamma and lambda are `PPO` fields (0.99, 0.95) and the worker's own `PPO`
  instance happens to use identical defaults. That agreement is coincidence, not configuration. Add
  all four to `writeParams`, set by the trainer from its own `PPO` so they cannot drift.
- `advantage` normalisation stays in the trainer. It is a separate batch-wide pass
  (`PPO.normaliseAdvantages`) and works over whatever subset arrives.
- Ship: `reward` is no longer needed once advantages are precomputed, nor `value` or `nextValue`. The
  transition frame is observation + masks + action/slot indices + `oldLogProbability` + `advantage` +
  `returnValue` + `terminal`.

Gate: `gaecheck` (scalar GAE equals `Transition` GAE on identical fixtures; sampling reproduces from
its seed; tail retention holds), episodes unchanged, `gradcheck` green, determinism sweep unchanged.

### Step 3 — Ship sampled transitions to the trainer

- New message `MSG_TRANSITIONS`, ~48.5 KB per sampled step.
- Replay transfer moves to a separate, rarer message. Today `wantReplay` rides along on the episode
  frame; at 320 episodes/generation that is a second, larger pipe cost hiding inside the same
  channel.
- The 20 dispatch threads decode into their own lists and the merge happens on the trainer thread, so
  no shared learner state is touched concurrently.
- The trainer decodes into fresh `Transition`s and never the pool. See step 5's note on why.
- `Trainer.report` prints sampled-step count per generation. If that drifts far from the predicted
  ~2,400, the sample rate is not doing what we think.

Gate: pooled update runs on real data for the first time. `policy` and `value` losses become non-zero,
the weights change across the update, and post-normalisation advantages have mean ≈ 0. **This is the
first moment anything in the project has learned anything.**

### Step 4 — Parallel minibatch update — **required, not conditional**

Step 1 measured it: 11.29 ms per sample, single-threaded, dominated by the forward and backward
rather than by the optimiser. At 5% and 4 epochs that is 107 s of trainer CPU per generation against
~7 s of collection and 0.12 s of transport.

The fix is to parallelise across minibatches: private scratch and gradient buffers per thread, one
reduction per minibatch, 14.3 MB × threads of accumulation. This requires separating parameter storage
from scratch in `Network`, `Dense`, `Conv2D` and `LSTM`, which is a real refactor.

The open question is where the parallel speedup stops paying: the reduction moves 14.3 MB per thread
per minibatch, so it competes with the per-sample work once a thread's slice gets small. That is a
measurement, not a guess, and it decides `minibatchSize` as much as thread count.

Gate: measured seconds/generation down, `gradcheck` green.

### Step 5 — fp16 weight push, only if the barrier shows up

The barrier is `3,583,827 × 4 B × 20 = 286 MB` per generation, pushed serially per worker, during
which no worker can run. Measured at 12–26% of wall at 20 workers, 45% at 4 workers.

Send weights as fp16 and cast back to fp32 on arrival: **286 MB → 143 MB**, barrier roughly halved.
Around 40 lines, no algorithmic change, no new hyperparameters.

`--episodes` also dilutes the barrier and already exists.

Gate: barrier percentage down, `gradcheck` green, throughput up.

### Step 6 — Bit-packing / RLE, only if transport shows in a profile

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

### Step 7 — IMPALA, only if 1–6 are measured and the barrier is still the wall

An architectural change, not an optimisation. Off-policy correction (V-trace), two learning rates,
tracer decay, ratio clipping. Gives up the on-policy guarantee that steps 2–3 preserve.

Note the sequencing risk: subsampling at 5% cuts generation time, so the *fraction* lost to the
barrier goes **up**, not down. Do not let that be misread as "C failed, IMPALA is required". The same
applies to step 4: parallelising the update makes the remaining barrier a *larger* fraction of a
smaller wall.

### Never — frame-differencing

Consecutive observations overlap heavily (camera follows hero; one tile changes). Delta encoding
would beat 8× by a lot.

It cannot be done. PPO shuffles minibatches across 4 epochs, so any delta encoding needs random
access to any observation. Delta and shuffling are mutually exclusive. Recorded here so it is not
re-proposed.

---

## 7. Expected effect on the machine

| | now | after steps 2–3 | after step 4 |
| --- | --- | --- | --- |
| transport/gen | ~0 (discarded) | ~118 MB | ~118 MB |
| update/gen | 0 (empty buffer) | ~107 s, 1 thread | ~107 s ÷ threads, plus reduction |
| barrier | 12–26% | ~90% of wall | much lower |
| system CPU | ~50% avg, 90–100% peak | trainer-core bound | spread across cores |
| pooled gradient | no | yes | yes |

Throughput should *fall* after step 3 — real PPO work replaces free discard. That is expected and is
not a regression. The size of the fall is measured: about 107 s of trainer CPU per generation at 5%
and 4 epochs, and the batch configuration has to be chosen against that rather than against taste.

---

## 8. Risks

| Risk | Severity | Note |
| --- | --- | --- |
| Advantages differ from full-buffer computation | blocking | Step 2 gate. Cheap to test. |
| Worker/trainer gamma drift | medium | Currently masked by identical defaults. Fixed in step 2. |
| Sample too small (~2,400 for 3.5M params) | medium | Needs a knob and a measurement plan. Low end of workable. |
| Update cost dominates everything | **measured: it does** | 107 s vs 7 s of collection. Step 4 is required. |
| 300 Adam steps on 2,400 samples destabilises training | medium | Only visible once losses are non-zero. Re-tune from step 1's grid. |
| 95% of collected experience unused | accepted | Fine to start with. Not free. |
| Replay transfer crowds the transition channel | low | Split the message in step 3. |
| GAE in worker breaks on chunk boundaries | medium | Gone: an episode is one GAE segment, and chunking moves to step 2's sampling cap. |
| Tail retention biases the gradient | low | A fixed position-based subset, not advantage-ranked. See §5. |

---

## 9. Open questions

- Sample rate: 5% gives ~2,400 steps. Now measured rather than guessed — §4 — and the answer is that
  5% at 4 epochs costs 107 s of trainer CPU per generation. Whether that is acceptable depends on step
  4's thread count, and 1 epoch vs 4 moves it by 4×. `--sample-rate` is a knob for exactly this.
- Should episodes longer than a threshold be capped at the source, given GAE cost grows linearly
  while value signal does not?
- Replay retention: currently every third episode. At 320 episodes/generation that is a
  disproportionate share of the pipe for a diagnostic feature.
- How many threads can the update use before the gradient reduction (14.3 MB per thread per minibatch)
  costs more than the parallelism saves? Unknown until step 4 exists.