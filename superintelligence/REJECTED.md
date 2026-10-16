# Rejected and deferred

Things that were proposed, measured or argued, and **not** done. Kept so they are not re-proposed
without their reasoning. A proposal here is not a failure; several are the right answer.

## A native or ONNX runtime for the learner

**Proposed by** `review.md` §6 Feature 3. **Not attempted.**

The numbers are right and the conclusion does not follow from them. Pure-Java forward and backward is
11.29 ms/sample on one thread and 2.73 ms at four; past four the 14.3 MB gradient reduction per
thread per minibatch saturates memory bandwidth (2.59 ms at eight threads). That is a real ceiling on
learner throughput.

It is not yet the binding one. A 30-generation run took **12 min 41 s** end to end at 8 workers and 4
update threads, so the learner is not what is stopping this project. Adding a native runtime also means
adding a dependency and a platform matrix to a module whose whole point is running the game's own
code unmodified, and a learner that no longer runs on the same matrix as the environment is a
different project.

**Revisit when** a run profile shows the learner, not the collection and not the barrier, is the wall.

## The libGDX trainer dashboard and the live reward monitor

**Proposed by** `docs.md:10` and `research.md:16`; `TODO.md` §3.1 and §3.3. **Not started.**

The console already prints a per-floor reward ledger, a colour-coded score-over-turns chart and an
end-of-run trend, and `out/evidence/` holds a CSV per run. What is missing is a window and a type
table. It is worth doing when someone watches a run rather than reading about one.

## fp16 weight push

**Proposed by** `PLAN-data-flow.md` §5. **Declined on measurement.**

286 MB per generation becomes 143 MB. The barrier it halves is now a smaller share of a wall that
step 4 cut by up to 3.5x, and the run completes 30 generations. Not warranted at the current
transport.

## Bit-packing and RLE on the grid

**Proposed by** `PLAN-data-flow.md` §6 and `WORKER-DATA-FLOW.md` §9. **Declined, then done for a
different reason.**

The bandwidth argument was wrong — transport is 0.12 s against ~7 s of collection. It was built
anyway, because the grid was **90% of every transition's memory**, not of its transport: 50,688 bytes
to 6,336 at 22 planes over 48x48, lossless. Worth recording that the right reason was the second one
and the plan's reason was the one that did not apply.

## IMPALA

**Proposed by** `research.md:26` and `PLAN-data-flow.md` §7. **Declined on architecture.**

Worker-side GAE with an on-policy PPO update is the opposite trade to IMPALA's off-policy correction.
Adopting it discards steps 2 and 3 to solve a problem they solved differently and measurably.

## Frame-differencing the observation

**Proposed implicitly** by any bandwidth argument. **Impossible, and recorded so it stops coming
back.**

PPO shuffles minibatches across epochs, so any delta encoding needs random access to any
observation. Delta encoding and shuffling are mutually exclusive.

## Event-keyed RNG

**Proposed by** the divergence investigation. **Not adopted.**

Correct in principle, and it is a rewrite of the game's randomness across every subsystem. The bug it
would most obviously address is `issues.md` 25, which was a shared object and not a stream-ordering
fault.

## Making `holdAllies` reachable in the trainer

**Deliberately not done.** `InterlevelScene.fall()` opens with `Mob.holdAllies`; copying that into
`LevelPipeline.handleFall` would put an unseeded draw into every chasm fall in the trainer. The
call is left out on purpose, not overlooked.

## Deciding the corpus is generated rather than checked in

**Settled by practice, never formally.** It is checked in, 20 recordings, and `viewcheck` and
`playbackcheck` both read it. The decision was never written down because it was never in doubt.

## Splitting the gate budget into a fast gate and a large periodic job

**Considered and declined.** One `gates` task at ~40 s is cheaper to reason about than two tiers, and
`viewcheck` is already the slow manual gate outside it.