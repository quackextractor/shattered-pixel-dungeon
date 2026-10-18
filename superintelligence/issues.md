# Open issues

Closed entries live in `CHANGELOG.md` with their measurements. Rejected and deferred proposals live in
`REJECTED.md`. What follows is what is still open.

| # | |
| --- | --- |
| 20 | under `depth_only` the run converges on dying early rather than on descending |
| 37 | one recording in two does not reproduce, and it is the trainer's half |

**26 is closed.** One snake attack landed on both sides at `turn=91.0` with the hero at 7 HP and
the number arriving at `Char.damage` was 0 against 1. `RandomTrace.siteLog()`, written by both
environments, names the first draw at which the two part: the headless stand-in for a particle
emitter was compensating on the **wrong stream**, spending a gameplay value the real `Emitter`
spends on `PRandom`, so the trainer ran one value ahead of the game from the first scroll read.
`:desktop:viewcheck` is **20 of 20**. Measurements and the mutation test are in `CHANGELOG.md` and
`out/evidence/issue-26/ordered-log-named.txt`.

## 20. Under `depth_only` the run converges on dying early rather than on descending - **OPEN, and it is a finding rather than a defect**
Measured on the same 30-generation run (30 generations, 16 workers x 4 episodes, `reward.profile=depth_only`,
| generation | meanScore | meanDepth | bestDepth | meanTurns | ended |
| --- | --- | --- | --- | --- | --- |
| 0 | -7616.8 | 1.00 | 1 | 795 | death 58, turn limit 6 |
| 6 | -3726.3 | 1.02 | 2 | 231 | death 64 |
| 11 | -3735.1 | 1.02 | 2 | 276 | death 64 |
| 20 | -3471.4 | 1.00 | 1 | 209 | death 64 |
| 29 | -4156.2 | 1.00 | 1 | 363 | death 64 |
**`bestDepth 2` in 5 of 31 generations, all of them before generation 12, and 100% of episodes dead from
**Re-measured 2026-10-18, after the guide-page fix, and the conclusion is unchanged.** 30 generations on the re-measurement's exact configuration: DEATH 56% in both windows, STALLED 6% in both, so **a third of every generation now ends on the turn limit**, meanTurns 542.79 -> 758.65, and depth>=2 **0 of 960**. The policy is learning to live longer rather than to descend, which is this entry's arithmetic arriving from the other side. CHANGELOG.md, evidence out/evidence/descend-30.csv.

**Why, and it is arithmetic rather than a mystery.** With nothing but the objective paying, the only
**So `depth_only` is a diagnostic profile and not a training default.** It answers the question the
**The next measurement is a curriculum rather than a switch**, and the machinery for it already exists and


## 37. One recording in two does not reproduce, and it is the trainer's half - **OPEN, re-measured after the fix**

Found by running the 30-generation measurement `TODO.md` 1 asks for, and reading what it wrote rather
than what it printed. `issues.md` 15 and 26 both record a recording that will not replay; this one is
neither of those, and the distinction is the reason it is filed separately.

### What it is

A 30-generation run, 8 workers, 4 update threads, seed 20261016, `train.random_seed_rate=0`, wrote two
recordings - best per seed and the run's worst, which is what `ReplayKeeper` keeps. Same generation,
same seed, same hero class, same header:

```
trainer-replays/noise-floor/LZJ-LNO-HLE.dat      generation 29  depth 2  598 turns  599 steps
trainer-replays/noise-floor/LZJ-LNO-HLE-r2.dat   generation 29  depth 1 1184 turns 1186 steps
```

The longer one reproduces exactly:

```
verify: seed=LZJ-LNO-HLE hero=WARRIOR steps=1186 recorded score=-5402.804771922529
[OK]     replay reproduced in 1186 steps, 556 ms
score    recorded -5402.804771922529, reproduced -5402.804771922529
```

The shorter one does not, and it does not in the same place every time:

```
verify: seed=LZJ-LNO-HLE hero=WARRIOR steps=599 recorded score=-1649.690040986985
[ERROR] replay diverged at step 534 of 599
        turn: replayed 13.0, recorded 12.0
```

Run again, identical output. **Deterministic, not a flaky seed** - which is the one thing this rules
out.

### Why it is not `issues.md` 26 and not `issues.md` 15

- Not 15. That was four of nine step fields dropped by the wire, recognised by the four-field
  signature. These recordings carry health, engine time and inventory on every step.
- Not 26. That is the *rendered viewer* spending 1 HP the recording does not contain, and it needs a
  display to see. This is `verify` - headless, in one process - refusing a recording that a **worker
  in another process** wrote. Nothing about the renderer is involved, so the whole viewer half of 26
  is irrelevant to it.

### What it looks like

Only `turn` differs, by one, at step 534 of 599. Position and health agree at that step, and agree at
every step before it - `verify` compares position first and would have reported a position
divergence had one occurred.

```
 528  MOVE_SW 1 WORLD 1044  1.04  11  11.0  Longsword:1,Seed:1
 529  MOVE_NE 1 WORLD  993 -2.96  11  12.0  Longsword:1,Seed:1
 ...
 533  MOVE_SW 1 WORLD 1044  1.04  11  11.0  Longsword:1,Seed:1
 534  MOVE_S  0 WORLD 1094 -0.96  11  12.0  Longsword:1,Seed:1
 535  MOVE_E  0 WORLD 1095 -2.94  11  13.0  Longsword:1,Seed:1
```

The clock reads 11.0 at both 528 and 533 and 12.0 at 534, so the step at 534 cost one turn in the
recording and two in the replay, and the two stayed one apart for the remaining 65 steps without the
positions ever separating. The floor is 50 wide - `MOVE_SE` at 531 is `994 -> 1045`, which is
`+50 +1` - so nothing crossed a floor boundary and the clock was never restarted under it.

### Re-measured on a 30-generation run, and it did not recur - **still open**

The re-measurement `TODO.md` 1 asked for wrote two recordings and **both verify exactly**:

```
[OK]  replay reproduced in 207 steps, 406 ms   score recorded -3094.229994300753, reproduced -3094.229994300753
[OK]  replay reproduced in 1283 steps, 442 ms  score recorded -5518.715346027166, reproduced -5518.715346027166
```

**That is one run, and it does not close this entry.** `issues.md` 18 put the same fault at 1 in 8
runs days earlier, so two clean recordings out of two is the expected outcome even if the rate is
unchanged. What has not changed is that nothing diagnoses it: the measurement this entry asks for -
`rngtrace` with `-Dspd.rngTrace` over the failing recording, whose tally names the call site - has
still not been taken. `issues.md` 38 has since produced a cheaper reproduction of a neighbouring
fault, and it has closed: the cause was a guidebook page, and `Document`'s page states are now cleared on
every reset.

### What is not established

- **Which action.** It is a move on step 534, but it is not the first move of its kind in the
  recording, so "a move costs two turns sometimes" is not yet a description of anything.
- **Which process is right.** The recording was made by a worker; the replay ran in the verifier's
  process. Both are headless and share every line of the environment, so "one of them is wrong" does
  not yet have an answer.
- **Whether it is the real policy's doing.** `paritycheck`, `collectcheck` and `rolloutcheck` all
  drive `ScriptedPolicy`, and all three are green. This recording was made by the trained policy,
  which walks into things the scripted one does not. That is a difference in *coverage*, not
  evidence about the cause.

Recorded rather than guessed at, for the reason `issues.md` 18 gives: a hypothesis here would be a
story about a turn cost, and `issues.md` 33's own measurement - 189 searches taking half a
940-step run's engine time - is the shape of the last confident answer to that question.

### Reproduced again, and now localised - **a fresh 2-generation run, after the draw count landed**

A two-worker, two-episode, two-generation run (`--max-turns 40 --update-threads 1
--replay-group smoke-rl`, `train.record_every=1`, the default `random_seed_rate`) wrote two
recordings. One reproduces, twice, identically:

```
ZVI-KMW-HKC.dat   [OK]  replay reproduced in 634 steps, 470 ms   (and again, 477 ms)
```

The other does not, and it does not in the same place every time it is run:

```
NRF-TFY-RXG.dat   [ERROR] replay diverged at step 1421 of 1422
                 rng baseDraws diverged at step 1421: replayed 745, recorded 747 (delta -2)
                 action SEARCH slot 0 mode WORLD
```

Both runs of it give the same step, the same delta and the same action, so this is deterministic —
the one thing the original entry already ruled out, re-confirmed on a different seed and a
different network shape.

**What the new field adds.** Before it, this recording reported a position, health or clock
divergence and nothing about the stream. Now it says that at the last step the *replay consumed two
fewer draws than the recording did*, on a `SEARCH`. That is three things the entry above could not
state:

- the divergence is in the **generator**, not only in the accounting for it;
- its **direction** — the recording drew **more**, so the worker is the side that made the extra
  draws rather than the replay;
- and it is reached by a **`SEARCH`**, which `issues.md` 33 has already measured as the most
  engine-time-expensive action in the game.

**What it does not do is say which call site.** `baseDraws` is cumulative. `rngtrace` with
`-Dspd.rngTrace` records the draws-by-site tally over the same window, and that is the next
measurement; it is not taken here because this entry has already recorded once what happens when a
turn-cost story is asserted ahead of the measurement.

Evidence: `out/evidence/issue-37-smoke/`, holding both recordings and both `verify` outputs.

Evidence: `out/evidence/gen29-divergence.txt`, `out/evidence/gen29-LZJ-LNO-HLE.dat`,
`out/evidence/gen29-LZJ-LNO-HLE-r2.dat`, `out/evidence/noise-floor-30.csv`,
`out/evidence/train-noise-floor-30.log`.

### Re-measured after the guide-page fix - **did not recur in its old form, and reproduced in its own**

`issues.md` 38 was closed by `Document.resetPages()`, and that fix was a per-process state leak of
exactly this family, so the obvious prediction is that this entry closes with it. **It does not.**

A two-generation run, 2 workers x 2 episodes, `--record-every 1`, wrote three recordings and **two
of the three verify exactly**:

```
ZVI-KMW-HKC-r2.dat  OK
ZVI-KMW-HKC-r3.dat  OK
LBS-VPS-LZG.dat     [ERROR] replay diverged at step 1124 of 1125
                    rng baseDraws diverged at step 1124: replayed 637, recorded 639 (delta -2)
```

**That is this entry's signature exactly** - a negative delta at the last step of the recording, so
the recording drew more - on a different seed and a different network shape. The historical
`NRF-TFY-RXG.dat` still fails identically, which says only that it was written before the fix.

**What the fix did narrow, and it is a real narrowing.**

- Two verifications of one file **agree exactly**, so the verification perturbs nothing.
- `paritycheck` runs **32 recordings across 32 episode boundaries in one process** and is green, so
  an episode-to-episode leak that the **scripted** policy reaches is excluded. The worker's policy is
  the trained network, and `issues.md` 37 already recorded that this is a difference in *coverage*
  rather than evidence about the cause.
- The difference therefore lives in the process that **made** the recording, and no instrument in
  this repository reads across a process boundary yet.

**The next measurement is wiring rather than new instrumentation**, and it is the one instrument
that has never existed here: attribute inside the **worker**, dump `RandomTrace.siteLog()` and
`generatorLog()` per episode, and diff them against a verification of the same file. The ordered log
is what makes it possible - a per-site tally said two processes were level at every site while their
order differed, and that is exactly how this entry survived three entry points.

Evidence: `out/evidence/issue-37-recheck.txt`.