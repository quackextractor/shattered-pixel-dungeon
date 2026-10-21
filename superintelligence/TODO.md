# Superintelligence TODO

Open work only. Done: `CHANGELOG.md`. Findings: `issues.md`. Declined: `REJECTED.md`.

## 1. Teach it to descend

- [ ] **Agent dies on floor 1.** `issues.md` 20. Read `trainer-replays/long-1021/`, best and worst first.
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
