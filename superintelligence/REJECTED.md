# Rejected and deferred

Proposals not done, with reason and trigger. Measurements: `CHANGELOG.md`.

| proposal | verdict | reason | revisit when |
| --- | --- | --- | --- |
| `reward.stair_potential_weight` 2.0 -> 10.0 | declined | 0 of 960 reach floor 2; objective 5 sigma worse. Term telescopes; pays for stairs observation does not carry. `out/evidence/descend-30-ab.txt` | observation carries unseen stairs |
| Ego-centred observation grid | deferred | rewrites every plane, voids every checkpoint. Exit beacon already survives window edge | next measured run that may break weights |
| Native / ONNX learner | declined | learner not the wall (30 gen in 12m41s); adds platform matrix | profile shows learner is wall |
| `jdk.incubator.vector` in `Dense.matvec` | declined | `Dense` 17% of forward, 7.6% of update; 62% is `Conv2D` with strided im2col | same as above |
| Worker reuses its `Network` across pushes | declined | `new Network` 22 ms per worker per generation, in parallel; its init draws `policyRng`, so skipping it moves every later action sample | worker setup shows in a profile |
| Skip `DescentBot.label` on unsampled steps or once `rl.teacher_weight` decays | declined | teacher off entirely: 0.678 against 0.680 ms/step on 714 steps, noise. Tail steps are kept at episode end, so every step needs its label anyway | teacher grows past a distance map per step |
| `--episodes` default 4 instead of worker count (N^2 jobs) | declined | 8 workers, 600-turn cap: 4 per worker kept 285 samples/s against 358 at 8; nothing dropped at either. Waste exists only when the reservoir drops, which the trainer warns about. `out/evidence/episodes-default-{4,8}.csv` | a run drops most of its samples |
| fp16 weight push | declined | 286 -> 143 MB/gen; barrier small share of wall | transport in profile |
| IMPALA / async learner (V-trace) beyond one update of lag | declined | `rl.overlap_collection` already hides collection behind the update at one update of lag; more lag needs V-trace | learning stalls on stale data |
| Workers update locally, send weights | declined | no pooled gradient; N policies drift | - |
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
