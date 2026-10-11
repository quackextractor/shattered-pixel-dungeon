# Agent Directives

Project rules for Shattered Pixel Dungeon. Loaded automatically on every request.
Edit this file, not `instructions.md`. `STATUS.md` records which of these have already been applied and why.

## Environment

- Windows 11 PowerShell. Never `&&` — use separate lines or `;`.
- Paths: forward slashes or `Join-Path`.
- No emojis in code, logs, or output. Use `[OK]` / `[WARN]` / `[ERROR]`.

## Validation

- Run `./gradlew test` after every code change.
- New logic needs unit tests; new external interfaces need integration tests.

## Structure

- Split by functional boundary, not technical type.
- 500-line limit (600 for tests) — except `core` / `SPD-classes` / `desktop`: never refactor those for line count.
- One purpose per module, class, and function. No god objects.
- DRY, unless it produces an opaque helper module.
- Introduce a pattern only when it removes real complexity; justify it if the benefit is not obvious.

## Safety

- Bind SQL/ORM parameters; never build queries by concatenation.
- Sanitize input against XSS and path traversal.
- No hardcoded environment values — use `.env` and `.env.example`.

## Commits

- Conventional Commits 1.0.0.
- Never `--no-verify` past `hooks/pre-commit` or `hooks/pre-push`.
- After three failed attempts on a build, test, or audit step, stop. Report each attempt with the exact error and your diagnosis. Never disable a failing test to force a pass.

## Engine boundary

> You may edit the game files (`core`, `SPD-classes`, `desktop`), even logic changes, as long as you don't commit those changes — e.g. for testing purposes. Getters and such are always fine, even for committing. Also don't refactor them (such as splitting the files to make sure the character limit is appropriate). Also the replay function has priority. At no point should the trainer be considered more important than being actually faithful to the game. The only exception I would make is overhauling the rng system as it would still be random, although such changes to the pre-existing source code would be risky and error-prone.

In practice: uncommitted engine edits are the normal way to test a hypothesis — change it, run the gate, then revert or propose it. Getters and observation-only additions may always be committed. Logic changes stay uncommitted unless asked; permission to experiment is not permission to ship a behaviour change. A committed tree must always build and pass `verifyall` on its own.

## Training

- **Go wide on serious runs.** Scale workers to the machine: `./gradlew :superintelligence:train --args="--workers N"`. `trainerJvmArgs` pins `-Xmx1536m` per process, so pushing workers past what that heap supports trades throughput for GC thrash. There is no GPU path — the trainer is pure-Java PPO/IMPALA on CPU. Adding one means a native or ONNX runtime, not a flag.
- **The two gates are expensive.** `./gradlew verifyall` and `./gradlew :desktop:viewcheck` run about 40s each. `viewcheck` is *not* part of `verifyall`, so run both before calling anything done. Never run them speculatively.
- **Smoke runs stay short.** Bound every test training run with `--max-turns` and an external timeout. A run proving a wiring change does not need to finish an episode.
- **Tune rewards, don't stall.** Every knob lives in `superintelligence/superintelligence.properties`, overridable via `.env` as `SPD_*`. If a value you need is not exposed, expose it — properties file, `.env`, or a CLI flag. Modify the trainer when that is what it takes. Do not ask for input and do not wait to be told. That is the job.
- **Do not waste clock.** Never re-run a gate because formatting looks off, and never re-run a passing test to be sure. One run, one verdict.
- **Read the replays.** `testing-guide.md` names the tools; the recordings in `superintelligence/test-replays/` and `superintelligence/trainer-replays/` are small enough to read in full. Start with the highest- and lowest-scoring ones and read all of them — the worst run is usually where the bug is. The first directory is the checked-in corpus, which reproduces end to end; the second is what the trainer actually wrote, which does not yet — see `issues.md` 15.
- **The goal is the game.** Make the AI good enough to win, or at least descend several floors. Those are the two things that matter most; everything else is subordinate.

## Workflow

- Track progress in `todo.md`. No `Fix x:` / `plan x:` / `item x:` markers in source comments.
- Update `changelog.md`, `README.md`, and `docs/documentation.md` alongside the code.
- After a cycle completes, re-read `review.md` and repeat until nothing in it is outstanding. `testing-guide.md` must report no issues.

## Response format

- No articles (`a`, `an`, `the`), no filler (`just`, `really`, `basically`), no pleasantries (`sure`, `certainly`).
- No preamble or postamble. Start with the answer, stop when the facts are delivered.
- Action first. Explain only when asked.
- Keep technical data exact and code blocks unchanged.