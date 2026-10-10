# Superintelligence TODO

Status of the work in [`docs.md`](docs.md) and [`research.md`](research.md), written against the
code as it stands. "Verified" means it was run and observed, not merely written.

Last updated: 2026-10-10, after the five viewer/recording issues in
[`issues.md`](issues.md) were worked through, and after the HUD overflow that followed from them was
found and fixed.

---

## 0.0.1 The viewer issues, 1-5 - FIXED

Worked from [`issues.md`](issues.md) "Viewer / recordings". Each is recorded with its cause, because
every one of them reads as a symptom and none of them is where the fault was.

**1. `R` after a finished recording leaves it finished, and SPACE then waits the hero.** Two faults,
one behind the other, and neither is where it looks. A restart nulls `Dungeon.hero` and re-enters the
interlevel scene, which takes at least a fade to build floor 1 — and the frame driver runs throughout,
because it is `GameScene`'s update hook. So the first frame after `R` found a null hero, and a null hero
is what ends a run everywhere else in that class: the drain called `playback.finish()` and halted
"run ended - hero is dead" before the rebuilt level existed. The HUD reported a finished replay over a
cursor that had just been rewound, which is why pressing `R` appeared to do nothing at all. Playback
now waits for the rebuilt level rather than reading a missing hero as an ending.

The SPACE half is separate and was about window rather than state. `InterlevelScene` calls
`KeyEvent.clearListeners()` every time it hands off to the game scene, and the viewer's key overrides
were only re-asserted when the HUD was rebuilt — so in the gap the game's own `SPDAction.WAIT_OR_PICKUP`
binding on SPACE was live and unopposed, and a pause keypress waited the hero. That spends a turn no
recorded step asked for, so the next comparison diverges. The overrides are now re-asserted every frame
while the viewer is installed, and the listener is kept rather than rebuilt.

**2. A death recording's last step did not play.** The test for "the recording ends here" sat at the
*top* of the drain loop, so it fired on the frame the final action was injected — the action went in,
and playback ended before the hero ever performed it. Three of the seventeen committed recordings end in
death and all three stopped with the hero still standing, one step short of the death they describe.
The check is now made after the drain has run and the hero has acted.

**And that exposed a third fault, which is the more interesting one.** Draining the last step meant
comparing it for the first time, and `cleric-mid` failed immediately — seven turns and eight health
away from what its recording says. `ReplayPlayer`'s drain had none of the trainer's resting-stall
backstop, so it sat out a rest `LevelPipeline.runToHeroReady` had already given up on: `Hero.act()`
handles a resting hero with no action by spending `TIME_TO_REST` and calling `next()` without ever
becoming ready, so the drain simply waited until the rest ended on its own. The recording's
`termination=STALLED` is that backstop firing. The viewer now carries it too, counting scheduler steps
the way the trainer's does — as a field, not a loop variable, because the viewer yields the frame after
three blocked iterations and a local counter never reached the threshold.

It went unseen for as long as it did because the only step it affects is the last one, and the last one
was skipped rather than drained. `playbackcheck` now plays the committed corpus for exactly this
reason: every other case there builds its own fixture, and a freshly recorded run does not end on a rest.

**3. "Turns" is actions, not engine turns.** The HUD and the launch banner both said `turns N`,
where `Replay.turns` is `SPDEnv.turnsTotal()` — a count of decisions, not of game turns. The
engine's own clock is `Actor.now()`, which is fractional (a heavy weapon costs two, haste less than
one), so the two are not interchangeable and a recording's `turns` is not the hero's turn count.
Both are now labelled for what they are: `actions N` for the decision count and `engine time T`
for `Actor.now()`.

**4. Score was a single number with no per-step history.** `Replay.Step.reward` was recorded and
never displayed; the viewer showed only the run's final total, so a live view could not say which
action lost 100 points or when the score started falling. The HUD now shows the running score, the
per-step delta and a running gain/loss split, and `ReplayPlayback` computes it from the recording so
the same numbers appear in a headless check.

**5. Positions were one number.** `heroPos` is `y * width + x`, and the viewer printed it raw. Both
the HUD and the divergence message now print `(x, y)` with `x` to the right and `y` downward —
engine order, so it matches the tile map — and the raw index is kept alongside for anyone comparing
against a trace.

Gated by new cases in `playbackcheck` (10 -> 18 checks) and by `viewcheck` over the whole corpus.
Every one of the new checks is mutation-tested: restoring the old drain ordering fails the death case,
disarming the restart wait fails the rebuild case, flipping the y axis fails the coordinate case, and
reporting the net instead of the gain half fails the score case. The old death check was passing for the
wrong reason and had to be rebuilt — it forced the recorded health to zero on the last step of a run
whose hero was at full health, which was a lie the drain had been skipping the comparison for.

---

## 0.0.2 The HUD ran off the screen - FIXED

**Reported as "the data doesn't line break, it goes off screen". The cause was that `BitmapText`
cannot line break at all.** Its font is built from `BitmapText.Font.LATIN_FULL`, which has no newline
glyph, and both `measure()` and `updateVertices()` feed every character straight through
`font.get()`. A `\n` therefore has no glyph, no line advance and no break: it draws as a blank and the
text keeps running to the right until it leaves the window. The HUD had built its rows with `\n` from
the start, so it was never a new bug — but it was invisible while the HUD was short enough to fit most
window sizes, and issue 4 above put a score line on it long enough to overflow any ordinary window.

The corroborating evidence was all around it. No `BitmapText` anywhere in `core` is ever given a `\n`,
because the game's own multi-line component — `RenderedTextBlock`, which the windows use — exists
precisely because `BitmapText` will not wrap. The viewer's HUD was the sole exception, and had been
since before any of these issues were filed.

Now a pool of one-line gizmos, wrapped on spaces and measured against the real UI font through
`BitmapText.measure()` rather than an assumed characters-per-line. Two details that are load-bearing:
the pool *grows* rather than having a fixed size, because the same text needs more lines on a narrow
window and a fixed pool would silently truncate the overflow — the failure being fixed; and a word too
wide to break on spaces (a deep seed hash) is kept whole rather than dropped, since dropping it would
silently hide the seed, which is the one value saying which recording is on screen.

`wrapText` takes its width measurer as a parameter so it can be checked headlessly, where there is no
font and every real measurement is zero. The nineteenth `playbackcheck` case asserts that no line comes
out wider than the limit, that wrapping does not alter the content, and that an explicit `\n` still
forces a break. Mutation-tested by disabling the width test, which fails it. `viewcheck` re-run over
the whole corpus: 17 of 17 clean.

---

## 0.0.0 The viewer and the trainer ran on different randomness - FIXED

**Found and fixed.** 14 of 17 committed recordings diverged in the rendered viewer while verifying
exactly headlessly. The cause was not the scheduler and not the step gate, both of which had been
proposed and measured and refuted. Four presentation draws were spending the gameplay RNG stream,
and only the rendered game makes them:

- `CharSprite.link` — a random sprite facing, drawn once per actor. `HeadlessSprite` overrides `link()`
  and never reaches it, so the trainer never drew and the viewer always did: 12 values.
- `AttackIndicator.checkEnemies` — which mob the overlay highlights, once per step.
- `Wand.staffFx` and `MagesStaff`'s staff particle — cosmetic direction and size jitter.

Every damage and defence roll after the first draw therefore came from a different point in the stream
than the recording was made from. Located by computing the base generator's expected output from
`scrambleSeed(Dungeon.seed)` and looking up where each side's first gameplay draw fell: headless at
value #5, the viewer at #17.

**It went unseen for as long as it did because the instrument was wrong.** `RandomTrace` reported
identical draw counts at every step of every recording, because `Random.Int(int, boolean)` and
`Random.shuffle(List)` advanced the generator without recording. `PLAN-replay-parity.md` §1.4 concluded
from that — correctly, given what it could see — that the stream was not offset, and five subsequent
hypotheses about ordering were refuted by measurement.

**Status.** `gradle :desktop:viewcheck` is green on all 17 and stable across repeated runs. The four
fixes are **engine changes**, per `instructions.md` §12.3, and are now committed with the proposal
record in `ENGINE-CHANGES.md`. `observecheck` gates the class of fault — every public draw path must be
counted, mutation-tested — and `viewdiff` is the automatic two-sided diff.
See `ISSUE-viewer-frame-drift.md`, `FINDINGS-viewer-fidelity.md` and `ENGINE-CHANGES.md`.

---

## 0. P0 - RESOLVED - the environment is not reproducible across processes

**Fixed.** Recording a run and re-executing it in a fresh process now reproduces exactly.

Verified: 120 rollouts - 4 seeds x 5 hero classes x 6 repeats - produce 1 distinct score per
seed/hero pair, and a recorded 499-step run verifies 4/4 in fresh processes. Three rollouts of one seed
across three fresh JVMs produce byte-identical `.replay` files, and a sweep of 32 recordings over 8 seeds
x 4 hero classes in one process replays exactly, twice.

### 0.4 A dead hero's remains reached the next run - found by the parity sweep

The last one, and the only one that needed a *sweep* rather than a repeat to see. `Bones` keeps a fallen
hero's belongings in statics plus a `bones.dat` under the process's file root, and
`RegularLevel.createItems` reads them to drop a `REMAINS` heap. For a player returning to a dungeon that
is the feature; for an environment playing thousands of independent runs in one process it means the
world is a function of process history as well as of its seed.

`diag.ParityCheck` recorded 32 runs and verified each twice. Two diverged, both on inventory, both on a
remnant - one recording carrying a HUNTRESS's `BowFragment` where its own replay had a ROGUE's
`CloakScrap`, one the other way round. Same seed, same hero, same actions; only what had died in
between differed.

Two things are worth recording about the fix rather than the fault:

- **Every other gate was structurally blind to it.** Each either plays one episode per process or never
  lets the hero die. `resetcheck` proves a reset is a function of its arguments, which is about the
environment; this was about the recording, and about game state that is supposed to persist.
- **The first attempt at the fix was wrong in a way every other test passed straight through.** Clearing
  the remains *after* `startRun` looks correct - it is where the other three statics are cleared - but
  remains are read during level generation, so the new floor was already carrying the previous hero's
  heap. `RunState` is now called before `startRun`, and `resetcheck` has a case that kills a hero and
  asserts the next floor carries none of it. That case also needed a positive control of its own: for
  its first few revisions it used a seed whose hero *survived*, so there was nothing to inherit and it
  passed without testing anything.

`Bones.clear()` is the one addition to the game, and it is additive only - nothing in the game calls
it.

A second pass found more identity-hash iteration after the first fix: `Char.buffs(Class)` handed
callers a `HashSet`, `Random.chances(HashMap)` chose secret room contents off a `Class`-keyed
`HashMap`, `Mob.chooseEnemy` resolved ties by iteration order, and `CursingTrap` / `VaultLevel` /
`Hero` used `Collections.shuffle`, which ignores the seeded generator entirely. All made
insertion-ordered or switched to `Random.shuffle`.

One trap worth recording: `GameSettings.getString(key, def, maxLength)` treats an over-long stored
value as corrupt and overwrites it with the default, and `SPDSettings.customSeed()` reads with a
20 character cap. A 23 character seed was therefore silently discarded and the run continued on a
*random* seed - which looked exactly like residual nondeterminism and cost real time to
rediscover. `LevelPipeline.startRun` now verifies the seed round-tripped and fails loudly.

The symptom when broken was that recording a run and re-executing it usually reproduced, but not
always - six runs of one seed produced four distinct position traces. An earlier smoke test had
reproduced 249/249 twice and I took that as proof. It was luck, and it took a dedicated audit to
find.

Three causes, all now fixed:

**0.1 The base RNG generator was unseeded.** `Random.resetGenerators()` pushed a no-argument
`new java.util.Random()`, and `Dungeon.init()` called it *after* pushing the seeded stack,
discarding it. Only level generation was deterministic, because `Level.create()` wraps its build in
`Random.pushGenerator( Dungeon.seedCurDepth() )`. Everything after it - mob turns, combat rolls,
item drops - drew from an unseeded generator.

This was never a game bug: within one process the base generator is created once and consumed
sequentially, so a live playthrough is self-consistent. The game was simply never designed to be
reproducible across processes, which is what replay verification needs.

Fixed by adding `Random.reseedBase(long)`, which reseeds the base generator in place without
disturbing anything pushed on top of it, and calling it from `Dungeon.init()` with the run seed.

**0.2 An unseeded generator inside level generation.** `EntranceRoom.placeEarlyGuidePages` pushed
an unseeded generator, deliberately - its comment reads *"so meta progression doesn't affect
levelgen"*. Correct intent, but it meant the first guidebook page landed on a different tile in every
process, which alone made floor 1 irreproducible.

Seeded from the floor's own seed with a fixed offset. Still isolated from meta progression, still
its own generator, now deterministic.

**0.3 HashSet iteration order in the turn scheduler and over actors.** `Actor.all`, `Actor.chars`,
`Level.mobs` and `Level.blobs` were all hash-based collections keyed on identity hash codes, so their
iteration order differed between JVM runs. `Actor.headlessStep` broke time ties on iteration order,
so *who moved first* varied; `Level.mobs` iteration order varied wherever it fed a game decision.

`Actor.all` and `Actor.chars` are now `LinkedHashSet`, `Level.mobs` is a `LinkedHashSet` and
`Level.blobs` a `LinkedHashMap`, so all four iterate in insertion order. Membership semantics are
unchanged. `Actor.headlessStep` additionally breaks remaining ties on `Actor.id()`, which is
assigned in creation order and therefore stable.

### Remaining risk

- `Mob` and `Char` still do not override `hashCode`. Nothing found iterates them through a hash
  collection during play, but that is an invariant future changes could break. Worth a comment at
  the `Level.mobs` declaration.
- `ColorMath.random` is used for particle colours only. Harmless, because headless never creates a
  particle.
- Determinism has been verified at 400 turns on a handful of seeds. Not verified across all 26
  floors, boss levels, or the shop/alchemy paths, which are exactly where exotic code lives. A
  long-horizon soak across many seeds is the right next check, and `paritycheck -PparityArgs="--turns
  1500 --seeds 8 --heroes 4"` is already the command for it.
- **State that persists between *processes* is only as isolated as the file root.** `Bones` was found
  because a sweep let the hero die repeatedly; `Bones.clear()` plus deleting the file handles it. There
  may be other save-shaped state the game reads during level generation - a sweep across more floors and
  more heroes is what would find it, not another audit of the reset path.

---

## 1. The critical gap: nothing has learned anything

**Partly closed.** The network runs, its gradients are correct, and the PPO update now executes on
real data collected by workers: `policy=` and `value=` are non-zero, and the weights move.

**But nothing has been trained, in the sense that matters.** Every run so far was a smoke test of the
plumbing — 2 to 25 generations of a few dozen episodes, discarded. Across all of them the agent never
left floor 1, against a milestone of beating Goo on depth 5. The best score seen is ~44, which is
almost entirely tile exploration rather than progress, and several runs ended in episodes of 2 to 14
turns: the hero dying at once, which against a `deathPenalty` of 100 means a real share of the
"experience" was learning only that the immediate neighbourhood is lethal.

So the remaining gap is *not* a missing subsystem — it is a persistence and tuning one, and the
ordering matters.

`gradle :superintelligence:gradcheck` finite-difference checks the analytic gradients against
central differences on 112 sampled parameters across all seven layers, and exits non-zero on a
mismatch. It is confirmed to fail when a derivative is deliberately removed.

`train.Worker` no longer loads weights into a `Network` and then hands control to `ScriptedPolicy`. It
runs `EpisodeCollector`, which plays the episode with the real policy, computes GAE over the whole of
it, and ships a sample of the transitions. See `PLAN-data-flow.md` steps 2 and 3.

| # | Task | Size | Notes |
| --- | --- | --- | --- |
| 1.1 | ~~Call `PPO.collect()` in `Worker.runEpisode`~~ | done | Superseded. `EpisodeCollector` replaced the collection half of `PPO` entirely; see `PLAN-data-flow.md` step 2 and §5 below. |
| 1.2 | ~~Run one generation end to end and check the losses are sane~~ | done | `policy` and `value=` are non-zero, and advantages arrive with a real spread. Reported per generation. |
| 1.3 | ~~Save and load the trained weights~~ | done | `Checkpoint` writes weights, Adam moments and the optimiser step count; `--save` / `--resume` / `--checkpoint-every`. A resumed run continues both the generation and the adam-step numbering. `checkpointcheck` refuses foreign, truncated, trailing-byte and wrong-config files. `PLAN-data-flow.md` step 4b. |
| 1.7 | ~~Instrument the loop so a run can be judged~~ | done | `clip=` now reports the real ratio-clip fraction, and `MetricsHistory` writes `metrics.csv` per generation plus an end-of-run trend. `PLAN-data-flow.md` step 3b. |
| 1.4 | Train long enough to see depth move off 1 | L | The actual milestone from research.md:40. **Still blocked, and the blocker is unchanged: 1.8.** Four harness faults have been fixed and none of them was this. bestDepth is 1 in every generation of every run and all recordings are depth=1. An earlier entry here claimed depth 2 had appeared; that came from a trend line reading depth 1.0..2.0, where the 2.0 was an artefact of Graph.bar widening a flat series' axis rather than a measurement. graphcheck now gates it. |
| 1.8 | **Make surviving worth more than stalling** | M | **The next real blocker, re-scoped.** This was written when the agent stalled 100% of the time because it *chose* to. It did not: `REST` was a one-way door (`PLAN-reward-signals.md` §7), and every episode that used it was trapped by the harness. Fixed. Episodes now run the full 1500 turns with zero stalls. **The question is therefore still "what does surviving look like", but it is now a question that can be asked** — the agent genuinely survives 1500 turns and genuinely does not progress. `depthReward` is +10 against `turnCost` of 0.002, so descending pays 5,000 turns of idling, and `KILL` / `GOLD_GAIN` / `ITEM_PICKUP` still never fire. Nothing should be tuned in the same commit as the fault fixes, or §9's measurement stops meaning anything. |
| 1.5 | Verify the seed gate: 1 locked seed until Goo (depth 5), then 10, then 100, then random | M | `SeedPool` and `Trainer.advanceSchedule` are written; the gate has never had real depths to act on. Still blocked on 1.4. |
| 1.9 | ~~Five harness faults that made the agent unable to have a correct episode~~ | done | Every one found by driving paths a weak policy almost never reaches, and every one hidden behind the one before it. REST was a one-way door (36/36 episodes stalled, idle guard fired zero times); headless had no texture and every TextureFilm dereferenced the null, killing workers on hunger damage; every death blew the stack, so DEATH - the ending the reward function is built around - was unreachable; a reset did not clear the static pending cell listener, so 4 of 10 recordings diverged when verified in sequence and all 10 verified clean alone; and OPEN_INVENTORY fell through to INTERACT's cell handling, so opening the inventory also acted on a neighbouring cell - which reached training. All fixed and gated: PLAN-reward-signals.md sections 7-8 and 10, with resetcheck, graphcheck and a new modecheck case. |
| 1.6 | ~~Parallelise the update across minibatches~~ | done | --update-threads N. Measured 8.98 -> 2.73 ms/sample at 1 -> 4 threads (3.29x), 2.59 at 8 (3.47x). parallelcheck is a gate and mutation-tested. Required three fixes first, of which the dCell gradient leak was the real blocker. PLAN-data-flow.md step 4. |

**Ordering was wrong and has been corrected.** 1.6 was originally next. It is still required — the
measurement does not care what else is on the list — but two smaller items now come first:

1. **1.7, because a run could not be judged.** `PPO.update` reported `clip=` as the fraction of
   samples with `|advantage| > clipEpsilon` on a *normalised* advantage, which is
   `P(|N(0,1)| > 0.2)` = **0.8415** — a constant. Observed 0.81/0.83/0.85/0.90 across real
   generations: that constant. `Policy.accumulatePolicyGradient` computed the real thing
   (`clipBinding`) and threw it away. Separately, `Trainer` kept only `lastEpisodes` — one generation —
   and discarded it; `diag.Graph` was dead code. **Now done:** the corrected metric ranges 0.047–0.737
   over 9 generations and `metrics.csv` accumulates per generation.
2. **1.3, because it is smaller than 1.6, on the critical path to 1.4, and is what makes two runs
   comparable at all.** Also now done.

**1.6 is finished too, so all four of the data-flow plan's steps are done.** What remains is 1.4 -
actually training something - and 1.5.

**1.6 was not the threading work it was scoped as.** It needed three correctness fixes first, and
the one that mattered was a bug nobody had looked for: Network.backward cleared dPrev but not
dCell, and LSTM.backward reads the incoming dCPrev before overwriting it. Every sample's
gradient was therefore a function of whichever sample preceded it - measured on conv.gb[15], four
samples in sequence gave 0.0892, 0.2075, 0.3372, 0.4235 where the same four individually gave
0.0892, 0.0419, -0.1487, 0.0517. Not additive.

That was invisible for the whole of the previous implementation because the buffer is shuffled, so a
leaked gradient arrived attached to an unrelated sample. Two bugs cancelling is the most expensive
kind to find, and the only reliable detector is one that varies a single thing at a time.

**What checkpointing turned out to need, beyond writing `Network.layers()`.** The plan called it
"plumbing, not design" and that was right about the format but wrong about the contents. Weights are
the obvious part and the easy part; two things that are *not* weights decide whether a resume is the
same run or a worse-looking one:

- **Adam's moments.** `Network.layers()` does not carry them, and a checkpoint without them restarts
  the optimiser's averages from zero. The run still trains. It just trains worse, with nothing in the
  metrics to say why — the same failure class as the `clip=` constant, one level up. `Network.moments()`
  and `loadMoments` now expose them. They are *not* sent to workers: a worker runs forward passes
  only, so pushing four times the floats per generation would cost ~57 MB per worker for nothing.
- **The optimiser step count.** Adam's bias correction divides by `1 - beta^step`, so restarting at 1
  makes that correction ~0.1 instead of ~1 — a full-size step on a barely-warmed average. Cheap to get
  wrong because nothing crashes: `Network.adamSteps(int)` restores it.

Two more things the format needed that were not obvious in advance:

- **Atomic writes.** A checkpoint half-written by a power cut is *newer* than the last good one, so it is
  exactly the file a resume would pick up. Writing to a sibling temp file and renaming means the
  file at the target path is always a complete previous checkpoint or a complete new one.
- **Refusing, loudly, in four cases.** A foreign file, a truncated one, one with trailing bytes, and one
  from a different `EnvConfig`. The last names the field — "trained with gridWidth=32, this run has
  48" tells you which flag to change, where "incompatible" tells you nothing.

Verified: a 6-generation run then a resumed 2-generation run continues the numbering 0-7 with no CSV
row collisions, and the resumed checkpoint's header reports generation 6 / adam step 32 as expected.
`checkpointcheck` (8 cases, now a gate) is mutation-tested — dropping the moments, the trailing-byte
check, or the config check each fail it. `weightsdiff <file>` is the complement: it reports how far a
checkpoint is from a freshly initialised policy, which the round-trip test cannot answer.

**`PPO.collect()` is gone, and so is the cap it implemented.** `rolloutCap` (default 2048) bounded
collection at ~98 MB because a step is ~49 KB and `turnLimitTotal` is 40000 - uncapped, one long
episode is ~1.9 GB against a 1536m worker heap. Its replacement is two caps with different units:
`--max-sampled-per-episode` bounds one worker's retained *observations*, and
`--max-samples-per-generation` bounds the trainer's update buffer across all workers. The observation
memory is now bounded by the sample rate rather than by episode length, because GAE runs on scalars
and never needs an unsampled step's observation.

**Worker-to-trainer data flow is resolved.** Chosen and built: the worker computes GAE and ships a
sample of the transitions; the trainer pools them and updates once. Reasoning, measurements and the
three bugs it surfaced are in `PLAN-data-flow.md`. Three consequences worth keeping in view:

- **The update, not the transport, is the bottleneck.** `gradle :superintelligence:updatecost`
  measures 11.3 ms per sample, which at a 5% sample rate and 4 epochs is ~107 s of trainer CPU per
  generation against ~0.12 s of transport. Every option in `WORKER-DATA-FLOW.md` was argued on
  bandwidth, which is not what binds here.
- **Episodes are much shorter than the arithmetic assumes.** An untrained policy produces 7-90 turn
  episodes, not ~150, so a 20-step tail is most of the episode and a requested 5% arrives as near
  100%. Expected to correct as the policy learns to survive; the sampled count in the report is the
  honest figure until then.
- **The update has been running at ~6× the cost of collecting the data**, visible as `barrier 606%`
  in a real run. Before this work the barrier was 12-26% of wall; now the trainer's single thread is
  the critical path and the other 19 logical cores idle through it. This is what makes 1.6 necessary
  rather than merely desirable.

**Headless coverage was the next likely source of crashes, and it was.** Running the real policy
surfaced three that the scripted one never touched: blobs had no emitter (15 blob types NPE in
`evolve()`), `GameScene.cancel()` dereferenced null `cellSelector`, and `GameScene.spellSprite()`
read `scene.spells` unguarded. Then the data-flow work surfaced **two more**, both fatal rather than
cosmetic:

- **The libGDX natives were never loaded in any headless JVM.** Desktop gets them implicitly from
  `Lwjgl3NativesLoader`; the headless backend replaces that, and a natives jar on the classpath does
  not load itself. Reached from `TextureCache.createSolid` / `createGradient` / `create`, which are
  *not* guarded on `Gdx.gl == null` the way `getBitmap` is - from any `Flare` or `ColorBlock`. Fixed by
  `GdxNativesLoader.load()` in `HeadlessServices.install()`.
- **`BitmapText` threw on every measuring call with a null font.** `HeadlessPlatform`
  `getGeneratorForString` returns null by design, so `PlatformSupport.getFont` returns null at its
  early-out. Its no-argument constructor already built a null-font instance, so nothing in the class
  guarded it. Reached from `Bag.execute` -> `WndQuickBag` -> `InventorySlot`.

Both are the same class of bug as the original three: a hand-written policy simply never reached the
code. Expect more. The useful observation is that each was a *deliberate headless substitution*
rather than a game bug - a null GL, a null font generator, a null scene - that something downstream
did not expect.

**Unverified: hero class appears not to affect the score.** Every hero class produced an identical
score and turn count on the same seed. `GamesInProgress.selectedClass` is applied in
`Dungeon.init`, so the class does reach the hero, and the score is probably driven by turns and
depth rather than anything class-specific. Not confirmed either way.

research.md:40 sets the first real milestone: *"Train the AI on a single seed until it can
consistently beat the first boss (Goo)."* The agent has never left floor 1, and because weights are
never persisted (1.3) no attempt has been resumable either. Nothing has been beaten.

**Seed text is capped at 20 characters.** `GameSettings.getString(key, def, maxLength)` discards
an over-long stored value and `SPDSettings.customSeed()` reads with a 20 character cap, so a
longer seed is silently dropped and the run falls back to a random one. `LevelPipeline.startRun`
now verifies the seed survived the round trip and fails with an explanation. Keep generated seed
text inside the cap.

---

## 2. Reward signals

Two different problems, and they were conflated until the first 20-generation
training run on the corrected update made them separable.

### 2.1 The agent learned to stall — **RESOLVED**

`meanScore` converged on exactly **-5.0**, which is `STALLED`'s terminal reward.
`meanTurns` collapsed 62 → 8. **The agent found a way to end an episode
deliberately, and ending it that way is twenty times cheaper than dying.**

The path is `WAIT` × 120 → `STALLED` → -5.0, against `deathPenalty` of -100 and
a `TURN_LIMIT` cost of -80. Every loss metric looked healthy throughout, which is
what made it worth writing down: the update was working and the objective was
wrong.

Three compounding defects, all fixed, with the analysis and reasoning in
[`PLAN-reward-signals.md`](PLAN-reward-signals.md):

| # | Defect | Fix |
| --- | --- | --- |
| 2.1.1 | `WAIT`, `REST` and `SEARCH` returned `false` from `ActionMapper.apply`, so `SPDEnv.step` classified valid actions as `INVALID_ACTION` | They return `true` |
| 2.1.2 | `STALLED` cost -5.0 — 20× cheaper than death, making a timeout guard the cheapest way out | Zero. Idling is priced by `TURN_COST`, which is what turn cost is for |
| 2.1.3 | `SPDEnv.settle` called `terminate(STALLED)` **twice**, charging -10.0 on that path | One call |
| 2.1.4 | `STALLED` was penalised *and* bootstrapped by GAE (`truncated = true`), so the critic never settled on it | Zero, and it stays truncated — the episode was cut off, not lost |

`rewardcheck` is a gate asserting the property that was violated: **`WAIT` ×
`stallLimit` must not come out cheaper than dying**, replayed through the real
environment rather than checked as constants. Mutation-tested.

**Measured over the same 20 generations at 8 workers:**

| | before | after |
| --- | --- | --- |
| meanScore, gen 19 | -4.46 | **+3.29** |
| meanScore, converged to | exactly -5.00 | no constant |
| meanTurns, gen 19 | 11 | **56** |
| valueLoss, gen 19 | 3.53 | **0.44** |
| stall share | 100% | 100% — but free, not profitable |

The last row is the honest one. **The agent still stalls every episode; it just
no longer profits from doing so.** `valueLoss` falling 3.53 → 0.44 is the critic
finally fitting its targets, which is what removing a penalty it could not
reconcile with a bootstrap should do. Depth is still 1. That is now 1.8.

> **Both columns of that table were correct and the conclusion drawn from them was
> wrong.** The agent was not choosing to stall. `REST` was a one-way door: it set
> `hero.resting` without setting a `curAction`, so `Hero.act` took the rest branch
> forever and never called `ready()`, and the recovery path of the time refused a
> resting hero because a *player* escapes rest by choosing another action. Every episode
> that rested was over. Instrumenting the two stall guards showed 36 of 36 stalls
> came from `LevelPipeline` and **zero** from the idle guard this section is about,
> at turns 10-51 rather than 121 — which is the whole tell.
>
> So 2.1's reward fix was correct on its own terms and none of the above measures
> the stall. `PLAN-reward-signals.md` §7 has the correction; §8 has two further
> faults the stall was hiding. Worth keeping in mind that every case in this
> section passed while the agent was trapped by something else — a check on
> reward arithmetic cannot detect a bug in the mechanism that builds the episode.

### 2.2 Reward terms that never fire

Declared in `reward.RewardTerm`, but with no emission site. Each is a documented requirement.

| Term | Document | Status |
| --- | --- | --- |
| `CRAFTED_MEAT_PIE` | docs.md:24, research.md:11+35 (called out specifically) | dead |
| `CRAFTED` | research.md:11 | dead |
| `CURSE_REMOVED` | docs.md:23 | dead |
| `EQUIP_TOO_STRONG` | docs.md:31, research.md:12 | dead |
| `KILL` | - | **not dead — tracked, but does not fire** |
| `MENU_NOOP` | research.md:57 (shop-loop guard) | dead |

**`KILL` is a special case and the row above is now wrong.** `RewardModel` already tracks `prevKills`
(it snapshots `Statistics.enemiesSlain`) but has no emission site for it, and
`RewardLedger.countKill()` is written and never called. So the plumbing is half-present rather than
absent — unlike `CRAFTED_MEAT_PIE` and friends, which have no observation point at all.

**Deliberately not fixed yet.** The emission site is small, but wiring it now would change the reward
function in the same run that just had its terminal reward corrected, and the result would be
uninterpretable. It waits for 1.4 — a run that can be judged.

Also relevant, and easy to misread: **four scripted 600-turn rollouts (`RS-1`..`RS-4`) produced only
`TURN_COST` and `TURN_LIMIT`**, scoring -1.25 to -1.40. But RL-policy training episodes have scored up
to **+44**, so positive terms *do* fire for the learned policy — the scripted heuristic simply does not
explore or loot. The reward model is not dead; the scripted policy is a poor proxy for it.

**Dead configuration.** `EnvConfig.allowAlchemy` and `EnvConfig.allowTrading` are read by nobody.
Alchemy and shops are reachable only because `Hero.handle` happens to resolve them.

These are all casualties of the state-diffing decision (see Deviations, D2). A diff cannot see
"an item was created in the alchemist", because the alchemy pot is a terrain tile and the result is
one item delta indistinguishable from a pickup. Fixing them needs real observation points in the
craft, equip and scroll/potion paths - i.e. the hooks research.md:8 actually asked for.

**Both are now externally configurable, which is not the same as fixed.** `env.allow_alchemy` and
`env.allow_trading` can be set in `superintelligence.properties` or through `SPD_ENV_ALLOW_ALCHEMY`,
and `configcheck` proves the values reach the settings they name - but nothing reads those settings, so
turning them changes nothing observable. That is deliberate: the key is visible in one documented place
rather than inferred from behaviour, and the dead wiring is a row in a table rather than a gap in the
source.

---

## 3. Missing subsystems

| # | Task | Document | Size |
| --- | --- | --- | --- |
| 3.1 | libGDX trainer UI in the `desktop` module | research.md:16, docs.md:10 | L |
| 3.3 | Live play with real-time reward/term monitoring | docs.md:10 | M |
| 3.4 | "type totals/subtotals" in the dashboard | docs.md:41 | S |

**3.2 is DONE.** Desktop replay viewer built: `gradle :desktop:replay --args="--file <replay>"`.
Recorded actions go through `ActionMapper` to `Hero.handle`, the same call the cell selector makes,
so action resolution is identical to a human playing. HUD shows step, seed, depth, actions, engine
time, live score with its per-step delta and gained/lost split, and divergence. Needed two engine
hooks: `Game.lockCellInput` and `Game.setSceneClass`. Enters via `InterlevelScene` because its
transition to `GameScene` is hardcoded, so a subclass would never be entered. See
`PLAN-replay-viewer.md`.

**And it now plays the corpus back faithfully**: `gradle :desktop:viewcheck` is green, 17 of 17, stable
across repeated runs. That took finding the fault below, which was not a viewer fault at all.

`diag/RunReport` already emits a per-term table and per-floor totals. "Subtotals" (a grouped
category rollup, eg. all combat terms together) is not implemented.

---

## 4. research.md sections not addressed

| # | Section | Status |
| --- | --- | --- |
| 4.1 | Garbage collection and memory pooling (research.md:60) | **Unaddressed.** |
| 4.2 | 1,000 simultaneous simulations (research.md:16, docs.md:42) | Never run at scale. |
| 4.3 | IMPALA as the alternative learner (research.md:26) | Not built. Listed as an optional alternative; decide whether it is still wanted now that the PPO path exists. |

4.1 in detail: the documents ask for an audit of the core game loop and pooling of volatile classes
(`FloatingText`, `Speck`). Zero references to either exist. I do pool `Transition` and reuse all
observation buffers in my own code, which measurably reduced allocation, but the game loop itself is
unaudited. This is the most likely thing to fail at high worker counts - see 4.2.

4.2 in detail: `Trainer` spawns worker JVMs over a binary stdin/stdout protocol. The path is now exercised at
two workers for two generations - handshake, params push, episode frames, weight push, replay write -
after the trainer was split into `Trainer` / `WorkerPool` / `Protocol` / `TrainOptions` / `Episode`.
What remains untested is `WorkerPool`'s parallel weight push under a real 20-worker pool, and whether
the per-worker buffers behave at that count.

The dead `byte[] payload = new byte[0]` initialisation in `pushWeights` is gone; it was replaced
earlier.

---

## 5. Known rough edges

Small things that are wrong but not blocking.

- **The reward curriculum is inert, and it is a design question rather than a bug.** `Curriculum` is
  fully written (fade from depth 3 to depth 10, `shapingScale()`) and `SPDEnv:325` applies it correctly
  to the ledger — but nothing ever calls `Curriculum.observe()`, so `deepestSeen` stays 0 and the scale
  stays 1f forever. On the trainer side `Trainer.curriculumScale()` returns a hardcoded `1f`, so the
  console prints a constant under the label `shaping=`.

  **Deliberately not "fixed" yet.** Wiring `observe()` is three lines and would make the label honest,
  but it would also start fading dense rewards by depth 3 — i.e. change the reward function — with no
  evidence that the dense terms help at all. The agent has never left floor 1, so the fade has never
  had a regime to act on. **Decide after 1.7**, when a run can be judged, and 1.4, when there are
  depths to fade across. Until then a wrong fade is worse than no fade, because it silently distorts the
  first real runs.
- `PPO.approximateKL` takes `t`, `logits` and `mask` parameters it does not use.
- Some engine states end a rollout as `STALLED` early. Seed `HERO` terminates after 2 turns, where
  most seeds run the full budget - an encounter reaching a state the action space cannot answer.
  Coverage is uneven across seeds.
- `TargetHealthIndicator`, `AttackIndicator`, `QuickSlotButton` and `GameScene` gained null guards
  for headless operation. Each is correct with a renderer present, but they are now load-bearing for
  a code path most contributors will never run.
- **`PPO.collect()` and `PPO.rollout()` are gone,** along with `rolloutCap`, `episodeInProgress` and
  `recurrentStateStale`. They had no callers once workers collected their own episodes, and leaving
  them would have meant two step loops to keep in agreement. `PPO` is the learner now, at 325 lines.
- **The `network` field on `Worker` is now only a parameter sink** — it holds the weights the collector
  uses, and nothing else reads it.
- `Worker`'s `network` field could be dropped and `EpisodeCollector` given the weights directly; it
  survives only because `readWeights` needs a `Network` to load into.
- **`GaeCheck` does not drive `EpisodeCollector`.** Its sampling checks restate the collector's two
  decisions - a uniform draw during collection, a rolling tail at the end - rather than exercising
  the loop that applies them, because that needs a live environment and a whole episode per case. A
  mutation inside the collector's retention logic would pass. Verified instead by observation: a
  multi-worker run reports a sampled count consistent with the tail dominating short episodes. Stated
  in the class comment rather than left implied.

---

## 6. Deliberate deviations

These were choices, not oversights. Each has a reason and a cost. Revisit any of them if the reason
stops holding.

### D1 - Single convolution layer, not a convolutional stack

**Was:** research.md:30 implies a CNN over the grid, plural channels.
**Now:** one 4x4/stride-2 convolution over all 21 planes, then fully connected.

*Reason:* dungeon rooms are not the same shape, so translation invariance across repeated motifs is
worth less here than the relationship between hero, walls and stairs. One layer gives local spatial
features at a fraction of the parameters.
*Cost:* less spatial generalisation. A real stack may learn "gap in wall" better.

### D2 - State diffing instead of hooks in the item and buff trees

**Was:** research.md:8 - *"placing hooks directly into the game's core logic packages"*, with
`Belongings.java`, `ScrollOfIdentify.java`, `Potion.java`, `Hunger.java` named.
**Now:** `RewardModel` diffs observable state between turns.

*Reason:* no edits to hundreds of item classes, and it measures what an action *achieved* rather
than which code path it took - a potion healing through a wand and a scroll healing through an
upgrade both read as "hero gained health".
*Cost:* **this is why every crafting, curse-removal and equip-rejection term is dead.** The diff
cannot distinguish alchemy from a floor pickup. If those rewards matter, D2 must be partly reverted
- see section 2.

### D3 - Truncated BPTT in the PPO update

**Was:** research.md:31 - an LSTM "strictly necessary" for memory.
**Now:** the LSTM is present and trained, but gradients are truncated at length one.

*Reason:* an exact through-time gradient needs every timestep's pre-activation state retained until
the update. At that sequence length this is gigabytes per worker.
*Cost:* the trunk, heads and critic get exact gradients; the LSTM gets exact gradients within a step
and none across steps. Whether this is enough to actually remember a dropped potion three rooms back
is an open empirical question, and it is the single assumption most likely to make the memory layer
fail.

### D4 - Worker processes, not threads

**Was:** docs.md:42 and research.md:16 imply 1,000 concurrent simulations.
**Now:** one run per JVM, pooled by `Trainer`.

*Reason:* not a preference. The game's simulation state is almost entirely static - `Dungeon.hero`,
`Dungeon.level`, `Actor.now`, `Level.visited`, the whole actor registry. Two environments cannot
coexist in one process.
*Cost:* ~1.5 GB and a JVM per worker. 1,000 workers is not practical on one machine; that needs
multiple hosts and a scheduler change.

### D5 - Console dashboard, not a libGDX UI

**Was:** research.md:16 and docs.md:10/41 both ask for a trainer interface.
**Now:** ANSI in a terminal, honouring `NO_COLOR` and non-tty output.

*Reason:* it works everywhere including CI logs, and it was buildable before the engine was.
*Cost:* the graphical trainer is not delivered - see 3.1.

### D6 - `verify`, not `replay`, for re-executing a recording

`ReplayIO.play()` was renamed `ReplayIO.verify()`, and the `replay` subcommand and Gradle task are
now `verify`. Nothing is drawn and nobody watches; the operation exists to catch the moment a
recorded path stops reproducing.

*Reason:* `play` implies a human watching the game, which is 3.2 and does not exist.
*Note:* the `Replay` *format* keeps its name. A recorded run that can be re-executed is a replay by
any normal definition, and the file magic is `SPD-REPLAY`.

### D8 - Four engine fixes proposed, then committed separately

**Was:** `instructions.md` §12.3, which keeps engine logic changes out of a commit unless proposed.
**Now:** applied to the viewer-fidelity fix rather than argued about in the abstract, and then resolved.
Four presentation draws moved from `Random` to `PRandom` — `CharSprite.link`, `AttackIndicator`,
`Wand.staffFx`, `MagesStaff`'s staff particle — one line each, none able to change an outcome. Plus
`Random.Int` recording its draws and `Random.shuffle(List)` routed through it, without which the trace
that was supposed to catch this was blind to the draws that caused it.

*Reason:* §12.3 exists precisely for this. The evidence went into `ISSUE-viewer-frame-drift.md` and
`FINDINGS-viewer-fidelity.md`, the proposal record into `ENGINE-CHANGES.md`, and the fixes were then
committed in their own commit once sanctioned.
*Cost, accepted deliberately:* the tree sat green on `verifyall` and red on `viewcheck` — 14 of 17 —
for the length of that proposal. A gate reporting a real fault is the correct behaviour; the delay was
the price of §12.3, and it is recorded here so the next reader knows the red state was chosen.

### D7 - Targeting and inventory as separate heads

**Was:** research.md:52 poses this as an open question - target every turn, or pause?
**Now:** the environment pauses (`EnvMode.TARGETING`, `SLOT`).

*Reason:* predicting a target on the ~95% of turns that are not aiming spends capacity and invites
spurious correlations. Keeping slot out of the flat action enum means output width does not change
with the hero's carrying capacity.
*Cost:* a step is not always one game turn, which complicates any tooling that assumes it is.

### D8 - Aim targets limited to the eight neighbours

*Reason:* shots resolve against a `Ballistica` collision cell, so a shot at range has to start from
one of these directions anyway.
*Cost:* the agent cannot express "throw at that specific far cell" without a two-step path.

---

## 7. Stale class names in the source documents

Worth knowing before either document is treated as a specification. They reference classes that do
not exist in this v4.0.1 checkout:

| Document | Referenced | Reality |
| --- | --- | --- |
| research.md:5 | `FogOfWar.java` (in `tiles`) | Exists, in the right package: `tiles/FogOfWar.java`. Reached through `Level`, not called directly. `Level.heroFOV` is what I read. |
| research.md:5 | `ShadowCaster.java` (in `mechanics`) | Exists, in the right package, but is reached through `Level`, not called directly. |
| research.md:5 | `InputHandler.java`, `ControllerHandler.java` (in core) | Not in core. They live in `com.watabou.input` in `SPD-classes`. Bypassed either way. |
| research.md:55 | `Alchemy.java` | Exists, as the blob at `actors/blobs/Alchemy.java` — not a scene. The pot is `Terrain.ALCHEMY`; the UI is `scenes/AlchemyScene`. Both readings of the reference are reachable, so this one needed no correction. |
| research.md:5 | `tiles` package | Exists. Eleven files, including `FogOfWar.java` and the tilemap classes. |

An earlier version of this table reported `FogOfWar.java`, `Alchemy.java` and the `tiles` package as
absent. All three exist; what is true is narrower — they are reached through `Level` and the scene
layer rather than called directly, which is why the literal instruction still did not apply.

The *intent* of every reference is achievable. The literal instruction is not always expressible.