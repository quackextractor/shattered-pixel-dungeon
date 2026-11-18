# Agent Directives

Project rules for Shattered Pixel Dungeon. Loaded automatically on every request.
Edit this file, not `instructions.md`. `STATUS.md` records which of these have already been applied and why.

## Environment

- Windows 11 PowerShell. Never `&&` — use separate lines or `;`.
- Paths: forward slashes or `Join-Path`.
- No emojis in code, logs, or output. Use `[OK]` / `[WARN]` / `[ERROR]`.
- Use the `edit` tool, not shell line-slicing, for editing files.

## Validation

- The gates are the tests. No module has a unit-test source set, so `./gradlew test` checks nothing beyond compilation, which every gate run already does, and it fails at configuration without an Android SDK. Do not use it as a check.
- New logic needs a gate case or a scenario that fails when the logic is removed. A new external interface (CLI flag, replay field, wire format, action) needs a case that drives it end to end.
- **Do not run `verifyall` before committing.** `hooks/pre-commit` runs it and refuses the commit if it fails, so running it first duplicates the same ~30s twice and changes nothing. Same for `viewcheck`: it is not in the hook, so run it when the change touches the viewer, the replay format, or anything a recording records — not before every commit.
- Run the *individual* gate you changed, though, and watch it fail when you break what it guards. A green gate you have not seen go red is a gate you have not tested.

## Structure

- Split by functional boundary, not technical type.
- 500-line limit (600 for tests) — except `core` / `SPD-classes` / `desktop`: never refactor those for line count.
- One purpose per module, class, and function. No god objects.
- DRY, unless it produces an opaque helper module.
- Introduce a pattern only when it removes real complexity; justify it if the benefit is not obvious.

## Code documentation

- Write no comments in code. Not `//`, not `/* */`, not Javadoc, not license headers, not annotations of prose. Naming carries meaning; a comment restating the line below it is noise.
- No lengthy docs or prose blocks in source. If a comment is unavoidable, keep it to one short line and only for something the code cannot say: a non-obvious invariant, a workaround for an external quirk, a units or format note.
- Never a multiline comment block. Never a comment explaining what was changed, why the author was confused, or what the next reader should think about.
- When existing code carries lengthy docs or comments, delete them as part of the change that touches that code. Replace only what a reader genuinely cannot recover from names and types with one compact line.
- Do not create extra `.md` files to explain code. Prose belongs in the existing docs, and only when asked.

## Safety

- Bind SQL/ORM parameters; never build queries by concatenation.
- Sanitize input against XSS and path traversal.
- No hardcoded environment values — use `.env` and `.env.example`.

## Commits

- Conventional Commits 1.0.0.
- Never `--no-verify` past `hooks/pre-commit` or `hooks/pre-push`.
- After three failed attempts on a build, test, or audit step, stop. Report each attempt with the exact error and your diagnosis. Never disable a failing test to force a pass.
- **Describe the change, not the author.** A commit message states what changed, why, and what was measured — nothing about who or what made it. No `Co-Authored-By:`, no `Generated with`, no tool name, no model name, no `noreply@` address, no "this commit was written by". Those are trailers nobody in this repository's history uses, and adding one attributes work to something that is not a party to the project's conventions. Conventional Commits is a spec for the change; a trailer naming a tool is not part of it.

## Engine boundary

> You may edit the game files (`core`, `SPD-classes`, `desktop`), even logic changes, as long as you don't commit those changes — e.g. for testing purposes. Getters and such are always fine, even for committing. Also don't refactor them (such as splitting the files to make sure the character limit is appropriate). Also the replay function has priority. At no point should the trainer be considered more important than being actually faithful to the game. The only exception I would make is overhauling the rng system as it would still be random, although such changes to the pre-existing source code would be risky and error-prone.

In practice: uncommitted engine edits are the normal way to test a hypothesis — change it, run the gate, then revert or propose it. Getters and observation-only additions may always be committed.

Signed off 2026-10-30: performance-only engine changes (FOV, pathfinding, actor scheduling and the like) may be committed when every recording in `test-replays/` and every RNG trace stays bit-identical (`verifyall`, `viewcheck`, `paritycheck`). Learning-loop changes (asynchronous learner, minibatch size, learning-rate scaling) may be committed when depth at equal turns does not drop. Logic changes stay uncommitted unless asked; permission to experiment is not permission to ship a behaviour change. A committed tree must always build and pass `verifyall` on its own.

## Training

- **Go wide on serious runs.** The defaults already do: in-process workers, async collection, 2.4 workers per logical core x 2 episodes. Workers share one JVM of `worker.in_process_heap`, so pushing them past 125 MB each trades throughput for GC thrash. `worker.in_process=false` returns to one process of `worker.heap_max` each. The update and batched worker inference run on the GPU through OpenCL (`rl.device`, default `auto`).
- **The two gates are expensive.** `./gradlew verifyall` and `./gradlew :desktop:viewcheck` run about 40s each. `viewcheck` is *not* part of `verifyall`, so run both before calling anything done. Never run them speculatively.
- **Smoke runs stay short.** Bound every test training run with `--max-turns` and an external timeout. A run proving a wiring change does not need to finish an episode.
- **Tune rewards, don't stall.** Every knob lives in `superintelligence/superintelligence.properties`, overridable via `.env` as `SPD_*`. If a value you need is not exposed, expose it — properties file, `.env`, or a CLI flag. Modify the trainer when that is what it takes. Do not ask for input and do not wait to be told. That is the job.
- **Do not waste clock.** Never re-run a gate because formatting looks off, and never re-run a passing test to be sure. One run, one verdict.
- **Read the replays.** `testing-guide.md` names the tools; the recordings in `superintelligence/test-replays/` and whatever a run has written into `superintelligence/trainer-replays/` are small enough to read in full. Start with the highest- and lowest-scoring ones and read all of them — the worst run is usually where the bug is. The first directory is the checked-in corpus, which reproduces end to end; the second is what the trainer actually wrote, which does not yet — see `CHANGELOG.md` 0.7.0.
- **The goal is the game.** Make the AI good enough to win, or at least descend several floors. Those are the two things that matter most; everything else is subordinate.

## Artefacts

**Everything a session or a run produces goes under `superintelligence/out/`, and nothing meant to be read
later goes in the system temp directory.** The temp directory is emptied on a schedule nobody controls, so
a metrics CSV, a training log, a trace, a saved replay, a patch or a commit message parked there is
evidence with a timer on it — which is worth nothing the moment the machine reboots, and the whole reason
`out/` exists.

| what | where |
| --- | --- |
| training weights, checkpoints, metrics CSV | `superintelligence/out/run/` — the trainer's default `--out` |
| evidence worth keeping: logs, per-generation CSVs, replay copies, patches, diffs | `superintelligence/out/evidence/` |
| a run's own recordings | `superintelligence/trainer-replays/` (the catalog reads it) |
| gate scratch — `spd-<gate>` under temp | **the exception**: regenerated on every run, nothing to keep |

`out/` is gitignored apart from a tracked `.gitkeep`, so an artefact there costs the repository nothing and
can be deleted freely. Commit an artefact only when it is part of what the change is about, and then by
copying it in rather than by un-ignoring the directory. When a run's output matters to a later reader —
which is most measurements worth making — it goes in `out/evidence/` **in the same session it was produced**,
not copied out afterwards on the assumption someone will remember.

## Workflow

- Track progress in `todo.md`. No `Fix x:` / `plan x:` / `item x:` markers in source comments.
- Update `changelog.md`, `README.md`, and `docs/documentation.md` alongside the code.
- After a cycle completes, re-read `issues.md` and `TODO.md` and repeat until nothing in either is
  outstanding. `testing-guide.md` must report no issues.

## Response format

- No articles (`a`, `an`, `the`), no filler (`just`, `really`, `basically`), no pleasantries (`sure`, `certainly`).
- No preamble or postamble. Start with the answer, stop when the facts are delivered.
- Action first. Explain only when asked.
- Keep technical data exact and code blocks unchanged.

## Memory (imported from Claude Code, 2026-11-08)

Source: `~/.claude/projects/C--Users-Soul-Documents-GitHub-shattered-pixel-dungeon/memory/`. Bodies are verbatim.

### Training machine (project)

Updated 2026-10-29.

Training machine: i5-14600KF (6 P-cores + 8 E-cores, 20 logical), 32 GB RAM, AMD RX 6900 XT (OpenCL device name `gfx1030`). Desktop apps hold ~8-12 GB, so expect ~12-20 GB free for workers.

2026-11-04: **48 x 2 in-process async is now the shipped default** (no overrides needed): 43K turns/s steady on an idle machine, ~34K with the machine in use, against 19K for 12 x 8 processes. Longest in-process run so far: 30 minutes.

The user uses this PC during runs: start commands at below-normal priority (children inherit it), one job at a time, say how long a run takes, and compare ratios or core time a turn, not absolute speed.

Claude Code's background-shell memory reaper killed a 2026-10-20 run; it acts on system-wide free memory. Check free memory first (`Get-CimInstance Win32_OperatingSystem`), ~1.8 GB per worker. jstack/jcmd/jfr live in `C:\Program Files\Java\jdk-24\bin\` (not on PATH). Worker stdout is the protocol pipe: profiling with JFR needs `-Xlog:jfr+startup=off`, and workers are killed at the end, so dump them live with `jcmd <pid> JFR.dump`.

192 in-process envs need a 20 GB heap and left 1.7 GB of commit free on 2026-11-06; Claude Code's reaper then killed a background shell. Do not repeat that.

**How to apply:** run training on plain defaults; running `viewcheck` alongside a training run can starve its diagnostic child and fail it falsely.

### Agent failure analysis (project)

Updated 2026-11-05.

Replay analysis of 2026-11-05 lives in `superintelligence/out/evidence/failure-analysis/`, rebuilt by `out/analysis/fp/FloorProbe.java` and agg*.py. Fixed in 14 commits, 7615608fe..HEAD, unpushed that day.

State late on 2026-11-06: the policy-teacher gap was unlearnable plans, not rewards. `TACTIC`, `HUNT` and `ITEM` macro actions took evaluated depth from 2.07-2.11 to 2.81, floor 6+ from 0 to 24 of 500, in 30 minutes (`out/evidence/macro3`). Teacher 3.28 was measured on other seeds. Issues 39 and 42 are closed. Issue 41 (stall) is untested on the new code and issue 43 (Guard pull in the viewer) is open. Old weights do not load after the action and hero-feature changes.

**Why:** user wants floors 2-4 survived and Goo beaten, see `issues.md` 20.
**How to apply:** do not claim apps are ruled out of the stall. `goo-kill.replay` is the scripted teacher; AI runs have since passed Goo on their own. Use `:superintelligence:evaluate` for depth, not training metrics, which read about 0.3 higher. A failure seen in `verify` and training but never in `evaluate` means a leak from an earlier episode in the process. See training machine.

### Keep apps open during training (feedback)

Updated 2026-11-05.

Do not ask the user to close Discord, YouTube or other apps so training can run.

**Why:** they use the computer during training, and said so plainly when asked to close them on 2026-11-05.
**How to apply:** debug and test with their normal apps open. Treat a stall under normal use as a trainer bug. See training machine and agent failure analysis.

### Tests short and self-contained (feedback)

Updated 2026-11-05.

Tests should be self-contained and short-lived. Do not propose a 20-minute training run as a test.

**Why:** the user said so directly on 2026-11-05 when a long diagnostic run was offered.
**How to apply:** isolate the suspect part and exercise it directly in about a minute, as `out/analysis/gpu/GpuSoak.java` does for GPU inference. Keep long runs for measuring learning, not for debugging. See agent failure analysis.

Applies to gate sweeps too: on 2026-11-06 a `viewcheck` over 257 trained recordings ran 1h40 with no limit and the user had to stop it. Sweep large sets with `:desktop:playbackcheck` on `.replay` copies (257 in 30 s), cap every run, and kill leftover child JVMs by PID, since TaskStop leaves them running.
