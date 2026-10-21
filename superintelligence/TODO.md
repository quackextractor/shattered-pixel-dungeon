# Superintelligence TODO

Open work only. Done: `CHANGELOG.md`. Findings: `issues.md`. Declined: `REJECTED.md`.

## 1. Teach it to descend

- [ ] **Agent dies on floor 1.** `issues.md` 20. Policy still near uniform after 608 Adam steps.
      In order, cheapest first:
  - [x] More gradient steps: 0.25 sample, 4 epochs, minibatch 8 (~9x).
  - [x] Episodic visit bonus `reward.visit_reward` 1.5.
  - [x] `TRAVEL_STAIRS`, `TRAVEL_EXPLORE` actions.
  - [x] `DescentBot` teacher, imitation loss `rl.teacher_weight`, half-life 10 generations.
  - [ ] Bot dies in fights at depth 2-4 (9 floors / 6 seeds): no equipment, no potions, no retreat.
  - [ ] Go-Explore on fixed seeds: archive (depth, cell), return by action replay, keep floor-reaching
        trajectories as BC data.
- [ ] **Walks to stairs?** Measure how often episodes end nearer stairs than they started, against a
      random walk on same floors. `EXIT_BEACON` lights only after stairs seen; `StairPotential` pays
      from step 1.
- [ ] **Depth 2 entrance room behind hidden door** on 4 of 4 sampled floors (`Phi` 0 when unreachable).
      Measure across seeds.
- [ ] **`free_step` stalls 56% in one short-cycle generation.** Read stalled recordings in
      `trainer-replays/forfeit-quick/` before tuning `env.free_step_budget`.
- [ ] **Shaping fade untested.** Starts at depth 3; no run passed floor 2.

## 2. Engine and harness

- [ ] **`HeroDisguise` crashes headlessly.** Cursed wand casts sprite to `HeroSprite`, which needs
      texture film.
- [ ] **Audit `core` for gameplay behind `scene() instanceof GameScene` / `GameScene.scene != null`.**
      `ImpShopRoom.onLevelLoad` is gameplay (depth 20). No gate.
- [ ] **Viewer plays unknown hero class as Warrior.** `ReplayLauncher`, `ReplayPlayback.heroClass()`.
- [ ] **`restartcheck` copies `ReplayController.restart` sequence** instead of calling it.
- [ ] **`RewardTerminalCheck` 607 lines**, over 600 limit.
- [ ] **`viewcheck` progress bar spams** when stdout not a TTY.
