# Superintelligence TODO

Open work only. Done: `CHANGELOG.md`. Findings: `issues.md`. Declined or deferred: `REJECTED.md`.

- [ ] **Survive floors 2-4** (`issues.md` 20). Plateau depth 2.5-2.9, best 4; every death is melee to
      0 HP with no retreat or potion. `server-14h` stopped at generation 360 and regressed: depth 1.66 ->
      1.15, floor 2+ 57% -> 14%, clip fraction ~0.7, teacher weight already ~0 at resume (decay uses the
      absolute generation). `out/evidence/server-14h/analysis.txt`. Next run: new `DescentBot` (2.56), teacher
      decay restarted, smaller updates; resume from `weights-seeds100-60-gen61.bin` (gen 65 was overwritten), not 360. Ctrl-C loses the run's replays.
      `fresh-1023` (new bot, 6x7 episodes, demos): depth 1.09 -> 2.40 by thirds, floor 2+ 87% in the last
      ten, still rising; `fresh-1022` (old bot) reached 1.75 / 58%. Clip fraction 0.69 -> 0.77, so smaller
      updates are still owed. Resumed to 100 (`fresh-1023-to100`): depth flat 2.35-2.49 from gen 30, floor
      2+ ~93%, score -822 -> +261 and turns 588 -> 965 without depth, entropy 0.98 -> 1.32, clip 0.84.
      Replays (`out/evidence/fresh-1023-to100-depthprobe.txt`): EXPLORE re-entry leak farmed by stair
      dancing (fixed); deaths melee to Gnoll 57 / Snake 37 / Rat 33 at hero lvl 1.55, cloth armour +0.04;
      at HP < 1/3 beside a foe it attacks (MOVE 1323) and almost never leaves (TRAVEL_STAIRS 3); depth
      2.4 sits at the teacher's 2.56. Score gain is KILL (1158/run) on floors 2-3, not depth. Clip
      fraction counted chunks (fixed); per step it is 0.30 late in a run. A low-HP retreat to stairs made
      `DescentBot` worse in three forms (2.44 / 2.53 / 2.51 against 2.56, `out/evidence/botstats-200-retreat*.txt`);
      60 of 197 deaths start the fight at full HP, so the bot is outclassed, not cornered.
      `fresh-1023-explorefix` (EXPLORE fix, fresh, 100 gens): same plateau, peak 2.47 at gens 20-29 while the
      teacher still has weight, 2.17-2.34 after; ascents unchanged (98/257) and now unpaid, random steps
      onto the entrance on arrival; entropy 0.92 -> 1.34. `fresh-1023-ent003`
      (`rl.entropy_coefficient` 0.003): entropy still 0.84 -> 1.27, depth 2.41-2.53 over gens 20-79 then 2.19,
      so the bonus is not the drift. Every run peaks while the teacher has weight (half-life 10) and
      declines after. `fresh-1023-teach30`: 2.72 over gens 50-99 (sd 0.15),
      entropy 0.55-0.75, now the default; eases to 2.56 by gen 90-99 at weight 0.125. Ascents 80/257.
      Next: a longer run to see whether depth holds once the teacher is gone, or a weight floor.
