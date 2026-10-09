# Desktop replay viewer — plan

Goal: watch a recorded run play back in the rendered game, the way you would watch a recording of
your own game. Not a text log. The actual dungeon, rendered, stepping through the recorded actions.

Status: **built and running.** `gradle :desktop:replay --args="--file <replay>"` opens the game and
plays the recording back. See §9 for the baseline and §11 for what is untested.

---

## 1. What already exists

The record and re-execute halves are done and verified.

| Piece | Where | State |
| --- | --- | --- |
| Format + IO | `superintelligence/replay/Replay.java`, `ReplayIO.java` | v1, magic `SPD-REPLAY` |
| Recorder | `replay/ReplayRecorder.java` | written |
| Headless re-execution | `ReplayIO.verify()` | verified 4/4 fresh processes, 499 steps |
| CLI | `Main verify <file>` | working |

What a step holds:

```java
String action;   // Action enum name
int    slot;     // secondary index (slot or target)
String mode;     // EnvMode at time of record
int    heroPos;  // where hero ended up
double reward;
```

Plus per-run: seed text, hero class, challenges, score, depth, turns, generation, turnLimitPerFloor.

**The critical property: a replay is just (seed, hero, action list).** Everything else is a
cross-check. That makes playback straightforward — re-create the run, then feed the actions.

---

## 2. Why it can be built at all

`ReplayIO.verify()` already re-executes a recording headlessly and gets an exact match:

```java
env.reset( replay.seedText, heroClass );
for (step : replay.steps){
    env.step( Action.valueOf( step.action ), step.slot );
    if (env.heroPosition() != step.heroPos) -> diverged
}
```

So the engine reliably follows recorded actions. **The viewer does not need new replay logic — it
needs a renderer attached to that loop.**

The gap is that `env.step()` is designed to be non-visual (headless: no scene, dummy sprites). The
rendered game needs a live `GameScene` driving its own update loop.

---

## 3. The core design problem

Two ways to build this, and the choice matters a lot.

### Option A — Playback inside the real game

Set the custom seed, load the replay's actions, let the real `GameScene` run normally, and inject
each recorded action when the hero is ready.

- **Pro:** it is the real game. Real sprites, real animation, real input handling. Anything that
  looks wrong on screen looks wrong because it *is* wrong.
- **Con:** input is injected through the game's controller path. Must find the same entry point a
  keyboard would use, and suppress player input while playing.
- **This is the honest viewer.** It shows the game as played, not as re-simulated.

### Option B — Render the headless env

Run `SPDEnv` with the headless services, attach real sprites, draw.

- **Pro:** total control over playback; scrubbing, speed, frame-step all easy.
- **Con:** the whole point of the headless path is that it *bypasses* the renderer. Making it render
  means undoing `HeadlessServices`, and the sprite-attachment work there (`attachSprites`,
  `attachBlobEmitters`) already exists as evidence of how many assumptions are baked in.
- **Higher risk, better payoff for the controls.**

### Decision

**Option A first.** It is less code, less risk, and it is what "watch the replay" actually means.
Option B is a follow-up if scrubbing and speed control turn out to matter more than fidelity.

If A stalls on input injection, that finding is what should decide between the two — not preference.

---

## 4. Architecture, Option A

### 4.1 Where the code lives

`desktop` module, new package. Reasons:

- `desktop` already owns the LWJGL3 backend, the window, and `gdx-controllers`.
- `superintelligence` must stay headless — it runs in trainer worker JVMs with no display and no
  GPU. Adding libGDX scene code there would be wrong.
- `core` must not grow a feature that only the desktop viewer uses.

New: `desktop/src/main/java/.../desktop/replay/`

```
ReplayLauncher.java     entry point: gradle :desktop:replay --args="--file run.replay"
ReplayPlayer.java       playback state machine
ReplayOverlay.java      HUD: step counter, seed, score, play/pause, speed
```

`ReplayLauncher` needs `Replay`, `ReplayIO` from `superintelligence`, so **`desktop/build.gradle`
gains `implementation project(':superintelligence')`**. That is a real dependency change — flagging
it because it pulls the RL module into the shipped desktop build.

Avoid if possible: the format is a simple text format, so `ReplayReader` could be duplicated into
`desktop`. Duplication is worse than the dependency. Prefer the dependency and note the size cost.

### 4.2 Launching into a seeded game

The game already supports a locked seed via `SPDSettings.customSeed()` — that is the same path the
headless trainer uses. So:

```java
SPDSettings.customSeed( replay.seedText );
Dungeon.initSeed();
Dungeon.init();
```

Then start a `GameScene` as usual.

**Two known hazards:**

1. `SPDSettings` reads the custom seed with a **20 character cap**, and
   `GameSettings.getString(key, def, maxLength)` silently discards anything longer. The headless
   side now *verifies* the round trip and fails loudly (`LevelPipeline.requireSeedSticks`). The
   viewer needs the same check, or it will silently play a different run than the recording.
2. `LevelPipeline.startRun` does real setup the viewer would have to duplicate (`GamesInProgress`,
   sprite attachment, `Dungeon.generatedLevels.clear()`). Worth reusing rather than reimplementing —
   but it lives in `superintelligence`, so this is another argument for the dependency.

### 4.3 The playback loop

State machine:

```
IDLE -> PLAYING -> PAUSED -> (step/speed) -> ENDED
```

Per update tick, when `Dungeon.hero.ready()` and a recorded action is pending:
- take the next step
- deliver `step.action` + `step.slot` through the game's own input path
- suppress player input while `PLAYING`
- compare `Dungeon.hero.pos` to `step.heroPos`; mismatch → flag divergence, surface in HUD

The `ready()` check is the key: it is the same signal the headless `LevelPipeline.runToHeroReady`
uses, so the viewer advances exactly when the engine is ready to accept input. That is what keeps
playback in step with the simulation instead of racing it.

**Slowdown:** the game runs at render speed. A recorded run of 1500 turns would replay in seconds.
Need a minimum interval per step (e.g. 60ms ≈ 16 steps/s) and a speed multiplier, otherwise the
viewer is a blur.

### 4.4 Input delivery — SPIKE DONE, and the answer is better than expected

The risky assumption is resolved. **No input synthesis is needed, and the seam already exists.**

Traced the real input path in the game:

```
CellSelector.onSignal(KeyEvent)     CellSelector.java:277
  -> heldAction1/2/3                    CellSelector.java:250-252
  -> directionFromAction(GameAction)    CellSelector.java:426   N/NE/E/... -> Point
  -> Dungeon.hero.handle( cell )        CellSelector.java:415
  -> Dungeon.hero.next()                CellSelector.java:416
```

`Hero.handle(int cell)` is at `actors/hero/Hero.java:1920`.

So the injection point is **`Hero.handle(cell)` + `Hero.next()`** — the exact call the game's own
cell selector makes after resolving a keypress into a direction.

Better still: **`ActionMapper` already wraps it.** `env/ActionMapper.java:274-275` does
`hero.handle(cell); hero.next();`, and its own header comment says:

> Nothing here goes through input handling. Movement is expressed as a cell and handed to
> `Hero.handle`, which is what the game's own cell selector calls, so all of the game's action
> resolution — attack versus loot versus unlock versus stairs — applies unchanged.

That is the viewer, already written, minus the rendering. `ActionMapper.apply(Action, int)` and
`applySecondary(EnvMode, Action, int)` are exactly the two calls a viewer needs per recorded step.

**Consequences for the design:**

- No key/controller synthesis. No `InputEvent` juggling.
- All action resolution (attack vs loot vs stairs vs menu) is inherited for free — the same reason
  the headless path is faithful.
- `ActionMapper` lives in `superintelligence`, so reusing it makes the `:superintelligence`
  dependency from §4.1 mandatory rather than optional. That settles the duplication question:
  depend on the module.

What still needs care:

- `ActionMapper` is built against an `SPDEnv`, which owns its own `Dungeon.init` sequence. The
  viewer needs a mapper over a *rendered* game. Check whether `ActionMapper`'s constructor or its
  fields assume ownership of the env (`LevelPipeline`, masks, `attachSprites`). This is the one
  remaining unknown, and it is small — the class mostly converts indices to cells and calls the hero.
- Player input must be suppressed while playing, so a stray keypress does not desync the replay.
  Easiest reliable way: ignore input in the viewer scene rather than trying to neutralise the
  selector.
- The 20-character seed cap still applies (§4.2). Reuse `requireSeedSticks`'s check.
- Playback speed. The game runs at render speed, so without a minimum interval per step a 1500-step
  replay flashes past in under a second.

---

## 5. HUD

Minimal, because it is a diagnostic:

- step N of M
- seed text, hero class, recorded vs live score
- depth, turns
- divergence warning with the step number
- play/pause, speed, restart, quit

`Ansi` is for the console trainer, not this. HUD is noosa scene components.

---

## 6. Verification plan

The point of building this is to rule out problems. So: what does it catch?

1. **Divergence the headless path cannot show.** If a replay re-executes headlessly but renders
   wrong, something in the visual layer disagrees with the simulation. That class of bug is
   invisible to `verify()`.
2. **Sprite and effect crashes.** The headless path has now surfaced three real crashes this way
   (blob emitters, `cellSelector`, `spellSprite`). Rendering exercises strictly more of that code.
3. **Animation and timing.** Headless has no animation timing at all.
4. **A replay a human can sanity-check.** "The hero walked into the rats on turn 3" is the kind of
   check that catches a subtly wrong recording that still passes an exact-match assert.

Gates:

- `gradle :desktop:replay --args="--file <known-good>"` completes with zero divergence
- plays back recordings from at least two seeds and both a short and a long run
- HUD step counter matches `Replay.steps.size()`
- **existing suites still pass**: `:superintelligence:gradcheck`, `build`, determinism sweep

---

## 7. Risks

| Risk | Severity | Note |
| --- | --- | --- |
| Input injection has no clean seam | **blocking** | Spike first. Decides A vs B. |
| `:superintelligence` dependency bloats desktop build | medium | Reuse over duplication; measure the delta. |
| Replay vs live game diverge for non-simulation reasons | medium | Version skew, mods, settings. Ship a version stamp in the HUD. |
| Playback too fast to read | low | Speed control; not a correctness issue. |
| Rendered game mutates save state | medium | Suppress saving, or replay could clobber a real save file. |
| Assumed clean input path turns out to be tangled | medium | The spike's real output. Budget for it. |

---

## 8. Ordering

Step 1 is done and it came back positive (§4.4), which removes the fork between Option A and B.

1. ~~**Spike: can `GameScene` accept an injected action?**~~ **Done** — yes, via
   `Hero.handle`/`Hero.next`, already wrapped by `ActionMapper`. No input synthesis needed.
2. **`desktop` depends on `:superintelligence`.** Reuse `Replay`, `ReplayIO`, `ActionMapper` rather
   than duplicating them. Check `ActionMapper`'s constructor for assumptions about owning the env —
   that is the last real unknown, and it is small.
3. Seed lock + launch into a seeded game, reusing the 20-char cap check.
4. Playback loop: `ActionMapper.apply` / `applySecondary` per recorded step, position cross-check
   against `step.heroPos`, `turn_limit` honoured.
5. Suppress player input while playing.
6. HUD.
7. Speed control, scrub, restart.
8. (separate) Split replay transmission out of the trainer's episode channel.

---

## 9. Baseline established — recordings that reproduce

So a viewer test failure is unambiguously the viewer's, four known-good recordings were generated
and each verified 3× in fresh JVMs. All exact.

| Recording | Hero | Max turns | Steps | File size | Verify ×3 |
| --- | --- | --- | --- | --- | --- |
| `rp-short` | WARRIOR | 60 | 2 | 0.21 KB | 2, 2, 2 |
| `rp-long` | HUNTRESS | 600 | 599 | 26.0 KB | 599, 599, 599 |
| `rp-trunc` | ROGUE | 40 | 6 | 0.38 KB | 6, 6, 6 |
| `rp-death` | DUELIST | 1500 | 1499 | 64.9 KB | 1499, 1499, 1499 |

Format is plain text, one header line per run field then one line per step:

```
SPD-REPLAY
version=1
seed=rp-trunc
hero=ROGUE
challenges=0
generation=0
score=-4.012000000569969
depth=1
turns=6
turn_limit=40
steps=6
MOVE_NW 0 WORLD 756 -0.0020000000949949026
INTERACT 0 WORLD 723 -0.0020000000949949026
MOVE_E 0 WORLD 724 0.9980000257492065
```

Two things worth noting from the generated set:

- **`turn_limit` is stored per recording** and `verify` applies it. The viewer must too, or a
  truncated recording replays past the point the original stopped.
- **`rp-death` did not actually die** — it hit the turn limit at 1499. With the scripted policy,
  deaths are rare. If a real death case matters for viewer testing, it needs a seed found by
  sweeping rather than one guessed.

If any future recording fails to reproduce 3/3, the viewer is the wrong thing to debug next — the
recorder or determinism is.

---

## 10. What was built

Four classes in `desktop/src/main/java/.../desktop/replay/`:

| Class | Role |
| --- | --- |
| `ReplayLauncher` | entry point, reads the file, seeds the run, opens the window |
| `ReplayPlayer` | playback state machine: applies steps, settles turns, checks positions |
| `ReplayPlayback` | cursor and divergence state. **No engine dependency** |
| `ReplayController` | per-frame pump and HUD |

Plus a `gradle :desktop:replay` task, and `probeClasspath` on `desktop`.

### Two engine hooks were needed

Both are small and general, not replay-specific in behaviour:

- `Game.lockCellInput(boolean)` — disables the cell selector every frame so nothing but the
  recording can inject an action. `GameScene.update()` already called
  `cellSelector.enable(Dungeon.hero.ready)` each frame; the lock gates that call.
- `Game.setSceneClass(Class)` — the field is `protected`, and an entry point that builds the game
  itself has no other way to choose the initial scene.

### Why a controller and not a scene subclass

The plan assumed subclassing `GameScene`. That does not work: `InterlevelScene` switches to
`GameScene.class` **hardcoded**, so a subclass is never entered. The viewer enters through
`InterlevelScene` with `Mode.DESCEND` and no hero — exactly how a first floor is built in normal play
— and a static `frameDriver` hook on `GameScene` pumps playback each frame. The recording is
therefore watched in the real scene, real sprites, real update loop.

### Launch ordering, which cost three bugs

1. `SPDSettings.customSeed()` writes through libGDX preferences, and `Gdx.app` does not exist until
   the game is running. The window config must be built first so preferences exist, *then* the seed
   applied, *then* the application started.
2. `Game.version` must be set before the first scene builds — `DeviceCompat.isDebug()` reads it and
   `InterlevelScene.create()` calls that.
3. `PixelScene.uiCamera` is null until `PixelScene.create()`, which `GameScene.create()` skips when
   no level exists yet, so the HUD must tolerate a null camera on early frames.

### Verified

- `ReplayPlayback` is the only class with no engine dependency, so it is the only one testable
  without a window. **36 checks pass** across all four recordings plus synthetic cases: cursor
  movement, completion, unknown hero class fallback, and that divergence is recorded once and never
  cleared by a later match.
- Window opens, render loop is live (CPU climbing, memory growing, 58 threads), no errors over 28s.
- Suites still pass: `:desktop:build`, `:superintelligence:build`, `:core:build`,
  `:SPD-classes:build`, `gradcheck`, and a 60-rollout determinism sweep (20 seed/hero pairs, all
  identical).

---

## 11. Not tested, and one real gap

**Resolved: recordings now exercise the secondary action path.** Section 11 originally recorded that
every step in every recording was `WORLD`, leaving `applySecondary` for MENU / SLOT / INVENTORY /
TARGETING written but never executed. Chasing that down found six real bugs, not just a missing
fixture, and `WORLD`-only recordings were hiding all of them:

| Bug | Effect |
| --- | --- |
| `EnvMode.INVENTORY` was never assigned anywhere | The mode was structurally unreachable, and `OPEN_INVENTORY` silently fell through to the generic cell handling as a no-op. No rollout could contain it and no replay could exercise it. |
| `OPEN_INVENTORY` was absent from the `WORLD` action mask | Even after wiring the mode, the action was unreachable because the mask never offered it. |
| `SlotAction.use` checked equipability first | `Weapon extends EquipableItem`, so a `ThrowingStone` is equipable: the stone was **equipped instead of thrown**. |
| `SlotAction.use` returned `toggleEquip`'s boolean | That "equipped OK" flag was read as "needs an aim", so every equipment change invented a `TARGETING` step. |
| `SlotAction.use` returned `true` on its success path | Every plain consumable falsely demanded an aim. |
| `toggleEquip` returned without `hero.next()` on a refused equip | The hero stayed mid-action forever, the pipeline never saw it become ready, and the episode ended `STALLED` within about a dozen turns. |
| `SPDEnv.step` cleared the pending use item on *every* step | Including the `TARGETING` step that needed it, so no aim could ever be resolved and cancelling a throw was impossible. |

`ScriptedPolicy` was updated alongside them: it now deliberately spends its first turns on the routes
into the secondary modes, remembers transition cells that did nothing (the quest-locked floor 1 exit
was making every episode ping-pong on the stairs), refuses to walk straight back where it came, and
tramples high grass before trying to collect from it.

`loot-a` now opens with `WORLD → SLOT → TARGETING → WORLD → INVENTORY → TARGETING`, verifies 3/3, and
is byte-identical across fresh JVMs. A six-seed determinism sweep still matches exactly.

**`MENU` is still uncovered.** It needs a real `WndOptions` dialog, which movement cannot produce. The
reachable triggers are the unequip-third-ring prompt (`KindofMisc`) and the upgraded-missile break
warning (`MissileWeapon.doThrow`); both need specific loot first. Measured: **zero** of the seventeen
committed recordings contain a `MENU` step, so the mode is exercised by `modecheck` and by nothing
else.

The asymmetry that would have bitten it is fixed. `WindowBridge` read `GameScene.headlessWindow()`,
which `GameScene.show` fills only when there is no scene — so in the rendered viewer it was always
null and a recorded `MENU` step resolved against nothing, while `ReplayController.dismissUnanswerableWindow`
deliberately exempts `WndOptions` and would have left it on screen forever. `WindowBridge` now reads
`GameScene.answerableWindow()`, which prefers the live window.

**`TARGETING` is entered and now used.** The six bugs below are fixed and the recorded corpus exercises
the aim path: **156 `TARGETING` steps across the corpus, every one a real aim, none a `CANCEL`.**

## 12. Driving it by hand, and what that found

Everything above was found headlessly. Watching the viewer with a human on the keyboard found a
second, unrelated class of bug: every one of them made the viewer look *broken in a way no headless
test could detect*, because playback is driven by the frame hook rather than by input. A dead
control list changes nothing about the log, and a replay that keeps playing perfectly is exactly what
a bricked game also looks like from the outside.

| Symptom | Cause |
| --- | --- |
| The whole game was unresponsive; keybinds did nothing | Reaching a transition the hero cannot use raises an informational `WndMessage` ("you cannot leave the dungeon yet"). SPD windows are modal, so the scene stopped accepting input. |
| A keypress moved the hero and desynced the replay | `lockCellInput` called `cellSelector.enable(false)`, but `CellSelector`'s own `KeyEvent` listener ignored `enabled` and kept translating arrow keys into movement. |
| SPACE paused *and* waited the hero, desyncing the replay | `KeyBindings.getActionForKey` consults `hardBindings` **last**, so forcing SPACE to `GameAction.NONE` lost to the default SPACE→WAIT binding. "Hard" only ever applies to keys the player has not bound at all. |
| `R` restarted on floor 2 | `InterlevelScene.descend()` has two branches. With a hero still in place it takes the transition branch and descends; only a null hero makes it call `Dungeon.init()`, which resets depth to 1. |
| `R` did "return to floor 1" instead | `InterlevelScene.mode` is static and gameplay rewrites it, so by the time `R` was pressed it was whatever the recording last did. Both statics are now set explicitly. |
| Controls died after `R`, while playback continued | `InterlevelScene` calls `KeyEvent.clearListeners()` on the way out to drop its own continue-button listener - and that clears *every* listener, the viewer's included. |
| The HUD vanished after `R` | `pump()` rebuilt it while the outgoing scene was still live, so it was added to a scene about to be discarded, and the non-null field meant nothing ever rebuilt it. |
| Resuming a diverged replay printed the same failure forever | Playback restarted, immediately re-detected the same divergence and halted again. Divergence is now sticky. |

Two of these are the kind that only exist in a manual test. The modal window and the dead key
listener were both invisible to `ReplayPlayback`'s 36 headless checks, because neither involves the
playback cursor. The viewer only became usable once someone pressed keys at it.

The fixes worth keeping in mind:

- **The viewer dismisses windows it cannot answer.** A recording has no step for an informational
  message, so leaving it up blocks the game forever. `WndOptions` is deliberately exempt: that one a
  recorded `MENU` step does resolve, through `WindowBridge`, and dismissing it would break the exact
  case the viewer exists to show.
- **`KeyBindings` gained an explicit override layer** (`addOverride` / `clearOverrides`) rather than
  relying on hard bindings. Precedence in `getActionForKey` was left alone, because reordering it
  would break players who rebound `ENTER` or `ALT_RIGHT`, which the game hard-binds. Overrides are
  cleared in `uninstall()`, since they are process-wide and the viewer quits to the title screen.
- **The HUD is rebuilt whenever the live scene changes**, not once, which makes it self-healing.

`replay-viewer.bat` wraps this up for a human: `replay-viewer` plays the newest recording,
`--record <seed>` makes one, `--verify <file>` checks one headlessly, `--list` shows them. It drives
Gradle tasks rather than hand-building a classpath, and resolves every path to absolute first because
Gradle's `run` task uses the module directory as its working directory - a relative path fails there
with a misleading "file not found".

Two `for /f` traps cost real time and are worth writing down:

- `for /f "usebackq" ... in ('cmd')` runs **nothing** - with `usebackq`, single quotes mean a literal
  string and backticks mean a command. Without `usebackq`, single quotes run the command.
- Batch files need CRLF. LF-only files break `call :label` in ways that look like a missing label.

Still untested:

- **A dialog inside a recording.** `modecheck` proves the `MENU` path works end to end, and the two
  bugs that made it unreachable in a real run are fixed, but no *recorded* run contains `MENU` yet:
  it needs a real `WndOptions`, and eight seeds at 1501 turns each produced none. The scripted policy
  never reaches an NPC, a shop or an upgraded missile, which are the reachable triggers.
- **Whether a viewer session can leave a save behind.** A viewer run uses the real preferences path.
- **A death recording plays its last step.** Three recordings end in death (`warrior-death`,
  `huntress-mid`, `warrior-long`), each carrying `termination=DEATH` and a final step worth `-100.0`.
  `PlaybackCheck.checkADeathRunPlaysItsLastStep` asserts the cursor reaches the end; what it cannot see
  is whether the hero actually acts and dies on screen, which needs `viewcheck` and a display.
- **Visual fidelity.** The controls have been driven by a human; nobody has checked that the sprites
  and animation actually look right.
- **`PlatformSupport.getFont` throws "No cap character found in font."** The viewer logs hundreds of
  these per run. It is *not* a rendering problem: the exception is caught, logged and turned into a
  null return, and the base game renders fine - checked by running the vanilla launcher. An earlier
  note here claimed it "breaks every message window in the base game as well as the viewer"; that was
  inference from shared code paths, not observation, and it is wrong.

  What is actually known: on libGDX 1.14.0 `generateFont` sizes the face from `fontData.capChars`,
  and nothing in the GDX jar ever populates that array, so the sizing loop cannot run and `capHeight`
  stays at its `1f` sentinel. It is unsatisfiable from the caller - an explicit `characters` reaches
  the throw, and `null` dies earlier on `parameter.characters.toCharArray()`. The font itself is fine
  (936 glyphs, all 26 capitals). The line is upstream code from 2021 against a much newer libGDX, so
  this is dependency drift.

  It was left alone. Two changes were tried and reverted: a preloaded ASCII charset (no effect, same
  error count) and `characters = null` (removes this error, introduces a NullPointerException per
  text block). Guessing in shared rendering code is a poor trade for a warning that costs nothing.
  If it ever does need fixing, the route is changing `gdxVersion`, and only 1.14.0 is in the offline
  cache.

## 13. What the checks now cover

Two of these were verified by hand before anything automated existed, which is why the first batch of
bugs was invisible to a test suite that only ever walked around.

- `gradle :superintelligence:modecheck` drives an explicit script through every action mode and fails
  if any is unreachable. It resolves a real aim rather than cancelling one, and executes a drop.
- `gradle :superintelligence:restartcheck` rebuilds a run after several hundred turns of play and
  fails if floor 1 does not come back identical. This exists because restarting was suspected of
  diverging - the generator stack is churned by every mob turn and item roll since launch, and nothing
  guarantees it is back where it started. It is: the suspicion was wrong, and the check now keeps it
  honest.
- `replay-viewer.bat --verify <file>` re-runs a recording headlessly.

Episode length is the headline change from this round. Every rollout used to end `STALLED` after 8-13
turns, which made the environment worthless for collecting experience; five seeds that all stalled now
run the full 1501. The cause was `Hero.act()` being entered with a null `curAction`: it clears `ready`
and then dispatches on `curAction`, so no branch matches, and `Hero.ready()` is the only thing in the
game that sets `ready` back to true. A normal playthrough never gets there because the cell selector
re-prompts for input, and that prompt is what calls `ready()`. Headless there is no scene and no
selector, so nothing was left to re-prompt.



