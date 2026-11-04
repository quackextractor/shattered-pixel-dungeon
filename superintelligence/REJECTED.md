# Rejected and deferred

Proposals not done, with reason and trigger. Measurements: `CHANGELOG.md`.

| proposal | verdict | reason | revisit when |
| --- | --- | --- | --- |
| `reward.stair_potential_weight` 2.0 -> 10.0 | declined | 0 of 960 reach floor 2; objective 5 sigma worse. Term telescopes; pays for stairs observation does not carry. `out/evidence/descend-30-ab.txt` | observation carries unseen stairs |
| Ego-centred observation grid | deferred | rewrites every plane, voids every checkpoint. Exit beacon already survives window edge | next measured run that may break weights |
| Native / ONNX / GPU learner | declined | after the conv fixes `Conv2D` is ~72% of an update and per-sample; batching the trunk across a chunk gained 7% at 8 threads (`out/evidence/perf-1022.txt`). A GPU needs batched convolution and adds a platform matrix | conv is batched and still the wall |
| `jdk.incubator.vector` in `Dense.matvec` | declined | `Dense` 17% of forward, 7.6% of update; 62% is `Conv2D` with strided im2col | same as above |
| Worker reuses its `Network` across pushes | declined | `new Network` 22 ms per worker per generation, in parallel; its init draws `policyRng`, so skipping it moves every later action sample | worker setup shows in a profile |
| Skip `DescentBot.label` on unsampled steps or once `rl.teacher_weight` decays | declined | teacher off entirely: 0.678 against 0.680 ms/step on 714 steps, noise. Tail steps are kept at episode end, so every step needs its label anyway | teacher grows past a distance map per step |
| `--episodes` default 4 instead of worker count (N^2 jobs) | declined | 8 workers, 600-turn cap: 4 per worker kept 285 samples/s against 358 at 8; nothing dropped at either. Waste exists only when the reservoir drops, which the trainer warns about. `out/evidence/episodes-default-{4,8}.csv` | a run drops most of its samples |
| fp16 weight push | declined | 286 -> 143 MB/gen; barrier small share of wall | transport in profile |
| IMPALA / async learner (V-trace) beyond one update of lag | declined | `rl.overlap_collection` already hides collection behind the update at one update of lag; more lag needs V-trace | learning stalls on stale data |
| Workers update locally, send weights | declined | no pooled gradient; N policies drift | - |
| Cache generated floors per seed | declined | fresh profile, 12 envs, 26,847 samples: `Dungeon.newLevel` 1.6%, `switchLevel` 2.0%. The 28% that proposed this was `runToHeroReady`, the turn loop. A cache must also restore generation side effects to stay bit-identical | level generation passes 10% of a profile |
| Exact cache for `levelExplorePercent` | declined | 8% of env CPU, but it reads heaps, blobs, mobs, the map and notes, so no cheap key is exact and an observation must not change | the map scan is split from the rest |
| Reuse encoder output when the grid is unchanged | declined | 233,261 of 588,834 forwards (39.6%) repeat the slot's previous grid, but a quarter-width encoder on every row raised forwards/s only 16%, so skipping it on 40% of rows cannot do better, and a per-slot cache adds two kernel launches to the round trip that binds (GPU time 215 of 650 us) | GPU compute binds inference |
| Less update work (`rl.epochs` 4 -> 2) | declined | 48 x 2 async, 100 generations: update share 31% -> 17% but wall 222 -> 225 s, so collection binds, not the update; depth at 1-4M turns 2.13/2.29/2.38 against 2.20-2.24/2.32-2.39/2.41-2.47. Updating every second generation halves learning the same way | the update binds in a run |
| Smaller network (reduce 32 -> 8, conv 24 -> 12, trunk 256 -> 128) | declined | all three: inference 72K -> 84K forwards/s, 133K -> 138K at 256 callers, update 16.8 -> 11.4 us a sample-pass; reduce alone changes nothing. 100 generations took 207 s against 222 s, and depth at 1-4M turns was 2.13/2.30/2.36 against 2.20-2.24/2.32-2.39/2.41-2.47 | the update dominates a profile again |
| Virtual-thread envs (`Thread.ofVirtual`, 14 carriers) | declined | 48 envs, batched GPU inference, ABAB: platform 36,501/33,001 turns/s, virtual 36,490/32,579; core time a turn 414 us on both, so OS wake-ups are not the 341 us cost | wake-ups show in a profile |
| Threads instead of worker processes | impossible | static game state, one env per process | - |
| Frame-differencing observations | impossible | PPO shuffles minibatches; delta needs sequential access | - |
| Event-keyed RNG | declined | rewrite of all game randomness | - |
| Sound-pitch draws `Random` -> `PRandom` (10 sites) | declined | both environments draw them, cannot diverge; moving them shifts every seed's layout | - |
| `Mob.holdAllies` in `LevelPipeline.handleFall` | declined | unseeded draw on every trainer chasm fall | - |
| `checkFloorLimits` on engine time | declined | moves recording end points, voids corpus. Free steps priced via `reward.no_progress_penalty` | corpus regenerated for other reason |
| Viewer drives `SPDEnv` instead of live game | deferred | viewer redesign; `viewcheck` 20 of 20 without it | viewer divergence returns |
| libGDX trainer dashboard, live reward monitor | deferred | console ledger, chart and per-run CSV exist | someone watches runs live |
| Corpus generated on demand | declined | checked-in bytes are what gates compare | - |
| Fast + slow gate tiers | declined | one `gates` task ~40s; `viewcheck` already separate | - |
| Death costs more than a dive pays (`VKG-DWR-EIN` dies on depth 4 at +2543) | declined | dive reward is what got the agent off floor 1; pricing death above it makes staying on floor 1 optimal again. Survival already pays through later descents | agent dives into deaths it could avoid by waiting |
| Stronger `DescentBot` teacher (fight, retreat, potions) | deferred | three variants, no gain (explore 1.97, items 2.23, wait 2.17 vs 2.21); policy already beats teacher at 2.6-2.9. Low-HP retreat to stairs, three more: 2.44 / 2.53 / 2.51 vs 2.56 (`out/evidence/botstats-200-retreat*.txt`); 60 of 197 deaths start the fight at full HP | policy plateau below teacher |
| Measure walks-to-stairs against random walk | superseded | `TRAVEL_STAIRS` + teacher: depth>=2 in ~90% of episodes answers it | - |
| Measure depth 2 entrance behind hidden door across seeds | declined | not blocking: depth>=2 in ~90%, best 4 | descent stalls on one depth across seeds |
| Read `forfeit-quick` stalls before tuning `env.free_step_budget` | superseded | gen-2 near-uniform policy; ~20% of steps menu actions (`out/evidence/forfeit-quick-actions.txt`). Stall share fell to 11-15% in `long-1021`; `USE` no-op loop now masked | stall share >20% again |
| Test the shaping fade | deferred | starts at depth 3; median run never passes floor 2 | median depth >3 |
| Gnoll Trickster hits three times in viewer (`fresh-1022/GFQ-ZBM-CRL`, steps 459-460) | deferred | 1 of 257; RNG and world agree through 458; in-flight wait, live `MissileSprite` wait, `Burning` patch ruled out. `out/evidence/issue-23/` | a second recording shows it |
| `restartcheck` calls `ReplayController.restart` | impossible | restart switches to `InterlevelScene` (GL, `:desktop`); `restartcheck` mirrors its new-run path headlessly, `playbackcheck` covers `ReplayPlayer.restart` | - |
| `--auto` probe warm-up: largest thread count first, or one discarded update before timing | declined | best-of-2 already times 1 thread warm: 4.37 ms/sample ascending against 4.45-4.54 timed last. Largest-first left 8 threads cold (0.83-0.85 against 0.82); a discarded update cost 1.5-3 s of a 14 s probe and sped up no count. `out/evidence/autotune-4-*.txt` | a probe picks fewer threads than a later `updatecost` |
