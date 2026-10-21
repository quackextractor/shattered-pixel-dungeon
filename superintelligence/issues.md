# Open issues

Closed: `CHANGELOG.md`. Declined: `REJECTED.md`.

## 22. Viewer burns a Swarm to death a turn before the trainer does - OPEN

`seeds100-60/HPY-QJL-DPP` (Warrior, depth 4) diverges in the viewer at step 836, hp 7 against 2. RNG
draw counts and fingerprints match through step 834. Step 835: Swarm 60 dies on 824, its loot drop
presses a hidden fire trap (3x3 `Fire`, `HazardAssistTracker` on Swarm 65). Headless: Swarm 65 alive at
2/50 and burning, hits the hero 10 -> 2. Viewer: Swarm 65 already dead, hero 7. Same order-of-actors
class as the dart (an animation-gated callback vs a synchronous one), not yet located. Evidence
`out/evidence/hpy-world` (headless), `hpy-world2.viewer.txt` (viewer, continue-on-divergence),
`hpy-rng.*`.

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
