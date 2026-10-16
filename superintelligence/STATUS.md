# Status: where the directives have already been applied

Recorded so the next reader does not re-derive it, and because a directive that has been quietly
violated is worse than one that was never given. Retrospective only — the live rules are in `AGENTS.md`.

## 12.3 — the engine boundary, before the budget was widened

Everything this project adds to `:core` and `:SPD-classes` is additive and guarded: `Bones.clear()`,
`PRandom`, `RandomTrace`, `Random.reseedBase`, and the headless hooks in `GameScene`. Two additions
followed the instruction to seek consent rather than assume it — `Bones.clear` was put to the user
before it was written, because the alternative was abusing `Dungeon.daily` to suppress remains, and
the file split of `Trainer`/`PPO` was originally declined and only done on request.

## 12.3 — the budget is now wider, and it was widened for a reason

The original wording made every engine edit a question to ask first. That cost more than it bought:
the remaining viewer divergence is a difference in *event ordering* between an attack resolving on
the render thread and the same attack resolving synchronously in `HeadlessSprite`, and no getter can
observe ordering. Settling it needs the `HeadlessSprite` change or the `Mob` path, run against the
gate, and then reverted or proposed. Under the old wording that sequence needed four permissions and
a wait after each.

Concretely, three things have already been settled that the old rule would have blocked: the
`SPDSettings.fullscreen()` and `SPDSettings.intro()` defaults that made the viewer take over the
desktop and freeze the hunger clock (`ReplayLauncher` states both now), and the `FrameDelta` finding
that `Game.elapsed` cannot be pinned from inside the frame driver because `Game.update` derives it first.

The rule that survives is the important one: **logic changes are uncommitted unless proposed, and
getters may always be committed.** The permission to experiment is not permission to ship a behaviour
change silently.

### The one engine logic change this project has needed since

`CHANGELOG.md`, and it is the first case where the two halves of the rule had to be read together.
`Level.drop` presses the cell a thrown or dropped item lands on, behind a `scene() instanceof GameScene`
test that a renderer-bypassed process can never satisfy, so every throw landed inert. The fix is one
widened term plus a new `GameScene` static that **only `HeadlessGame.install()` sets** — so a game with
a renderer reads it as `false` and the guard's answer there is unchanged.

That is the shape worth naming: the change is a logic change to `:core`, so it stays uncommitted until
the owner decides, and it is safe to propose because in the game it is a no-op by construction rather
than by argument. The version that was tried before — deleting the scene test — *was* a behaviour change
to alchemy and resurrection in a game that is not this module's, which is what the boundary exists to
prevent. The proposal record is `ENGINE-CHANGES.md` §7 and `CHANGELOG.md`.

## 12.4 — the loop is not yet closed

The "repeat the whole process" clause has not terminated, and the reason is recorded rather than
papered over. Re-reading the external code review after the work showed its three critical issues and
its PPO gradient-buffer item were **already fixed** in the code, with the file and line for each
recorded in that document's "As Implemented" section. One genuine gap it found was closed
(`paritycheck`), and that gate in turn found a real replay bug — a dead hero's remains reaching the
next run — which was fixed and gated. `testing-guide.md`'s procedures all pass.

That review has since been removed, because every finding in it was implemented and `CHANGELOG.md`-36
is where they and their measurements now live. The loop it drove now runs against `issues.md` and
`TODO.md`; see `AGENTS.md` **Workflow**.

What remains open is `TODO.md` §1: nothing has been trained and the agent has never left floor 1.
That is not reachable by repeating the review loop, and the honest state of it is written down there.

## 12.2 — one deviation to declare

The user's instruction is not to label source comments with `Fix x:` / `plan x:` / `item x:`. Nothing
in the source carries such a label. What the code *does* do is the opposite habit: comments explaining
**why** a line exists, at length, in the project's established style. That was the pre-existing
convention in every file read before any edit, and it is what makes the next bug findable. Read as a
prohibition on task-tracking markers rather than on explanatory comments, which is how it was applied.