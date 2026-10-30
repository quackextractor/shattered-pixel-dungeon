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
      `long-teach30` (300 gens): depth tracks the teacher weight - 2.78 at weight 0.3 (gens 40-59), 2.63
      at 0.08, 2.43 at 0.02, 2.17 at 0.001; entropy 0.58 -> 1.35, turns 636 -> 980. PPO alone drifts back
      to the half-life-10 plateau. `const-teach03` (weight held 0.3): 2.74-2.83 over gens 60-149,
      entropy 0.46. Now `rl.teacher_floor` 0.3. `floor-teach03` (defaults, 150 gens):
      2.82 over gens 50-149 (sd 0.15), 2.90-2.92 in gens 120-149 and still rising, entropy 0.36.
      Next: a longer run on the floor; the plateau is still well short of floor 4.
- [ ] **Training throughput past 7,114 turns/s.** GPU update + 12 workers x 4 is 3.24x the CPU update
      (2,197; `CHANGELOG.md` Unreleased). Collection 3.53 s and update 3.02 s a generation, so both bind.
      Worker step: env 0.24 ms, forward 0.28 ms (reduce layer 0.134 of it). Update: naive OpenCL kernels,
      ~16 ms a minibatch of 128 rows. Ceiling with free inference and a free update is ~0.24 ms a step on
      ~9 effective cores, ~25-35K turns/s; past that is the engine, which is not ours to change.
      Measured on `perf/throughput` with inference stubbed to zero cost (uniform policy, `out/bench/spike`):
      one env 9,690 turns/s, 16 envs in one JVM 22,706, 4 JVMs x 4 envs ~27,900 - 2.9x one env on 20
      logical cores. That is the ceiling for env + game on this i5-14600KF whatever the network costs,
      12.7x the 2,197 CPU baseline. In-process workers with batched GPU inference (`worker.in_process`)
      match but do not yet beat process workers end to end (3,605 against 3,754 turns/s, same load).
- [ ] **Throughput plan: 9,343 -> 220,000 turns/s (100x the 2,197 CPU baseline).** Today: 12 process
      workers x 4, GPU update, 9,343 turns/s end to end; collection 2.8 s and update 2.3 s a generation.
      100x means, per trained turn, ~45 us of CPU across ~9 effective cores and 4.5 us of GPU update per
      sample-pass (0.25 sample rate x 4 epochs); today they are ~1.3 ms and 87.5 us. Every phase keeps the
      replay contract (`verifyall`, `viewcheck`, `paritycheck` byte-identical) and is merged only when it
      beats the previous default in an ABAB run on a quiet machine.
  - [x] **P0 Bench as a gate.** Done: `throughputbench` (env-only, inference-only, update-only; round 0
        warms the JIT, median of the rest; 1 s idle-CPU sample before each leg, `[WARN]` above 10%; CSV
        appended to `out/evidence/throughputbench.csv`, `--label` names the tree). End-to-end stays the
        trainer's own turns/s. ABAB: run it in two worktrees alternately with different labels.
        Original note: Commit `out/bench` as `throughputbench`: env-only (inference stubbed),
        inference-only, update-only and end-to-end, each reporting turns/s, plus a JFR profile flag. Fixes
        the noise that cost a day: ABAB order, idle-CPU check before each leg, results to `out/evidence`.
  - [ ] **P1 Inference off the CPU, latency hidden (target 25-28K, the env-only ceiling).** Per-step
        inference is 70% of worker CPU. (a) Each env thread owns 4-8 envs and steps whichever has its
        action back, so no thread waits on the GPU. (b) One fused inference kernel per batch (packed-bit
        conv, reduce, trunk, one LSTM step, heads), LSTM state resident per slot, inputs and outputs in
        one pinned buffer each: target <150 us a batch at 128 rows. (c) 64-128 in-process envs; in-process
        becomes the default only when it wins. Gate: `gpucheck` batched case at 128 rows.
  - [ ] **P2 Update 20x (target 4.5 us/sample-pass).** Done so far (a, b and most of c): 84 -> 15.8 us
        a sample-pass in `throughputbench` (GPU time 557 -> 94 ms an update of 2,048 x 4), 58 launches a
        minibatch, no host round trip. In training the update fell 2.3 -> 0.35-0.5 s a generation, but
        `rl.overlap_collection` already hid it behind collection (barrier ~100% before, ~25% now), so end
        to end is unchanged at ~11.5-12K turns/s steady state (`out/evidence/p2-proc.log` against
        `fix-proc.log`). The update has 4-5x headroom; collection is the whole critical path until P1/P3/P4
        land. Left: im2col/col2im (115/132 us a minibatch), first-layer packed kernels (~100 us each), fused
        LSTM (100/92 us), the 85 us slice sum; bigger minibatches (P6) amortise the 58 launches.
        Original note: (a) PPO loss, imitation, value and entropy as a
        kernel, so a minibatch needs no host round trip; clip scale computed on the device. (b) The whole
        update enqueued at once, one sync per update; the buffer's packed grids (~47 MB) uploaded once and
        indexed per minibatch. (c) Tiled local-memory GEMM for trunk and LSTM, packed-bit conv in the
        backward pass, fewer and fused launches (~100 a minibatch today). Gate: `gpucheck` against the CPU
        update to 1e-4, parameters to 1e-2 of the step.
  - [ ] **P3 Env CPU 5x (target ~20 us/turn, 60-90K).** Re-profile env-only after the sparse fixes.
        Candidates: observation built as packed bits directly and windowed on the GPU from level-space
        deltas; travel masks only when TRAVEL could be chosen, cached per game-state version; teacher
        labels only on retained steps, with `lastHp` and `waited` (the only state it carries) updated
        cheaply every step; reward terms incremental. Gate: `sparsecheck`, `teachercheck`, `rewardcheck`
        byte-identical on the corpus.
  - [ ] **P4 Parallel scaling (16 threads are 2.9x one).** Find why: memory bandwidth, allocation rate,
        per-loader JIT copies (code cache full GCs), E-core placement. Fixes in reach: allocation-free step
        loop, a larger code cache, pinning envs to P-cores. Target 6x on 16 threads.
  - [ ] **P5 Game engine hot paths (signed off 2026-10-30, bit-identical only).** FOV (`ShadowCaster`), `PathFinder` and actor
        scheduling are what is left after P3. Changes only if every recording and RNG trace stays
        bit-identical; the engine boundary otherwise forbids committing them.
  - [ ] **P6 Learning-loop changes (signed off 2026-10-30, depth at equal turns must hold).** Asynchronous
        IMPALA-style learner with no generation barrier, larger minibatches with a scaled learning rate.
        Speed only counts if depth at equal turns does not drop.
      Projection, compounding: P1 + P2 ~20-25K (10x); + P3 60-90K (30-40x); + P4 and P5 150-220K (70-100x).
      P1 and P2 are engineering with known shapes; P3-P5 are where 100x is won or lost.
