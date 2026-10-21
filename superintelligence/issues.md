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
