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
- Stats: Upgrade scrolls could not be used at all headless (item picker, `CHANGELOG.md` Unreleased). Fixed;
  the teacher now reads, upgrades and equips, and explores before descending: 2.56 mean depth, floor 4+ 27
  of 200, 3 stalls. Upgrades stay scarce before death (mean armour +0.16, no tier-2 armour worn: it needs
  11 strength). Remaining gap is combat: 197 of 200 still die to ordinary depth 2-3 mobs, swarms of flies
  the largest single group, hero level ~2.3.

## 39. Wild Magic wand level draws gameplay RNG on the render thread - OPEN

`Wand.buffedLvl` rolls `Random.Int(2)` while `WildMagicTracker` is attached, and `ItemSlot` calls it through
`buffedVisiblyUpgraded` on every toolbar refresh, on the render thread. The viewer spends gameplay values the
trainer does not, at a frame-dependent point. Not moved to `PRandom`: the roll sets wand power, so it would
desync gameplay. Not wrapped in a pushed generator: the stack is shared with the actor thread. Unreachable
by the trainer today (Battlemage armour ability, after Tengu).
