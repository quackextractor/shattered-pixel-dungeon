# Superintelligence TODO

Open work only. Done: `CHANGELOG.md`. Findings: `issues.md`. Declined or deferred: `REJECTED.md`.

- [ ] **Survive floors 2-4** (`issues.md` 20). Plateau depth 2.5-2.9, best 4; every death is melee to
      0 HP with no retreat or potion. `server-6h` on the Ubuntu server (4 workers x 4, resumed from
      `seeds100-60` gen 61, 100 seeds, 6 demos) was stopped at gen 66; checkpoint gen 65 in the server's
      `out/run/weights.bin`. Resume with `train.sh -r -H <hours>`, then read its deaths.
