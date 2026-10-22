# Open issues

Closed: `CHANGELOG.md`. Declined or deferred: `REJECTED.md`.

## 20. Agent dies on floors 2-4 - OPEN

Floor 1 is solved: `teach-demo-31` reaches depth>=2 in ~90% of episodes, mean depth 2.5-2.9, best 4;
`seeds100-60` reaches floor 4 on 4 unseen dungeons. Deaths 90-100%.

- Every death is melee to 0 HP, still attacking at 1-2 HP; never retreats, never drinks. Hero level 1-2
  on depth 3-4 (`out/analysis/DeathProbe.java`).
- The `DescentBot` teacher is no better (mean depth 2.21, 92 of 100 die); the policy already beats it.
