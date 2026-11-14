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
- 2026-11-06: the policy's 2.29 was a gap in what it could learn, not in rewards. The teacher's plans were invisible to it, and macro actions took
  evaluated depth from 2.07-2.11 to 2.81 in 30 minutes, floor 6+ from 0 to 24 of 500. Next: the policy still sits below its teacher's 3.28, measured on
  other seeds, and training depth reads 3.1 against 2.81 evaluated. `macro3` predates the sell-window fix, so its deepest runs were cut short.
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

## 44. An adjacent aim index throws the aimed item itself - OPEN

`ActionMapper.resolveTarget` drops the cell listener for target indices 1-8 and calls `SlotAction.castAt`, which is
`Item.throwAt`. For a thrown weapon that is a throw; for a wand it throws the wand. Scenario `aim-no-enemy` zaps a
Wand of Magic Missile at an empty adjacent cell: `a zap at cell 708 left 3 of 3 charges, wand still carried false`.
The viewer runs the same code, so it is not a fidelity fault. The policy cannot emit 1-8 in `TARGETING` (gap G3 in 45),
so no training run reaches it; scripted recordings can (`duelist-throw.replay`, scenario `aim-empty`). Listed in
`scenario.ReachExpectations` as a known failure, so the gate goes red when it is fixed.

## 45. Mechanics the policy cannot reach - OPEN

`scenariocheck` drives each mechanic through `SPDEnv.step` and asks `scenario.PolicyReach`, which calls
`EpisodeCollector.headFor` and `subModeAction`, whether the collector could have emitted every step. A scenario whose
steps it could not is `GAP`, and `scenario.ReachExpectations` lists which ones must be, so closing a gap is a one-line
change there. These are action-space limits, not fidelity faults; every one of these recordings verifies and plays in
the viewer. Confirmed 2026-11-14:

| gap | scenario | first unreachable step |
| --- | --- | --- |
| G1 no drop | `drop-pickup` | `in SLOT the collector emits USE/1, never DROP` |
| G2 no cancel while anything is usable | `cancel.SLOT`, `.INVENTORY`, `.TALENT`, `.ALCHEMY`, `.MENU` | `in SLOT the collector emits USE/0, never CANCEL`; `MENU_SELECT/0, never CANCEL` in the other three |
| G3 aim only at the four nearest enemies | `aim-empty`, `aim-no-enemy` | `target 8 is masked in TARGETING` |
| G4 INTERACT's direction comes from the slot head | `interact-direction` | `INTERACT/8 reaches cell 708 only through slot 8, which the slot head does not offer` |
| G5 worn items are in no slot | `unequip`, `artifact.Cloak` | `the worn ClothArmor is in no slot`; `the worn CloakOfShadows is in no slot` |
| G6 Ethereal Chains cannot be cast | `artifact.EtherealChains` | `USE equips the chains and the aim after it casts nothing; worn, they are in no slot (G5)` |
| G7 shop windows cannot be answered | `shop-buy` | `buying opened WndTradeItem, which WindowBridge cannot answer` |
| G8 no throw from a pack of throwables, unknown potions always drunk | `throw-only`, `potion-unknown-throw` | `USE is masked in WORLD`; `an unidentified PotionOfToxicGas was drunk` |
| G9 known traps, wells and chasms cannot be stepped on | `well.*`, `trap.*` (11), `chasm` | `MOVE_N is masked in WORLD`: `Surroundings.moveLegal` requires `passable`, and `TRAP`, `WELL` and `CHASM` are `AVOID` terrain, which `Hero.handle` walks onto. A well is never drunk and a chasm never jumped by the policy. Found by the suite, not in the design. |

Three corrections to the design's reading. G6 is not a lost artifact: `throwAt` finds the worn chains in no bag and
detaches nothing, so the aim spends a turn and casts nothing. Before the fix in `CHANGELOG.md` it walked the hero
headlessly, a fidelity fault the viewer exposed. Second, G7 is partly wrong: talking to a Shopkeeper opens a `WndOptions`, which is
answerable; only the buy window is a plain `Window`. Third, G4 is reachable when the hero happens to carry an item in the slot
whose index equals the direction, which is exactly why it is a fault rather than a limit.

## 46. The chasm's "jump?" answer lands on a different step in the viewer - OPEN

`Chasm.heroJump`'s `WndOptions` accepts an answer only once its own `elapsed`, summed from `Game.elapsed` in
`update()`, passes 0.2 s. Headlessly the window is updated once per step at 1/60 s (`WindowBridge.age`; the
`ageSeconds` it sets is read by nothing), so the trainer's 13th `MENU_SELECT` is the first one accepted. The viewer also
updates the window every rendered frame, so it accepted the 11th: a scripted jump diverged at step 11, `hero at (27, 44)
pos 1743, recording says (6, 19) pos 747`. After the accepted answer the viewer's hero resumed into the chasm at once,
while the trainer's needed another `MOVE`. The `accepted` flag cannot tell the two presses apart, since `selectOption`
returns true for a declined press. The policy cannot reach it while G9 masks the move, so scenario `chasm` records the
prompt and backs out, and plays clean; the jump itself has no recording. A fix makes the answer step-deterministic on
both sides, for instance by having `WindowBridge.age` advance the window by more than the gate.

