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
**Re-measured twice more, 2026-10-18, and the conclusion is unchanged.** The shipped configuration
is **flat**: `meanScore` **+2.39 +/- 44.6** over 30 generations, so the `-182.32 +/- 56.4` recorded
above did not reproduce either, and `depth>=2` is **0 of 960**. `reward.stair_potential_weight` at
10.0 - five times the shipped weight - is **also 0 of 960**, with the objective window **5 sigma
worse**. So the depth-only arithmetic is not the whole story and the reward is not the lever; the
term pays for progress towards stairs the observation does not carry. CHANGELOG.md and
REJECTED.md, evidence out/evidence/descend-30-ab.txt.

**Why, and it is arithmetic rather than a mystery.** With nothing but the objective paying, the only
**So `depth_only` is a diagnostic profile and not a training default.** It answers the question the
**The next measurement is a curriculum rather than a switch**, and the machinery for it already exists and