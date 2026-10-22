# Open issues

Closed: `CHANGELOG.md`. Declined: `REJECTED.md`.

## 23. Viewer parts from `fresh-1022` recordings that verify headlessly - 1 OPEN

246 of 257 played clean; the 11 failures are four faults, three fixed:

| recordings | cause | status |
| --- | --- | --- |
| `SSW-ZVA-GRB`, `DSD-FKG-MLX` | items fallen down a chasm never placed headlessly (`Dungeon.droppedItems`) | fixed in `LevelPipeline`; these two were recorded without the items and stay unplayable |
| `PTJ-VRT-KHR`, `JUZ-UAQ-GCH`, `FUE-XLT-OVJ`, `LFJ-HPG-RFV` | viewer settled a step before a disguised Mimic's attack animation landed | fixed in `ReplayPlayer`; fixture `rogue-mimic.replay` |
| `SZZ-PPW-YGF`, `MSR-XCX-JWZ`, `PBC-UFZ-RLA` | 300 s gate budget, not a fault: 187 s and 206 s alone | budget is now progress-based |
| `RXN-FXT-XRB` | `Burning.totalIncomingDMG` draws `nextHit` from the gameplay RNG when a UI health bar asks | engine patch proposed, see 22 |
| `GFQ-ZBM-CRL` | open: viewer's Gnoll Trickster hits three times inside hero turn 128 (steps 459-460, all REST) and kills the hero; headless it hits once, at step 460. RNG and world agree through step 458. Neither the in-flight wait, nor waiting on live `MissileSprite`s, nor the `Burning` patch changes it | open |

With all fixes, `viewcheck` on the 257: 254 clean (`out/evidence/issue-23/fresh-1022.viewcheck2.txt`).
Evidence `out/evidence/issue-23/`.

## 22. Viewer burns a Swarm to death a turn before the trainer does - CAUSE FOUND, needs an engine change

`seeds100-60/HPY-QJL-DPP` diverges in the viewer at step 836, hp 7 against 2. Cause: `Burning.totalIncomingDMG`
draws `nextHit` from the gameplay `Random` when it is 0, and only the rendered game calls it - `StatusPane`,
`HealthBar`, `CharHealthIndicator` and `BossHealthBar` via `Char.incomingDOT()`. The viewer draws a burning
char's first hit at frame time, the trainer in `Burning.act`, so every roll after it reads another value.
`out/evidence/issue-23/burning-ui-draw.patch` (estimate the first hit instead of drawing it, so the rendered
game draws where the trainer does) makes `HPY-QJL-DPP` and `fresh-1022/RXN-FXT-XRB` play to the end; without
it `HPY-QJL-DPP` still diverges at 836. It is a `core` logic change, so it is uncommitted pending approval;
trainer recordings are unaffected because headless never calls the UI path.

## 20. Agent dies on floor 1 instead of descending - OPEN

Now: descends, then dies on 2-4. `teach-demo-30/22/31` (teacher + 6 demos): depth>=2 in 56-62 of 64
episodes from gen 11, mean depth 2.5-2.9, best 4, deaths 90-100% to gen 31.
`trainer-replays/teach-demo-31` (5, `out/analysis/DeathProbe.java`):

- Every death is melee to 0 HP, still attacking at 1-2 HP; never retreats, never drinks. Hero level 1-2
  on depth 3-4. Mage dies on depth 1 meleeing a Snake it never hits.
- Dive pays: `VKG-DWR-EIN` dies on depth 4 at +2543; descent reward outweighs `DEATH` -2500.
- Rogue stall: 822 steps, `USE` on Waterskin/VelvetPouch/Seed 291 times, 34 refused.
- 90% of episodes on one locked seed (`seeds=1/100` until best depth > 5).

`long-1021`, 30 gen, 16 workers x 4 episodes, after forfeit fix (2026-10-21):

| | first third | last third |
| --- | --- | --- |
| death | 85% | 87% |
| stalled | 15% | 11% |
| turn_limit | 1% | 2% |
| depth>=2 | 0.2% | 0.1% |

`meanScore` +191.89 +/- 54.6, from fewer stalls only. Evidence `out/evidence/long-1021.csv`, `.log`.

Replays (71, `out/evidence/long-1021-replays.txt`): 60 death, 8 stalled, 3 turn_limit. Policy near
uniform: entropy 2.10 -> 1.85; `REST` 6676, `WAIT` 5618, `MOVE_N` 5099, `SEARCH` 4898, `DROP` 4711 steps.
Median 44 distinct cells in ~1000 turns. Most deaths sit at 1 HP for 100+ turns, then die after turn
~800: starvation pattern. Whole run = **608 Adam steps** (~20 per generation: 5% sample rate, 2 epochs,
minibatch 32 chunks x 16).
