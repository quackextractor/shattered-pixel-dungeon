# Open issues

Closed entries live in `CHANGELOG.md` with their measurements. Rejected and deferred proposals live in
`REJECTED.md`. What follows is what is still open.

| # | |
| --- | --- |
| 20 | under `depth_only` the run converges on dying early rather than on descending |

**26 and 37 are closed.** 26: the headless stand-in for a particle emitter was compensating on the
**wrong stream**, so the trainer ran one value ahead of the rendered game from the first scroll read
and one snake attack rolled differently; `:desktop:viewcheck` is **20 of 20**. 37: a hero's death
bookkeeping was compared as if it were gameplay - `Bones.leave` draws per item for a random-seed
episode and not for a custom-seed replay of the same episode - and the worker-side ordered draw log
names every one of them. Measurements, mutations and the worker wiring are in `CHANGELOG.md`,
`out/evidence/issue-26/ordered-log-named.txt` and `out/evidence/issue-37/named.txt`.

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