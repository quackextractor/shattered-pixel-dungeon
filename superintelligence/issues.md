# Open issues

Closed entries live in `CHANGELOG.md` with their measurements. Rejected and deferred proposals live in
`REJECTED.md`. What follows is what is still open.

| # | |
| --- | --- |
| 20 | under `depth_only` the run converges on dying early rather than on descending |

**20's numbers predate 0.11.0 and must be re-measured before they are read.** Every run below trained
with the policy gradient's sign inverted, tanh-bounded logits, a clip that stopped both sides, no
terminal flag on death, and a stair potential pointing at the upstairs, so "the policy learned to die
cheaply" is a statement about a learner that moved away from whatever it was paid for. `CHANGELOG.md`
0.11.0 has the list; `TODO.md` section 1 carries the re-measurement.

**26 and 37 are closed.** 26: the headless stand-in for a particle emitter was compensating on the
**wrong stream**, so the trainer ran one value ahead of the rendered game from the first scroll read
and one snake attack rolled differently; `:desktop:viewcheck` is **20 of 20**. 37: a hero's death
bookkeeping was compared as if it were gameplay - `Bones.leave` draws per item for a random-seed
episode and not for a custom-seed replay of the same episode - and the worker-side ordered draw log
names every one of them. Measurements, mutations and the worker wiring are in `CHANGELOG.md`,
`out/evidence/issue-26/ordered-log-named.txt` and `out/evidence/issue-37/named.txt`.

## 21. The viewer and the trainer service a level transition in the same step but not the same place - **OPEN**

`superintelligence/trainer-replays/retrain-0.11.0-60/ZRD-CVK-QYT.dat` verifies headlessly in 888
steps with the score identical to the last decimal, and diverges in the real viewer. It is a
generation-48 recording of a WARRIOR that ping-pongs between floors 1 and 2 through the stairs.

**What is fixed, and it was half the fault.** `Camera.update` drew the screen shake's two offsets
from `Random` - the gameplay stream - and only a rendered game has a camera to shake, so the trainer
never spent those values and the viewer did. How many it spent depended on how long the shake
lasted, which depends on the frame rate, so the two streams parted at a different step on every
run: **steps 213, 235, 477, 519 and 552** in five runs of the same recording. The shake draws from
`PRandom` now, the ordered draw log agrees for **537 draws** instead of 144, and the divergence is
one step rather than a race. `observecheck` holds it.

**What is left, and it is a level transition.** The divergence is now at **step 638**, and it is
this: the recording's `INTERACT` leaves the hero on cell 910 of floor 1 and the viewer's leaves it
on cell 1170 of floor 2.

| | recording | viewer |
| --- | --- | --- |
| step 637 | 911, turn 1.0 | 911, turn 1.0 |
| step 638 | 910, turn 3.0 | 1170, turn 0.0 |
| step 639 | 1170, turn 0.0 | - |

`Hero.actTransition` takes one of two paths: if the transition's cell is the hero's own cell it
activates it, and otherwise it walks the hero there first (`getCloser`) and the activation happens
on the hero's next turn. **The recording walked the hero and the viewer activated it**, so the two
floors put the stairs in different places, and the transition cell is not in the world snapshot's
cell records - it carries terrain, visibility and line of sight, and not what is on the cell.

**Everything upstream of it agrees.** The base stream is level at the descent (`baseDraws=537` and
the same fingerprint on both sides), the generator pushes are identical in content and order, the
world's cells agree to step 637, and the hero's position, health, engine time and inventory agree to
step 637. The difference is in the cell *contents*, which the snapshot does not record.

**The next measurement** is the stairs' cell on the reloaded floor 1 in each environment, and it
wants an instrument that does not exist yet: the world snapshot's cell record carries terrain,
visibility, passability and line of sight, and not the heap or the transition on the cell. Adding
those two is observation-only and would settle it.

Evidence: `out/evidence/viewer-divergence-zrd/` - the headless and viewer ordered draw logs, the
world snapshots, and the per-step traces.

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