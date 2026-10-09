### Agent Directives

**1. Environment & Execution Constraints**
* **Shell Context:** Windows 11 PowerShell.
* **Command Chaining:** You MUST NOT use `&&`. Write multi‑step operations as separate lines. If a single line is unavoidable, use the semicolon (`;`) as separator (e.g., `Set-Location frontend; pnpm run build`).
* **CI/CD Triggers:** After completing a logical feature or fix, stage the relevant changes (use `git add -u` for modified tracked files and `git add <new-file>` for any new files) instead of blindly using `git add .`. Then commit (`git commit -m "<message>"`) but do not push. Ensure all local validation (including pre‑commit hooks) pass.
* **Path Handling:** Use forward slashes (`/`) or `Join-Path`. Use `Set-Location` (or `cd`) to change directories instead of `&&` chaining.

**2. Development Standards & Quality**
* **Post‑Implementation Checks:** After any feature or refactor, run static analysis (e.g., `pnpm run lint` if available) and then always run the full test suite (e.g., `pnpm run test`) to catch regressions.
* **Commit Message Convention:** All commit messages MUST follow the [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) specification. Use `fix:` for patches, `feat:` for minor bumps, and `BREAKING CHANGE:` (in the footer) for major bumps. This format will be used to automatically determine the semantic version increment.
* **Styling Rule:** You are strictly forbidden from using emojis (😊, 🚀, etc.) in code, logs, console outputs, or user‑facing messages. Use ASCII indicators (`[OK]`, `[WARN]`, `[ERROR]`) or dedicated icon libraries when icons are required.

**3. Testing Protocols**
* **Mandatory Execution:** Run the project’s test suite after **every** code modification, no matter how small. Determine the correct command from the project’s scripts.
* **Coverage Requirements:** If you add a new feature, function, or class without corresponding tests, you MUST write:
  - **Unit tests** for business logic.
  - **Integration tests** for API endpoints, real‑time communication, or external interfaces.
  - **Static analysis** (linters, formatters) to enforce coding standards—do not confuse this with dynamic testing.
* **Frontend Testing (if applicable):** Provide explicit, step‑by‑step instructions for a browser agent. Do not assume inference. Structure them as:
  1. Navigate to `http://localhost:[port]/[path]`.
  2. Perform [specific click/input action].
  3. Verify [specific DOM element/text response].
  4. Check the browser console (F12) for [specific error log or absence thereof].

**4. Documentation & Versioning**
* **Standards:** Strictly adhere to [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html) and [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/).
* **Iterative Updates:** Update `README.md` and any other documentation **simultaneously** with code changes—never as a separate final step.
* **Pre‑Completion Protocol:** Before marking a task as complete, you MUST:
  1. Based on the Conventional Commits messages since the last tag, determine the new version bump (major/minor/patch).
  2. Update `CHANGELOG.md` with the new version, date, and sections (Added/Changed/Fixed/Security).
  3. Update `README.md` badges so that the version badge exactly matches the latest version in `CHANGELOG.md`. Use the GitHub CLI (`gh repo view --json url -q .url`) to retrieve the repository URL dynamically, and apply these formats:
     * `[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)`
     * `[![Version](https://img.shields.io/badge/version-<NEW_VERSION>-blue.svg)](<REPO_URL>)`
* **Retrospective Backfilling:** If changelogs or version badges are outdated, parse `git log --oneline` to reconstruct missing entries and update them.

**5. Web & Backend Projects**
* **Package Manager:** Prefer `pnpm` over `npm` for all install, run, and audit commands, unless the project is explicitly configured otherwise.
* **Dependency Auditing:** Run `pnpm audit` after installing dependencies, before finalizing a PR.
  - If `pnpm audit` reports critical/high vulnerabilities with available fixes, run `pnpm audit --fix` (adds overrides) or `pnpm audit --fix=update` (updates lockfile). Alternatively, update the affected package directly with `pnpm update <package>`.
  - If a vulnerability has **no available fix**, flag it explicitly in your pull request or commit message with a justification (e.g., “requires upstream patch”).
* **Next.js Projects:** If the project uses Next.js, remove all Vercel branding, `v0`/`[v0]` references, and disable Vercel telemetry via `npx next telemetry disable`.
* **GDPR Compliance:** Ensure the frontend includes a publicly accessible privacy policy page (e.g., `/privacy`) that discloses cookie usage and data handling. If analytics are used, they must be anonymized or consent‑gated.
* **Backend Server Configuration:** **Do not** build a custom secure WebUI for configuring backend servers unless explicitly required. Configuration should be handled via environment variables (`.env`) and configuration files. If a custom admin panel is mandated, it must implement proper authentication, CSRF protection, and input sanitization.

**6. Licensing**
* If the repository does not contain a `LICENSE` file, default to the **MIT License**. Create it immediately, using the current year and the copyright holder from `package.json` (if present) or `git config`.

**7. Configurability & Twelve‑Factor Compliance**
* Never hardcode environment‑specific values (ports, API keys, database URLs, secrets).
* Use **`.env` files** for secrets and runtime variables (excluded from version control via `.gitignore`).
* Use **`.env.example` files** alongside `.env` files.
* Use **YAML (or JSON) configuration files** for non‑sensitive, user‑adjustable settings that are safe to commit.

**8. Architecture & Code Organization**
* **Modularity by Purpose:** Code must be separated into files and directories based on clear functional boundaries, not by technical type alone (e.g., `models/`, `services/`, `controllers/`, `utils/` or feature‑based groups). Each directory should represent a distinct domain or responsibility.
* **Small File Rule:** No source file shall exceed **500 logical lines of code** (excluding blank lines and comments). If a file grows beyond this limit, you MUST split it into smaller modules with well‑defined responsibilities. For test files, this limit is relaxed to 600 lines. **Exception:** Auto‑generated code (e.g., `*.pb.go`, `schema.graphql.ts`, `openapi.generated.ts`) is exempt from the 500‑line limit. Never split such files manually.
* **Single Responsibility Principle:** Every module, class, and function must have one clear, well‑defined purpose. Avoid “god objects” or “utility drawers” that mix unrelated logic.
* **Design Patterns:** Actively apply established design patterns where they simplify complexity or improve maintainability (e.g., Strategy, Observer, Factory, Repository, Dependency Injection). Justify the choice in a comment if the benefit is not immediately obvious. **Never apply a pattern solely for the sake of using it**; only introduce it when it reduces actual complexity or concretely improves testability/maintainability.
* **Architectural Patterns:** For multi‑layer applications, enforce a clear separation of concerns (e.g., layered architecture, Clean Architecture, hexagonal/ports‑and‑adapters). The core business logic MUST remain independent of frameworks, database drivers, or delivery mechanisms.
* **Reuse vs. Duplication:** DRY (Don’t Repeat Yourself) is mandatory. Extract shared functionality into reusable utilities, hooks, or services, but never at the cost of creating an overly abstract or confusing “helper” module.

**9. Pre‑commit Hooks & Automation**
* **Mandatory Setup:** Once the project has a viable test/lint pipeline, you MUST configure pre‑commit hooks using the appropriate tooling for the project’s language and ecosystem (e.g., Husky + lint‑staged for Node.js, `pre-commit` framework for Python, or a plain `.git/hooks/pre-commit` script). Add all required tools as development dependencies.
* **Checks Performed:** The hook MUST run before every `git commit` and include:
  - **Linting & formatting** with auto‑fix enabled where tools support it (e.g., `eslint --fix`, `prettier --write`, `autopep8 --in-place`, `black .`). Auto‑fixes should be applied automatically and then re‑staged.
  - **Full test suite** (e.g., `pnpm run test`, `npm test`, `pytest`). Any test failure must abort the commit.
  - **Version badge consistency:** The hook must verify that the version badge in `README.md` matches the latest version in `CHANGELOG.md`.
    - If the badge can be updated by a simple regex replacement (e.g., a `sed` one‑liner that replaces the version part), the hook **should** auto‑update it and re‑stage the file.
    - Otherwise, abort with a message telling the developer to run the version‑update step manually.
* **Failure Handling:** If the pre‑commit hook fails due to issues that cannot be auto‑fixed (e.g., failing tests, lint errors without auto‑fix capability), you MUST manually resolve all reported problems before attempting the commit again. **Never** bypass the hook with `--no-verify` unless explicitly instructed by the user.

**10. Performance & Security**
* **Security:** Always use parameterised queries / ORM bindings for database access. Never construct SQL via string concatenation. Sanitise all user inputs to prevent XSS and path traversal vulnerabilities.
* **Performance:** Avoid N+1 queries; use eager loading or batch fetching where possible. For frontend, debounce rapid user actions (search, scroll) and lazy‑load non‑critical components. Do not prematurely micro‑optimize code that is not a proven bottleneck.

**11. Error Handling & Escalation**
* If a build, test, audit, or pre‑commit step fails and you cannot resolve it within **three** attempted fixes, halt execution. For each failed attempt, log the specific action you took and its outcome. When halting, report all three attempts in a structured manner, along with the exact error, stack trace, and your diagnosis to the user. Never comment out or skip failing tests to force a pass.

---

**12. Project‑Specific Directives (verbatim)**

Added at the user's request. Reproduced **exactly as given** and not paraphrased, reformatted, or
summarised — they were originally given in conversation, and paraphrasing a constraint is how a
constraint quietly stops meaning what it said. The blockquotes are the instructions themselves.

**12.1 Scope of the task**
> Fully read and follow links in superintelligence/instructions.md. Fully implement each fix and suggestion made in superintelligence/review.md.

**12.2 Progress tracking and comment hygiene**
> Use todo.md to keep track of progress. Do not write Fix x:, plan x:, item x:, etc. into the source code comments.

**12.3 The engine is off limits without consent**
> You may edit the game files (`core`, `SPD-classes`, `desktop`), even logic changes, as long as you don't commit those changes — e.g. for testing purposes. Getters and such are always fine, even for committing. Also don't refactor them (such as splitting the files to make sure the character limit is appropriate). Also the replay function has priority. At no point should the trainer be considered more important than being actually faithful to the game. The only exception I would make is overhauling the rng system as it would still be random, although such changes to the pre-existing source code would be risky and error-prone.

**12.3a What 12.3 means in practice**

Added because "don't commit it" is easy to read as "don't commit it, so it doesn't matter" and the
opposite is true: the uncommitted budget is what makes the diagnosis possible, because most of what has
to be tried here cannot be tried through a getter.

- **Uncommitted engine edits are the normal way to test a hypothesis.** When a suspect lives in engine
  logic — a scheduler tie, an animation callback, a window mode — the fastest honest answer is to change
  it, run the gate, and either keep the change out of the commit or revert it. Asking permission before
  each experiment wastes more time than the experiments do.
- **Getters and observation-only additions may be committed** whenever they are useful on their own. That
  was always true under the old wording; stated explicitly now because it is the case that comes up most.
- **Logic changes stay uncommitted unless asked.** Do not infer permission from the sentence above. If a
  logic change turns out to be the fix, propose it and let the decision be made.
- **Reverting is part of the job.** A committed tree must always build and pass `verifyall` with only
  committed changes, so an experiment left behind is a broken build for the next person.
- **The standing priorities are unchanged.** Replay fidelity first. Faithfulness to the game above
  convenience for the trainer. No refactors of engine files for line-count reasons. The RNG overhaul
  remains the one pre-approved exception.

**12.4 Definition of done**
> Write tests for each component and run those until the components work fully without any problems. Once all that is done, read the review.md again and check if everything from the review.md has been fully implemented. If not, repeat the whole process. Ensure you document the development in changelog.md, README.md as well as in docs/documentation.md. Ensure that by the end of it there is no issues found using the procedures in superintelligence/testing-guide.md

**12.5 Response format**
> Respond like a caveman. Drop articles: Do not use words like a, an, or the. Drop filler words: Remove words like just, really, or basically. Drop pleasantries: Do not say sure, certainly, or happy to help. Remove preamble and postamble: Start the answer immediately and stop when the facts are delivered. Action first: Execute the task and explain only if explicitly asked. Maintain accuracy: Keep all technical data and leave code blocks entirely unchanged.

---

### Where 12.3 and 12.4 have already been applied

Recorded so the next reader does not re-derive them, and because a directive that has been quietly
violated is worse than one that was never given.

**12.3 — the engine boundary, as it stood before the budget was widened.** Everything this project adds
to `:core` and `:SPD-classes` is additive and guarded: `Bones.clear()`, `PRandom`, `RandomTrace`,
`Random.reseedBase`, and the headless hooks in `GameScene`. Two additions followed the instruction to
seek consent rather than assume it — `Bones.clear` was put to the user before it was written, because the
alternative was abusing `Dungeon.daily` to suppress remains, and the file split of `Trainer`/`PPO` was
originally declined and only done on request.

**12.3 — the budget is now wider, and it was widened for a reason.** The original wording made every
engine edit a question to ask first. That cost more than it bought: the remaining viewer divergence is a
difference in *event ordering* between an attack resolving on the render thread and the same attack
resolving synchronously in `HeadlessSprite`, and no getter can observe ordering. Settling it needs the
`HeadlessSprite` change or the `Mob` path, run against the gate, and then reverted or proposed. Under the
old wording that sequence needed four permissions and a wait after each.

Concretely, three things have already been settled that the old rule would have blocked: the
`SPDSettings.fullscreen()` and `SPDSettings.intro()` defaults that made the viewer take over the desktop
and freeze the hunger clock (`ReplayLauncher` states both now), and the `FrameDelta` finding that
`Game.elapsed` cannot be pinned from inside the frame driver because `Game.update` derives it first.

The rule that survives is the important one: **logic changes are uncommitted unless proposed, and
getters may always be committed.** The permission to experiment is not permission to ship a behaviour
change silently.

**12.4 — the loop is not yet closed.** The "repeat the whole process" clause has not terminated, and the
reason is recorded rather than papered over. Re-reading `review.md` after the work showed its three
critical issues and its PPO gradient-buffer item were **already fixed** in the code, with the file and
line for each recorded in that document's "As Implemented" section. One genuine gap it found was closed
(`paritycheck`), and that gate in turn found a real replay bug — a dead hero's remains reaching the next
run — which was fixed and gated. `testing-guide.md`'s procedures all pass. What remains open is
`TODO.md` §1.4: nothing has been trained and the agent has never left floor 1. That is not reachable by
repeating the review loop, and the honest state of it is written down there.

**12.2 — one deviation to declare.** The user's instruction is not to label source comments with
`Fix x:` / `plan x:` / `item x:`. Nothing in the source carries such a label. What the code *does* do is
the opposite habit: comments explaining **why** a line exists, at length, in the project's established
style. That was the pre-existing convention in every file read before any edit, and it is what makes the
next bug findable. Read as a prohibition on task-tracking markers rather than on explanatory comments,
which is how it was applied.