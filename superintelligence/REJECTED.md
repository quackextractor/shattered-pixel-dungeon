# Rejected and deferred

Things that were proposed, measured or argued, and **not** done. Kept so they are not re-proposed
without their reasoning. A proposal here is not a failure; several are the right answer.

`TODO.md` carries open work only, so nothing declined appears in both. A declined item that is
still worth doing someday is in one of two states: **declined**, meaning the answer is no and the
reason is on this page, or **deferred**, meaning the answer is later and the trigger is stated.

**Shipped, not rejected.** The own-cell `INTERACT` this page carried for a while is done: the
obstruction was `issues.md` 38 and the fixture `issues.md` 26 was diagnosed against, both closed.
Two lines in `ActionMapper`, a regenerated corpus, `rewardcheck` at 35 cases and `:desktop:viewcheck`
at 20 of 20. Its reasoning is in `CHANGELOG.md`.

## Raising `reward.stair_potential_weight`

**Proposed by** `TODO.md` §1, as the dense term that pays for walking towards the exit. **Declined
on measurement: 5x the weight, 0 of 960 episodes reach floor 2.**

| | shipped (2.0) | A/B (10.0) |
| --- | --- | --- |
| `meanScore` gen 0-14 → 15-29 | +2.39 +/- 44.6 | -51.28 +/- 38.6 |
| `meanObjectiveScore` | -115.34 +/- 48.4 | **-214.14 +/- 42.8** |
| `depth>=2` | 0.0% → 0.0% | 0.0% → 0.0% |

Both runs are 30 generations, 8 workers x 4 episodes, 4 update threads, seed 20261016,
`train.random_seed_rate=0`, `reward.profile=default`, one variable between them. Evidence
`out/evidence/descend-30-ab.txt`.

The term is potential-based, so it telescopes: an episode's total is `weight x (distance at the
start - distance at the end)`, and five times the weight multiplies that difference rather than
creating one. The objective window getting **5 sigma worse** while the shaping improves is what a
term produces when the policy is not exploiting it, and `depth>=2` staying at zero is the direct
answer: the policy does not respond to the direction of the gradient at all.

**It cannot respond to it.** The potential is the breadth-first distance to the stairs across the
whole floor, and `obs.GridChannel.EXIT_BEACON` lights only once they have been in view - not from
generation, because painting it from the start would hand the objective over. So the term pays for
progress towards something the observation does not carry.

**Revisit when** the observation carries the stairs further than "seen once" - an ego-centred grid,
or a beacon that survives the window edge - which is the ego-centring entry below and its own
trigger.

## A native or ONNX runtime for the learner

**Proposed by** the external code review, as a native or ONNX learner. **Not attempted.**

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

## Settled by practice, then recorded

These were decided by what the project did and never written down, which is why they read as open
questions in documents that outlived the decision. They are decisions now, and the two stubs at the
foot of this page are the same decisions in one line each.

**The corpus is checked in, not generated on demand.** 20 recordings under `test-replays/`, read by
both `viewcheck` and `playbackcheck`. Regenerating it on demand would invalidate the corpus every time
the observation width or an action head changed, and the whole value of a checked-in corpus is that it
is the same bytes a gate read last time.

**One gate tier, not a fast one and a slow one.** One `gates` task at ~40 s is cheaper to reason about
than two tiers, and `viewcheck` is already the slow manual gate outside it - it needs a display, so
splitting the budget would have meant moving a gate that cannot run here into a gate that can.


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

## Ego-centring the observation grid on `hero.pos`

**Proposed by** the external code review: "either the grid is ego-centric (centered on
`hero.pos`) or downsampled". **Downsampling is done; ego-centring is declined.**

Measured across 1,040 floors over 40 seeds at depths 1-26: **419 exceed 48 tiles on at least one
axis**, the largest 81. So a fixed `env.grid_width` x `env.grid_height` window clips real floors.

**Why it is not a config change.** `ObservationEncoder`'s planes are written in *level* coordinates
and index the grid absolutely. Re-centring them on `hero.pos` is a rewrite of every plane's plane
arithmetic, and it invalidates every checkpoint - the same thing `env.max_slots` 32 -> 96 did, and
for the same reason.

The half of the problem that is cheap is already done: `obs.GridChannel.EXIT_BEACON` carries the
stair's location forward across the window boundary, so a hero that walked past and away still has a
channel carrying the objective.

**Revisit with** the next measurement, not in the middle of one. It belongs with a run whose numbers
are going to be read, because it invalidates every weight file there is.

## Vectorising `Dense.matvec` with `jdk.incubator.vector`

**Proposed by** the external code review: "keep the project 100% pure Java ... and vectorise
`Dense.forward` / `Dense.backward` using the built-in JDK Vector API". **Declined on measurement.**

The incubator module is available on this JDK — `javac --add-modules jdk.incubator.vector` compiles and
`FloatVector.SPECIES_PREFERRED` reports 8 lanes here — so this is not blocked on the toolchain. It is
blocked on where the arithmetic is.

Multiply-accumulates per forward pass, from the layer shapes after the reduction convolution:

| layer | MACs | share |
| --- | --- | --- |
| `conv`, 529 positions x 24 filters x 336 patch | 4,264,896 | 62.0% |
| `reduce`, 100 positions x 32 filters x 384 patch | 1,228,800 | 17.9% |
| `trunk`, 4,567 x 256 | 1,169,152 | 17.0% |
| `memory`, 4 x (256 + 128) x 128 | 196,608 | 2.9% |
| the four heads, 128 x 125 | 16,000 | 0.2% |

**The dense layer the review's sketch vectorises is 17% of the forward pass.** Forward is 4.95 ms of
the 11.07 ms forward+backward, so the whole of that 17% is **7.6% of an update**, and a vectorised
matvec is not free — the honest ceiling is a few per cent.

**And the part worth vectorising is the part the sketch does not touch.** `Conv2D.forward` runs
`for p { for q { for k } }` over an im2col buffer laid out row-major in `k`, so the inner loop strides
by `col.cols` — the worst layout for a vector unit, and 80% of the work. Getting a win there means
restructuring `Conv2D`, not replacing a scalar loop in `Dense`.

The cost side is four build sites: `compileJava`, `javaclint` (whose `-Xlint:all -Werror` meets the
incubator module's mandatory warning and would need a suppression to opt back in), `trainerJvmArgs`,
and the worker JVM's command line.

**Revisit when** a run profile shows the learner rather than collection and the barrier is the wall —
the same trigger `REJECTED.md` already gives for a native or ONNX runtime, and the arithmetic above is
why this one has the same answer rather than a different one.

## Moving the remaining sound-pitch draws off `Random`

**Proposed by** the external code review: "migrate the 11 sound-pitch `Random` calls to `PRandom`".
**Declined on measurement.**

Ten sites, counted by scanning `core` and `SPD-classes` for a `Random.*` draw passed as the pitch
argument of `play` / `playDelayed` / `hitSound` / `blockSound`:

| Site | Note |
| --- | --- |
| `Hero.java:2337`, `:2339`, `:2344`, `:2346`, `:2349` | one per terrain type the step lands on |
| `Char.java:502`, `:503` | `blockSound` then `hitSound` |
| `HolyLance.java:126` | the neighbouring `:125` already reads `PRandom` |
| `GreatCrab.java:104` | the neighbouring `:88` already reads `PRandom` |
| `WandOfLivingEarth.java:178` | the neighbouring `:111` and `:167` already read `PRandom` |

The eleventh site `ENGINE-CHANGES.md` §4 named is `WndSettings.java:931`, a `Random.oneOf` over sound
ids and not a pitch.

**The change is one line per site and it invalidates the whole committed corpus.** Every one of these
draws is made by *both* environments, which is why §4 says they cannot separate them - and that
symmetry is also why moving them is not free. Taking a draw off the gameplay stream changes the
number of values a footstep consumes, so the first step on the first floor diverges from every
recording in `test-replays/`, including `duelist-death-last.replay`, which is the fixture
`issues.md` 26 is diagnosed against and `viewcheck` is 19 of 20 because of.

**What it buys is tidiness and nothing else.** A sound's pitch is still random; the only effect is
that the presentation draw stops consuming the gameplay stream, which the game's own `PRandom`
exists for. It cannot fix a divergence, because it cannot cause one.

**Revisit when** the corpus is regenerated for another reason - a changed observation width, a new
action head - at which point it is ten mechanical edits and no recording is lost by them.

## Making `holdAllies` reachable in the trainer

**Deliberately not done.** `InterlevelScene.fall()` opens with `Mob.holdAllies`; copying that into
`LevelPipeline.handleFall` would put an unseeded draw into every chasm fall in the trainer. The
call is left out on purpose, not overlooked.

## Deciding the corpus is generated rather than checked in

**Settled by practice, never formally.** It is checked in, 20 recordings, and `viewcheck` and
`playbackcheck` both read it.

## Splitting the gate budget into a fast gate and a large periodic job

**Considered and declined.** One `gates` task at ~40 s is cheaper to reason about than two tiers, and
`viewcheck` is already the slow manual gate outside it.
