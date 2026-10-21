# Open issues

Closed: `CHANGELOG.md`. Declined: `REJECTED.md`.

## 20. Agent dies on floor 1 instead of descending - OPEN

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
