# Open issues

Closed: `CHANGELOG.md`. Declined or deferred: `REJECTED.md`.

## 20. Agent dies on floors 2-4 - OPEN

Floor 1 is solved: `teach-demo-31` reaches depth>=2 in ~90% of episodes, mean depth 2.5-2.9, best 4;
`seeds100-60` reaches floor 4 on 4 unseen dungeons. Deaths 90-100%.

- Every death is melee to 0 HP, still attacking at 1-2 HP; never retreats, never drinks. Hero level 1-2
  on depth 3-4 (`out/analysis/DeathProbe.java`).
- The `DescentBot` teacher is no better; the policy already beats it. `out/analysis/BotStats.java`, 200
  episodes over five classes: 2.18 mean depth, 178 die, hero level 1.66, 41 of 92 final fights begun above
  75% HP against ordinary depth 2-3 mobs, 24-27% starving with no food. Teacher now 2.38, 167 die
  (`CHANGELOG.md` Unreleased). Drinking unknown potions when safe: no change.
- 2026-11-05, 514 recordings: 19% of seen food and 23% of seen Upgrade scrolls were picked up, nothing aimed ever
  fired, 89% of deaths were hungry or starving, and Goo was never killed in 106 fights. Fixed in `CHANGELOG.md`
  Unreleased; the teacher went from 2.56 to 3.28 mean depth and floor 6+ from 0 to 22 of 200. The policy has not caught
  up: at generations 150-220 it reads 2.17 against `flip-long`'s 2.60, with entropy 0.80 against 0.39. Dropping
  `reward.sneak_attack_reward` from 300 to 160 changed nothing (2.19 against 2.17), so that is not the cause, and no
  run has yet passed generation 302. `out/evidence/tactics-menu`, `tactics-long2`, `tactics-sneak160`.
- Stats: Upgrade scrolls could not be used at all headless (item picker, `CHANGELOG.md` Unreleased). Fixed;
  the teacher now reads, upgrades and equips, and explores before descending: 2.56 mean depth, floor 4+ 27
  of 200, 3 stalls. Upgrades stay scarce before death (mean armour +0.16, no tier-2 armour worn: it needs
  11 strength). Remaining gap is combat: 197 of 200 still die to ordinary depth 2-3 mobs, swarms of flies
  the largest single group, hero level ~2.3.

## 41. GPU reads stall a long run - OPEN

Two of two full runs on the 30-plane network died about 20 minutes in: generation time climbs from about 2 s to 14-23 s
from minute 5 to 9, with workers parked in `clEnqueueReadBuffer`, the machine about 20% busy and 27 s of GC in 20
minutes, then the watchdog fires with no worker progress for 180 s and for 900 s. No driver reset is logged. A 7-minute
run did not reach it, and `flip-long`, on 23 planes, ran 30 minutes without it.

`out/analysis/gpu/GpuSoak.java` drives `BatchedInference` alone with Discord and video open: 48 threads hold 71,700
forwards/s for 90 s, worst 2.9 ms after warm-up; the weights from the stalled run hold 66,000-70,000; 5 threads hold 10,000
and adding a live PPO update holds 49,000, worst 28 ms. So GPU capacity, those weights and Discord are not the cause, and
the stall needs something only real collection does. On a stall the log does not say what the inference threads were
reading, so the watchdog should record that.

`clear-long`, 1250 generations in 32 minutes at 39K turns/s on the same network, ran with no stall, no forward over 500 ms and no
episode over 8 s after the PC was cleared. Two runs with apps open had died. This does not rule the apps out and contradicts the
Discord line above, which only covers inference alone. The cause is still unknown; `TrainerWorkers.reportSlowest` and the watchdog
diagnostics now log the next stall. A stall under normal use remains a trainer bug.
