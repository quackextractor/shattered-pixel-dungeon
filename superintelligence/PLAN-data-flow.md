# Worker data flow — implementation plan

Companion to [`WORKER-DATA-FLOW.md`](WORKER-DATA-FLOW.md), which sets out the dilemma and the option
analysis. This file is the ordered engineering plan and the reasoning behind the order.

Decision: **worker-side GAE + sampled transitions (Option C), bit-packing deferred, IMPALA deferred.**

Status 2026-10-07: **every step is now resolved.** Steps 0–4 and 4b are built and committed; steps 5, 6
and 7 are recorded as not warranted, each with the measurement that says so. See §6.

Revised 2026-10-07. Corrections applied to this document, each verified against the code rather than
against the prose: §2 and §4 contradicted each other on sampling order; §4's cost projections ignored
the learner's compute entirely; the update was unmeasured; global gradient clipping did not exist;
`epochs` default of 4 was never a considered choice and is now 2; a per-turn simulation cost was 17×
wrong; a claimed throughput doubling was never measured; and the plan described code (`PPO.collect`,
`rolloutCap`) that has since been deleted.

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
  2,000 retained = ~98 MB.
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
  selected. Re-simulation is cheap — pure scripted simulation measures **7,367 turns/s, 0.136 ms a
  turn** — so it would cost time rather than being prohibitive. It is still the wrong choice: it
  doubles the simulation cost of every episode for no gain, and the all-observations variant is not an
  option at all.
- **No all-observations residency**, which is what keeps bit-packing an optimisation rather than a
  prerequisite.

The only reason to prefer sampling afterwards would be *biased* selection, by advantage magnitude
say. That introduces bias into the gradient estimator and needs importance weights to undo. Uniform
sampling during collection is unbiased and needs none.

### 2.2 Correction — one forward pass per step, not two

The old `PPO.collect` forwarded the network again after every step, purely to fill `t.nextValue`:

```java
t.reward = (float) env.step( action, secondary );
network.forward( env.grid(), env.inventory(), env.heroFeatures() );
t.nextValue = network.value();
```

But the GAE loop above reads `t.nextValue` at exactly one index: `i == to - 1`. Every other step's
bootstrap is `buffer.get( i + 1 ).value`, which is the value the *next* step already computed from
the same state. So an episode of *n* steps needs **n + 1** forward passes, not 2*n*: one per step for
its own `value`, and one more at the end to bootstrap a truncated episode.

The extra pass is not merely redundant. It advanced the LSTM over the post-step observation as well,
so every observation was absorbed into the recurrent state **twice** — once before the action, once
after it. Deleting it makes the trunk see each observation once, which is what the network was
designed for and what `gradcheck` measures against.

**Expected to roughly halve the cost of collection. Not measured** — it was a code reading when the
change was made, and the collection timings in the generation report have moved for other reasons
since. Treat it as a hypothesis. It is the kind of claim that should not be presented as a
measurement in a document whose other numbers are measurements.

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

An update costs one forward and one backward per sample per epoch. At the then-current
`epochs = 4` and ~2,400 sampled steps that is **9,600 forward+backward passes per generation, on a
single thread** (`PPO.update`, `rl/PPO.java`). The default is now 2 — see step 2b — which halves
every figure below.

Per sample, the shape is expensive. The convolution is 20 planes of 48x48 im2col'd into a
12,696×320 matvec; the trunk is 12,696×256; the LSTM is 256→128.

**Measured.** `gradle :superintelligence:updatecost`, on this machine, 3,583,827 parameters, one
thread:

| | per sample |
| --- | --- |
| forward only | 4.30 ms |
| forward + backward | 11.02 ms |
| + average, clip, Adam, amortised over a 32-sample minibatch | **11.29 ms** |

Projected against a generation of 48,000 collected steps (20 workers × 16 episodes × 150 turns).
Those 48,000 steps assume ~150-turn episodes, which an untrained policy does not produce — §5.1 — so
treat the "sampled steps" column as the intended regime rather than the current one:

| sample rate | sampled steps | 4 epochs | 2 epochs (now default) | 1 epoch |
| --- | --- | --- | --- | --- |
| 1% | 480 | 21 s | 11 s | 5 s |
| 2% | 960 | 43 s | 21 s | 11 s |
| 5% | 2,400 | 107 s | **53 s** | 27 s |
| 10% | 4,800 | 214 s | 107 s | 54 s |
| 100% | 48,000 | 35 min | 18 min | 9 min |

**So the bandwidth argument for Option C was never the binding constraint at these sample rates.**
Transport is 120 MB per generation, ~0.12 s. The update at the intended 5% was ~107 s at 4 epochs —
roughly **900× the transport**, and ~15× the ~7 s it takes to collect the data in the first place.
The trainer's single thread was the critical path with 19 of 20 cores idle.

Two things follow that are not optional:

1. **The update must be parallel.** Not "if the measurement says so" — the measurement says so. It
   also means the batch configuration has to be chosen against this table rather than against taste:
   at the current default, 5% at 2 epochs is ~53 s and 5% at 1 epoch is ~27 s, and the difference is
   whether a generation is under a minute or about two even after parallelising.
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
  trainer, so a seed reproduces the same sample set. Separate from the policy's own RNG so changing
  the sample rate does not change which actions a run takes, which would make two rates incomparable.

Both are knobs, not constants: `--sample-rate`, `--max-sampled-per-episode` (2048, ~98 MB) and
`--max-samples-per-generation` (8192, ~397 MB) as a trainer-side valve. Drops are counted and printed,
never silent.

### 5.1 The tail dominates short episodes, and early episodes are short

An untrained policy's episodes are **7 to 90 turns**, not the ~150 the transport arithmetic assumes, so
a 20-step tail is most of the episode and a requested 5% arrives as near 100%. Measured: 243 of 243
steps sampled on a 243-step generation.

This is a property of the two mechanisms meeting, not a sampler bug: on a 40,000-step episode the
same tail is 0.05%. It means the plan's 2,400-samples-per-generation figure only holds once episodes
approach the length it assumes, which is expected to happen as the policy learns to survive — and
until then the sampled count in the report is the honest figure, not the plan's.

The trainer clamps the rate it sends to [0, 1] and says why, so the two mechanisms cannot silently
disagree. The alternative — sending the raw request and letting the collector exceed it — produced a
sampled count that drifted for a reason no report explained.

---

## 6. Plan, in order

Each step is independently shippable and leaves the system working.

### Priority order (reordered 2026-10-07)

The numbering was written when every step was outstanding, and it is no longer the priority order.
Two things moved on 2026-10-07:

| | was | now |
| --- | --- | --- |
| instrument the loop | not in the plan | **done** (step 3b) |
| persist the weights | after the parallel update | **done, before it** (step 4b) |
| parallel update | next | **done, 3.47x at 8 threads** (step 4) |

**Why instrumentation moves first.** Steps 0-3 built a loop that learns, and the plan had no way to
tell whether it does. `clip=` was reporting a constant 0.8415 — `P(|N(0,1)| > 0.2)` — instead of
PPO's ratio-clip fraction, so the one signal that says the policy is moving too far per update could
not be read. Nothing accumulates across generations, so a run's trend existed only in console
scrollback. The parallel update's only success criterion was "faster", which *is* measurable without
any of this — but doing it first would mean optimising a loop whose behaviour cannot be observed,
and would leave step 3b with nothing to compare the result against.

**Why checkpointing moves ahead of it.** It is smaller than the parallel update, it is on the
critical path to `research.md:40`, and it is what makes two runs comparable at all. It was
originally scheduled after the parallel update, which was wrong on both counts.

### Step 0 — Split the trainer, changing nothing — **done**

`Trainer.java` was 824 lines against the project's own 500-line rule (`instructions.md:57`), and the
work below adds to it. Extracted first, as pure moves, so the behavioural commits below have a small
diff to review: `train/WorkerPool` (launch, `WorkerHandle`, watchdog), `train/GenerationReport`,
`train/TrainOptions`, `train/Protocol` (message constants, then duplicated as bare literals across
`Worker` and `Trainer`). `Trainer` is now 465 and is the loop itself.

Not a pure move after all — it also fixed two real defects, disclosed in its commit: a policy-push
acknowledgement that was read and discarded, and an episode reply check that read a second int off the
wire while building its own error message.

Gate, met: `build`, `gradcheck`, `modecheck`, `restartcheck`, a 60-rollout determinism sweep, and a
2-worker training run end to end.

### Step 1 — Measure the update, and clip gradients — **done**

`diag/UpdateCostCheck` (`gradle :superintelligence:updatecost`) times forward, backward and a whole
minibatch, and projects seconds/generation across a sample-rate × epochs grid. §4 has the numbers.
Global-norm clipping landed here, because the first real gradient needs it whether or not the
measurement is bad.

Result: the update is ~900× the transport it was supposed to be cheaper than. Steps 3 and 5 do not
address it; step 4 does.

Gate, met: the numbers are in this file, and step 3's sample rate and `epochs` are chosen from them.

### Step 2 — GAE in the worker — **done**

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
its seed; tail retention holds; the wire round trip is exact), `gradcheck`, `modecheck`,
`restartcheck`, and a determinism sweep — all met.

### What step 2 actually cost, beyond the code

Switching the worker from `ScriptedPolicy` to the real policy exposed **two latent headless crashes**,
both in code the scripted policy never reached, and both of a class rather than isolated instances —
TODO.md §1 already predicted this ("a random policy explores action space the scripted one never did,
and there are almost certainly more").

| Crash | Cause | Fix |
| --- | --- | --- |
| `UnsatisfiedLinkError: Gdx2DPixmap.newPixmap` | The libGDX natives were never loaded in any headless JVM. Desktop gets them implicitly from `Lwjgl3NativesLoader`; `HeadlessGame` replaces that backend, and a natives jar on the classpath does not load itself. Reached from `TextureCache.createSolid` / `createGradient` / `create`, which unlike `getBitmap` are not guarded on `Gdx.gl == null` — from any `Flare` or `ColorBlock`, ~40 item sites plus every inventory slot. | `GdxNativesLoader.load()` in `HeadlessServices.install()` |
| `NullPointerException: this.font is null` in `BitmapText.baseLine` | `HeadlessPlatform.getGeneratorForString` returns null, so `PlatformSupport.getFont` returns null at its early-out, and every measuring method in `BitmapText` dereferenced it. Reached from `Bag.execute` → `WndQuickBag` → `InventorySlot`. | Null-font guards in `BitmapText.measure`, `baseLine` and `updateVertices` |

`BitmapText()` already constructed with a null font, so the class was in a state it never guarded. This
is **not** the `PlatformSupport.getFont` "No cap character found" drift recorded in
`PLAN-replay-viewer.md` §12 — that is the rendered viewer's path and remains open. This is a separate,
headless-only null return that was silently fatal.

### Step 2b — Reach a usable training speed — **done**

Steps 2 and 3 made learning *possible*. This made it fast enough to actually do, and it is the
difference between a prototype run and an overnight one.

1. **`--epochs`, default 2 instead of 4.** A config change, no refactor, and it halves the update. It
   also corrects a misconfiguration this plan itself flagged: 300 Adam steps over 2,400 samples is too
   much optimiser movement for the batch, so 2 epochs (150 steps) is closer to sane. Not a
   compromise — a fix.
2. **A `gates` task** running every check in one gradle invocation. Measured: `gaecheck` takes 0.22 s
   bare and 2.4 s through gradle, so gradle's per-invocation overhead is ~90% of the cost. Nineteen
   gates: 21 s in one invocation against roughly a minute run separately. The checks are not slow; the
   harness around them is. Named `gates`, not `check`, because the `java-library` plugin already
   contributes a lifecycle `check` and Gradle refuses to shadow it. Two of the nineteen are
   `configcheck` and `paritycheck`, added with the externalised configuration; `paritycheck` is the one
   that found process-spanning game state (`Bones`), which no gate before it was able to reach.
3. **Fewer generations per run, seed fixed.** Deliberately the prototype's choice: a prototype needs to
   see a trend in a minute, not a correct long run overnight.

Gate, met: 20 generations went from ~36 min to ~7, and a 6-generation prototype run from ~36 min to 2.

**Item 3 of that list is incomplete.** Weights are still never written to disk (TODO 1.3), so a
prototype run cannot be resumed, inspected mid-flight, or compared against another run. That is
step 4b.

### Step 3 — Ship sampled transitions to the trainer — **done**

Landed with step 2 rather than after it: the worker had to send the transitions for the trainer's
update to have anything to run on, so the two could not be separated without an intermediate state
where transitions existed and were thrown away.

As specified, except:

- **The replay still rides the episode frame** rather than its own message. It is one replay every
  third episode, a small share of a generation's traffic, and splitting it is worth doing only once
  there is a measurement saying so. `Protocol.MSG_REPLAY` is reserved and numbered for it.
- **`--sample-rate`, `--max-sampled-per-episode` and `--max-samples-per-generation` exist** and are
  documented in `TODO.md` §1. The last is a memory valve rather than a design choice: 320 episodes
  wide, on a machine whose pagefile is 2 GB.

Gate, met: `policy` and `value` are non-zero, advantages arrive with a real spread, and the report
shows the sampled count, its byte cost, and the advantage statistics **before** normalisation.

**What this did not deliver: a model.** See `TODO.md` §1.3. Nothing writes weights to disk, so all
three steps above produce a loop that learns and a process that then forgets.

Two bugs came out of it, and their shape is worth recording, because it is the third and fourth time:

| Bug | Symptom | Why nothing caught it |
| --- | --- | --- |
| `EpisodeCollector` read `heroPosition` before `env.step()`, not after | Every trainer recording diverged at step 0 **while still playing** — the file parsed, the run scored, the dungeon looked like a dungeon | Only `verify` compares step by step, and the scripted path reads position correctly so `--record` was unaffected. Found by trying to play one back. |
| Random-seed episodes were recorded with the *requested* seed, which is empty for them | `verify` reset onto a fresh draw and reported a divergence at step 0, reading as a broken seed lock | `SeedPool` leaks 10% of episodes to random seeds deliberately, so ~10% of recordings were affected and none of them could ever verify |

Both are now covered by `collectcheck`, and both were mutation-tested. The pattern — code that had
never been run, being wrong — has now happened four times: two headless crashes, the step ordering,
and the seed resolution. It is the single most productive thing to go looking for in this project.

### Step 3b — Instrument the loop — **done**

Steps 0–3 built a loop that learns. **This is the step that makes it possible to tell whether it
does.** It comes before the parallel update, not after, because the parallel update's only current
success criterion is "faster", and a faster loop you cannot evaluate is not an improvement.

Three defects found while auditing the plan for this step, all of which make a run unjudgeable:

**1. `clip=` is not the clip fraction.** `PPO.update` counted `|advantage| > clipEpsilon` on a
*normalised* advantage. After normalisation advantages have unit variance, so that expression is
simply `P(|N(0,1)| > 0.2)` = **0.8415** — a constant. Observed across real generations: 0.81, 0.83,
0.85, 0.90, which is that constant. PPO's clip fraction is the fraction of samples whose *ratio* fell
outside `[1-ε, 1+ε]`; `Policy.accumulatePolicyGradient` computes `clipBinding` internally and discards
it. **This is the one number that says the policy is moving too far per update, and it currently
cannot.**

**2. Nothing accumulates across generations.** `Trainer` keeps `lastEpisodes` — one generation — and
discards it. `diag.Graph` is dead code (the `Graph` in the repo is the game's unrelated
`com.watabou.utils.Graph`). A run therefore prints a block per generation into console scrollback and
throws it away. There is no way to see a trend, and with the agent never having left floor 1, no way
to see that a change helped.

**3. Nothing compares runs.** Directly downstream of (2), and the reason `TODO.md` 1.3 matters: with
no persisted weights and no persisted history, "generation 200 scored better than generation 100" has
to be believed rather than checked.

All three are now done. `Policy.accumulatePolicyGradient` returns `clipBinding` and `PPO` counts it;
`train/MetricsHistory` appends a CSV row per generation and renders the end-of-run trend through the
revived `diag.Graph`.

**What the corrected metric actually showed.** Across 9 generations the real clip fraction ranges
**0.047 to 0.737** — it moves, where the old one sat at 0.8415 regardless of the policy. The first
generation's 0.737 is the interesting one: a third of samples were having their gradient zeroed by the
clip on the very first update, which is a learning rate that is too high for the batch and is now
visible rather than invisible. Declining afterwards is what settling looks like.

CSV and graph both, because they answer different questions: CSV is how two runs are compared, the
graph is how one run reads.

Gate, met: 9 generations of history survive the process in a 24-column CSV; appending a second run adds
rows without a second header; `clip=` is the real ratio-clip fraction and the check fails when
`Policy` is mutated to report a non-binding clip.

### Step 4 — Parallel minibatch update — **done**

`--update-threads N` splits each minibatch across N threads. Measured, 8 workers × 3 generations,
cost per sample:

| Threads | ms/sample | Speedup |
| --- | --- | --- |
| 1 | 8.98 | 1.00× |
| 2 | 5.09 | 1.76× |
| 4 | 2.73 | 3.29× |
| 8 | 2.59 | 3.47× |

**Measured 3.47× at 8 threads against a predicted 5×.** The shortfall is the gradient reduction —
14.3 MB per thread per minibatch — which is why 4 and 8 threads are nearly identical: past 4, the
reduction costs about what the extra compute saves. The prediction was directionally right and
optimistically quantified, which is the usual outcome for an estimate that names its own unknowns
without measuring them.

#### Why per-thread networks rather than shared tensors

`Network` is thread-hostile by construction. Every layer holds parameters, Adam moments, gradient
accumulators **and** forward/backward scratch in one object — `Dense` has `W, b, mW, vW, mb, vb, gW,
gb, preAct`; `LSTM` adds `gates, scratch, h, c, hPrev, cPrev`. Sharing those would have threads
corrupting each other's hidden state, not merely each other's scratch.

Each thread instead gets its own `Network` **holding a copy of the parameters**, `copyParametersFrom`
the master at the start of each minibatch. The cost is one 14.3 MB copy per minibatch, about 2 ms
against 32 samples of forward and backward — 0.5%. What it buys is that no thread can observe or
corrupt another's writes *even if the reduction is wrong*: the worst a bad reduction can do is produce
a bad gradient, not a corrupt model. Sharing the tensors would save 0.5% and reintroduce exactly the
aliasing this avoids.

Gradients are reduced by `addGradientsTo`, which adds rather than assigns — each thread contributes a
shard of one minibatch's gradient, and the caller scales by `1 / minibatchSize` afterwards, the same
place the serial path scales, so the two produce the same number rather than a similar-looking one.

#### Three defects had to come out first, and only one was anticipated

**The gradient leaked between samples.** `Network.backward` cleared `dPrev` but not `dCell`, and
`LSTM.backward` reads the incoming `dCPrev` *before* overwriting it. So every sample's gradient was a
function of whichever sample was processed before it. Measured on `conv.gb[15]`: four samples in
sequence gave 0.0892, 0.2075, 0.3372, 0.4235, where the same four run individually gave 0.0892,
0.0419, −0.1487, 0.0517. Not additive.

This is the defect that made this step look "not actionable" when it was only *broken*. Samples had to
be independent before a minibatch could be split across threads, and they were not — for two separate
reasons: the replay ran under the wrong recurrent state (step 2's follow-up, below), and the gradient
leaked. Both are now fixed, and `statecheck` and `parallelcheck` assert the two halves of that.

It was masked for the whole of the previous implementation because the buffer is shuffled: a leaked
gradient arrived attached to an unrelated sample, so nothing ever compared one sample's gradient
against that same sample processed alone. Two bugs cancelling is the most expensive kind to find, and
the only reliable detector is one that varies one thing at a time.

Gate, met: `parallelcheck` — 4 cases, a gate, mutation-tested. It asserts that a sharded accumulation
equals a single-network one at 2, 4 and 8 shards, and that the answer does not depend on the shard
count. Tolerance is 1e-4 relative to each tensor's own L2 norm; observed disagreement ~2e-7, so the
slack is five orders of magnitude wider than float rounding. Reverting the `dCell` clear fails all
four cases.

#### How the check was arrived at, because the wrong turns are the useful part

The first version drove all of `PPO.update` twice and compared the gradients left afterwards. That
measures the wrong thing twice: the buffer is shuffled, and the surviving gradient is only the last
minibatch's, after two Adam steps have moved the weights underneath everything. Four probes followed.
The worst added the shard sums into the serial accumulation's own tensors, so it compared `sum(shards)`
against zero and reported agreement for any input whatsoever. One more reported a correct reduction as
a 34% error, because it normalised by each element rather than by the tensor's norm — turning
cancellation-rounded entries into unbounded "relative" errors.

What actually located the bug was measuring one sample alone and then in sequence, on the same network:
0.0419 versus 0.1182. That immediately narrowed it to cross-sample state rather than to anything about
shards, threading, or the reduction — all three of which had been suspects by then and all three of
which were innocent. **When a check disagrees with an implementation, suspect the check.**


### Step 5 - fp16 weight push - **not warranted**

**Not warranted.** The premise below was measured before the update was parallelised and before the
epoch default was halved. The barrier is now a smaller share of a wall time that step 4 cut by up to
3.5x, and the condition this step was written under - only if the barrier shows up - is not met by
anything currently recorded. Revisit if a run profile says otherwise.

The original premise, kept for the record: the barrier is `3,583,827 x 4 B x 20 = 286 MB` per generation, pushed
serially per worker, during which no worker can run. Measured at 12-26% of wall at 20 workers, 45% at 4.

Send weights as fp16 and cast back to fp32 on arrival: **286 MB → 143 MB**, barrier roughly halved.
Around 40 lines, no algorithmic change, no new hyperparameters.

`--episodes` also dilutes the barrier and already exists.

Gate: barrier percentage down, `gradcheck` green, throughput up.


Deferred deliberately. §3 is the justification.

### Step 6 - Bit-packing / RLE - **not warranted**

**Not warranted, and will not be at these rates.** Transport is 0.12 s against ~7 s of collection and
~53 s of update (step 4), so the pipe is under 0.2% of the barrier. Bit-packing is a bandwidth
optimisation and there is no bandwidth problem to optimise.

The original case, kept for the record:

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


### Step 7 - IMPALA - **not warranted**

**Not warranted.** This plan chose worker-side GAE with an on-policy PPO update, which is the
opposite trade to IMPALA's off-policy correction. Adopting it now would discard steps 2 and 3 to solve a
problem they solved differently and measurably. Revisit only if sample efficiency, rather than
compute, becomes the binding constraint.

The original case, kept for the record:

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

| | before steps 2–3 | after steps 2–3 (current) | after step 4 |
| --- | --- | --- | --- |
| transport/gen | ~0 (discarded) | ~118 MB | ~118 MB |
| update/gen | 0 (empty buffer) | ~53 s, 1 thread, 2 epochs | ~15 s at 4 threads (measured 3.29×) |
| barrier | 12–26% | ~90% of wall | much lower |
| system CPU | ~50% avg, 90–100% peak | trainer-core bound | spread across cores |
| pooled gradient | no | yes | yes |
| run history | none | CSV + graph (step 3b) | yes |
| weights on disk | none | checkpoint (step 4b) | yes, resumable |

Throughput *fell* in step 3 — real PPO work replaced free discard. That is expected and is not a
regression. The size of the fall is measured: about 53 s of trainer CPU per generation at 5% and the
current 2 epochs, so the batch configuration has to be chosen against that rather than against taste.
Step 4 then cut it by 3.29x, so the update is no longer the wall - but the collection it now waits
on is 19 of 20 cores against the trainer's 4, which is the shape step 7's caveat describes.

---

## 8. Risks

| Risk | Severity | Note |
| --- | --- | --- |
| Advantages differ from full-buffer computation | blocking | Step 2 gate. Cheap to test. |
| Worker/trainer gamma drift | medium | Currently masked by identical defaults. Fixed in step 2. |
| Sample too small (~2,400 for 3.5M params) | medium | Needs a knob and a measurement plan. Low end of workable. |
| Update cost dominates everything | **mitigated, not solved** | Was 53 s vs 7 s of collection. Step 4 cut it 3.29x; at 4 threads the update is ~15 s and collection is the wall again. |
| A run cannot be judged | **hit, fixed** | `clip=` reported `P(\|N(0,1)\|>0.2)` = 0.8415 rather than the ratio-clip fraction, and nothing accumulated across generations. Fixed in step 3b: metrics CSV, revived `diag.Graph`, real clip fraction. |
| A crash costs the whole run | **hit, fixed** | Atomic checkpoint every 25 generations and on exit, so a crash costs the interval rather than the run. Step 4b / TODO 1.3. |
| A resumed run is quietly a worse run | **avoided by design** | Adam moments and the optimiser step count are saved, not just weights. Dropping them restarts bias correction near 0.1x and averages from zero — trains, but worse, with normal-looking metrics. |
| 300 Adam steps on 2,400 samples destabilises training | medium | Only visible once losses are non-zero. Re-tune from step 1's grid; step 2b defaults epochs to 2. |
| Parallel update silently races on shared state | **high** | `LSTM` writes `h`/`c` in place. `gradcheck` runs single-threaded and cannot catch it. Needs its own equivalence check. |
| 95% of collected experience unused | accepted | Fine to start with. Not free. |
| Replay transfer crowds the transition channel | low | Split the message in step 3. |
| GAE in worker breaks on chunk boundaries | medium | Gone: an episode is one GAE segment, and chunking moves to step 2's sampling cap. |
| Tail retention biases the gradient | low | A fixed position-based subset, not advantage-ranked. See §5. |
| `gaecheck` cannot see `EpisodeCollector`'s retention loop | medium | A mutation that disables tail retention still passes. Only `collectcheck` exercises the loop, and it does not assert retention. |
| A trainer recording that will not replay | **hit, fixed** | The hero position was recorded before the step rather than after. Every recording diverged at step 0 while still playing. Covered by `collectcheck`. |

---

## 9. Open questions

- Sample rate: 5% gives ~2,400 steps. Now measured rather than guessed — §4 — and the answer is that
  5% at the current 2 epochs costs ~53 s of trainer CPU per generation. Whether that is acceptable
  depends on step 4's thread count, and 1 epoch vs 2 moves it by 2×. `--sample-rate` is a knob for
  exactly this.
- Should episodes longer than a threshold be capped at the source, given GAE cost grows linearly
  while value signal does not?
- Replay retention: currently every third episode. At 320 episodes/generation that is a
  disproportionate share of the pipe for a diagnostic feature.
- How many threads can the update use before the gradient reduction (14.3 MB per thread per minibatch)
  costs more than the parallelism saves? Unknown until step 4 exists. The prognosis is ~5× at 8 threads,
  not 8× — see step 4 for why the optimiser and memory bandwidth cap it.
- **Is the prototype's batch the one the real run should use?** Step 2b deliberately trains small and
  fast. The sample rate and epochs chosen there are for seeing a trend in a minute, and are unlikely to
  be what a long run wants. Scaling up is deferred, so the point at which to revisit is unrecorded.
