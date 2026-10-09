# Plan: viewer fidelity — the step gate, and why the trainer is not what needs changing

**Status:** in progress. Written after the fullscreen and reproducibility work landed in
`e0c986c27`, and before the step-gate fix.

Supersedes nothing. `ISSUE-viewer-frame-drift.md` recorded this as open with an incorrect leading
cause; §7 below says which of its claims survived measurement.

---

## 1. Where this actually stands

The corpus verifies **clean headlessly, all 17**, and diverges in the **rendered viewer** for
**14 of 17**. Those 14 fail at identical steps across repeated sequential runs, which is the property
the open issue said did not exist.

The open issue's leading cause — that `GameScene.update` advances the world's actors on its own
schedule — is **wrong**. `GameScene.java:942` gates its scheduler thread on
`!Actor.manualScheduling`, and `ReplayPlayer.java:133` sets that flag, so the branch is dead during
playback. `Actor.headlessStep()` called from `driveToHeroReady()` is the only scheduler advance, and it
is the same function the trainer uses.

The `PENDING` yield is also not the fault. Its comment at `ReplayPlayer.java:373-380` is correct:
spinning inside the drain would starve the very animation callback being waited on.

## 2. The verified cause

Traced per frame with `WorldSnapshot`, on `warrior-mid`, rat `id=9`:

```
frame:68  mob9[sees]: N -> Y
frame:70  mob9[state]: Sleeping -> Hunting;  target: - -> Hero
          ... 31 frames in which nothing in the world changes ...
frame:105 hero hp 20/20 -> 19/20
frame:106 hero buffs: WarriorShield:0.0 -> 1.0
```

An attack is two events: *decide to swing*, then *the hit lands*. In the trainer they are adjacent.
In the viewer the second arrives ~0.33 s later, and the viewer plays a recorded step in between.

- `Mob.doAttack` (`Mob.java:797-809`) takes the visible-sprite branch: `sprite.attack(enemy.pos)` and
  `return false` — no damage, **no time spent**.
- The damage is in `Mob.onAttackComplete` (`Mob.java:811-817`): `attack(enemy)`, `spend(attackDelay())`,
  then `super.onAttackComplete()`.
- Reached from `CharSprite.onComplete(Animation)` (`CharSprite.java:871-875`), driven by
  `MovieClip.updateAnimation()` accumulating `Game.elapsed` (`MovieClip.java:62`) — wall clock.
- `HeadlessSprite.attack` (`HeadlessSprite.java:87-94`) calls `ch.onAttackComplete()` **synchronously**,
  so the trainer spends the mob's turn inside the same `headlessStep()` that decided to attack.

`Rat` is not special. It overrides only `act()` (for Ratmogrify) and two stat rolls; `doAttack`,
`onAttackComplete` and the AI states are all stock `Mob`. Every mob that attacks is affected the same
way, which is why the corpus failures are overwhelmingly HP deltas.

The `WarriorShield` buff appearing one frame after the damage confirms it was a combat hit rather than
a trap. The 31-frame gap is the attack animation: `RatSprite.attack` is 15 fps over 5 frames.

## 3. Why the trainer is not the thing to change

This is the part that reversed direction, so it is worth stating carefully.

The obvious reading is "the trainer resolves animations instantly, make it behave like the viewer". That
would be **making the trainer wrong**, and it is checkable:

**`GameScene.java:942`** gates the render thread's poke at the scheduler:

```java
if (!Actor.processing() && Dungeon.hero.isAlive() && !Actor.manualScheduling) {
    if (actorThread == null || !actorThread.isAlive()) {
        actorThread = new Thread() { public void run() { Actor.process(); } };
        ...
    } else if (notifyDelay <= 0f) {
        notifyDelay += 1/60f;
        synchronized (actorThread) { actorThread.notify(); }
    }
}
```

`Actor.processing()` returns `current != null` (`Actor.java:272-274`). So when a mob attacks,
`Mob.doAttack` returns `false` without spending (`Mob.java:817-829`), `current` stays set to that mob,
`Actor.process()` falls to `if (!doNext)` and parks in `Thread.wait()` at `Actor.java:423` — and the
render thread **stops poking it**, because the poke is gated on the exact condition that is now false.

**The real game parks the scheduler for the whole attack animation.** It does not spin, and it does not
re-run `Mob.act()`. The mob's turn is genuinely blocked until `CharSprite.onComplete` fires
`Mob.onAttackComplete`, which applies damage and calls `spend(attackDelay())` — after which `next()`
clears `current` and the poke resumes.

An earlier version of this document claimed the scheduler re-picks the mob and spins in place. **That
was wrong**, and it was wrong in the way that matters: the spin is not what the game does, so it is not
what the viewer has to match. Corrected on reading `GameScene.java:942`, which the original claim never
consulted.

What survives is the conclusion, and it survives for a stronger reason than the one originally given:

| | order |
|---|---|
| real game | decide → **park** → animation completes → damage → spend turn → next actor |
| trainer | decide → damage → spend turn → next actor |
| viewer, currently | decide → park, **but the hero may still act** → animation completes → damage → spend |

The park contains no state change and no `Mob.act()` re-run in either the game or the trainer. So both
produce the same ordering. The viewer differs not in the park but in what it is allowed to do **while
parked** — see §4a.

**Consequence:** teaching the trainer to wait would teach the agent a reordering the game does not have,
and would need a virtual clock in a loop that has none, plus a re-recorded corpus. The user raised
exactly this concern when the plan still pointed the other way, and the evidence settles it: **fix the
viewer.**

This also answers the standing question of whether the fault limits what the agent can do. It does not,
and it cannot, because nothing in the trainer changes.

## 4. The suspected mechanism

`ReplayPlayer.update` decides whether to apply a recorded step like this:

```java
//ReplayPlayer.java:337
if (!readyToAct() && driveToHeroReady() != Drain.READY){
    return;
}
applyNextStep();
```

`readyToAct()` (`ReplayPlayer.java:469-488`) asks three questions, all about the hero: is there a hero,
is it alive, is it not paralysed, is `curAction` null, is it `ready`. **If the hero answers yes, the
drain is skipped entirely** and the next recorded step is applied with zero scheduler advance.

That short-circuit is itself an earlier fix, and its comment is right:

> `driveToHeroReady()` always advances at least one scheduler step before it tests readiness, so using
> it as a per-frame gate advanced the game once for every frame rendered.

It stopped the over-advancement and introduced the opposite problem: the gate is bypassed on precisely
the frames where an actor's turn is unresolved. The hero reads as ready because `Hero.act()` calls
`ready()` whenever `curAction == null` (`Hero.java:870-877`), and it stays ready for the whole time a rat
is mid-swing.

Frame sequence, on the measured run:

1. Drain runs. The rat attacks, spends no time, so `headlessStep` reports `wantsMore`.
2. `Actor.now()` unchanged and `hero.curAction` unchanged → `blocked++` → `Drain.PENDING` at 3. No settle.
3. Frame ends. `super.update()` ticks the animation. Damage is still pending.
4. Next frame, `awaitingSettle` is false, so the gate is consulted. `readyToAct()` is **true**.
5. The drain never runs. The next recorded step is applied — the hero moves — while the rat's hit is
   still in the air.

### What is not yet proven

This is read from source, not measured. The chain in §2 is measured — the stall, the HP delta and the
shield are all observed. That this gate is *the* mechanism is a hypothesis with one competing
explanation that reading cannot settle: `Actor.now()` may already have advanced past the mob's turn
before the hero moves, in which case the ordering is lost inside the scheduler rather than at the gate.

Step 1 below exists to tell those apart.

## 4b. The gap the trace actually shows

Recorded because it is easy to conclude §4 from the stall frames and miss this.

On `warrior-mid`, frames 71-74:

```
71  applied=false drain=PENDING  current=Rat@13            animating=0
72  applied=true  drain=PENDING  current=WaterOfHealth@14  animating=0
73  applied=false drain=PENDING  current=Rat@7             animating=0
74  applied=false drain=PENDING  current=Rat@9             animating=1
```

**Frame 72: a recorded step was applied while `WaterOfHealth@14` held the scheduler.**

`Actor.processing()` was `true`, `animating` was `0`, and `applyNextStep()` ran anyway. The reason is in
`ReplayPlayer.readyToAct()` (`ReplayPlayer.java:469-488`), which asks three questions, all about the hero.
The hero was free, so the drain was skipped and a step was injected mid-turn.

That is the condition `GameScene.java:942` exists to prevent. In the real game, `Actor.processing()`
being true means the render thread does not poke the scheduler, and the scheduler is parked. The viewer
has no equivalent gate: it consults only the hero.

So the §4a refutation is narrower than it looked. It was scoped to frames 74-121 and tested for a step
applied *during an animation*. Frame 72 is a different case — no animation, an unrelated actor mid-turn —
and `animating=0` says so explicitly.

### Also unresolved: the redundant acts

While the rat's animation resolves, `driveToHeroReady` calls `headlessStep()` up to
`BLOCKED_STEPS = 3` times per frame (`ReplayPlayer.java:599`). Over the ~49 frames of the stall that is
~147 extra `Mob.act()` calls on the same mob, which the real game makes **zero** of while parked.

Nothing observed so far says these matter. `Char.act()` recomputes FOV, `Mob.act()` calls
`chooseEnemy()` (which builds a `PathFinder` distance map — deterministic) and `processSwarmIntel()`,
and `Hunting.act()` returns `doAttack()`. No RNG draw has been found in that path. But "no draw found by
reading" is not "no draw", and `Mob.chooseEnemy` is long. E3 measures it rather than assuming.

## 4a. Measurement: the animation gate is not the bug

Recorded because a plan that predicted the wrong mechanism, and was caught by measurement, is worth more
than one that was never questioned.

**Scoped to the stall.** What was tested was whether the viewer applies a step *while an animation is in
flight*. That it does not is established below. It is not the whole hypothesis — see §4b for the case
this measurement did not cover.

`gate` fields added to the frame record: `readyToAct()`, `Actor.currentActor()`, count of actors with an
animation in flight, and the drain outcome. Run on `warrior-mid`, 124 frames.

Frames 74-121, while Rat@9's attack resolves:

```
applied=false  drain=PENDING  ready=false  current=Rat@9  animating=1
```

**48 consecutive frames where the viewer applies nothing** and waits for the animation. The proposed fix
would have forced behaviour the code already exhibits. It was dropped rather than implemented, because
a change that cannot change an outcome is a cost with no benefit.

Two consequences:

- Blocking on `animationInFlight()` specifically is a **no-op**. `ready=false` throughout the stall, so
  the short-circuit never fires during it. That part of the change was dropped; the `Actor.current` part
  in §4b was not, and is a different condition.
- **§2's frame count was wrong.** The stall is **49 frames ≈ 0.82 s**, not 31 frames ≈ 0.52 s. And an
  animation that takes 2.5× its expected duration is not a separate mystery to be chased later — it is
  in the same causal chain as the divergence.

### The pin does not pin

`clock` fields added: what `Game.elapsed` and `Game.timeTotal` actually advanced by, against the pin.

With a pin of 0.0166666, `timeTotal` advanced **0.0070 per frame** — 42% of what was asked. Three
attempts, all measured:

| attempt | result |
|---|---|
| overwrite `Game.elapsed` | pins what `MovieClip` reads; `timeTotal` keeps real time. Clock split 42/58. |
| write `Game.timeScale` | applies to the *next* frame's delta, which is unobservable, so each ratio is derived from a delta the previous ratio already distorted. Observed oscillating between 0.001 and 0.14 per frame. |
| both | no better. |

The structural reason, and the reason this cannot be fixed from here: `Game.elapsed` is **derived**.
`Game.update` computes `elapsed = timeScale * frameDelta` and folds it into `timeTotal` at `Game.java:283-284`
*before* `scene.update()` runs. The frame driver is downstream of the value it would need to influence.

So `-Dspd.fixedDelta` pins **playback pacing only**, and says so at runtime rather than appearing to work.
Animation timing remains frame-rate dependent; making it otherwise needs an engine change at the point the
delta is measured, which is a separate decision and not one taken here.

The arithmetic closes: 49 frames × 0.0070 = 0.34 s, which is the attack animation's real duration. The
animation was completing correctly all along — the pin was simply dividing it across 49 frames instead of
20.

## 5. The change

Viewer-only. `ReplayPlayer` and `FrameDelta`. No engine logic, no `HeadlessSprite`, no trainer change,
no corpus re-recording, no replay format change.

### E1 - two-sided diff, no hypothesis

`WorldSnapshot` and `WorldDiff` already exist and were used per-frame on one recording, but a two-sided
diff has not been run on a recording that fails. It is the cheapest way to find out whether the cause is
the one 4b names.

1. `gradle :superintelligence:worldtrace -PworldArgs="<replay> <out>"` - headless, per step.
2. Viewer with `-Dspd.worldTrace=<out>` - per step, same labels.
3. `WorldDiff.compare` on the `step` records only.

Output: the first step where roster, cooldowns or `Actor.now()` differ, and the field. If it points
somewhere other than 4b, the change below is not the next move.

### E2 - the `processing()` gate

`ReplayPlayer.readyToAct()` additionally requires `Actor.currentActor() == null`.

This mirrors `GameScene.java:942`'s `!Actor.processing()`, which is what stops the real game from giving
the hero input while any actor holds the scheduler. Read through the getter already committed in
`e0c986c27`, so it is viewer-only and there is no engine edit to keep or revert.

`Actor.current` is set by `headlessStep` and cleared only by `next()`, which is what `onAttackComplete`
calls - so non-null means *an actor has taken a turn and has not completed it*.

**Bounded, or it can wedge.** Count consecutive frames the gate refuses. Past a limit, halt naming the
blocking actor rather than hanging: a gate that can wedge a playback forever is a worse failure than the
divergence being fixed.

Prediction: `warrior-mid` green if this is the cause. One recording must fail identically before and
after - a change that merely moves failures onto other recordings has fixed nothing.

### E3 - count the redundant acts

Instrument `driveToHeroReady` to count `headlessStep()` calls that re-select an actor already holding
`Actor.current`, and whether any of those `act()` calls consumes RNG (`RandomTrace.baseDraws()` delta
around the call).

Runs alongside E2, because it is what would explain an incomplete fix: if the redundant acts do draw,
the ~147 of them the real game never makes are a second fault.

### E4 - verify

Full corpus, twice, sequential (`VIEWCHECK_JOBS` defaults to 1, from `e0c986c27`).

Both membership **and** step numbers must match between runs.

### E5 - the survivors

Three failures have a different signature from the other eleven and may be separate causes:

- **`mage-alt`, `mage-mid`** report the viewer having **more** health than the recording
  (`hp is 20, recording says 18`). Opposite sign. Something is failing to *apply* a hit, not applying
  one early.
- **`duelist-short`** fails on position (`hero at 169, recording says 170`), not health.

E4 says whether they survive the gate. Each is then traced with the same tools.

### Rejected: deferring `HeadlessSprite`

The experiment this plan started with, and it is not the one to run. Four reasons, all found by reading:

1. **There is no tick point.** `HeadlessGame.update()` exists but nothing calls it; `Game.update` is never
   reached from the module. `LevelPipeline` would have to add the pump itself.
2. **`postRunnable` runs inline** (`HeadlessServices.java:174`), so anything deferred through
   `Game.runOnRenderThread` resolves inside the `act()` that queued it. No deferral.
3. **The drain cannot yield.** `runToHeroReady` has no no-progress counter and no yield point, so an
   unresolved actor spins to `actorStepLimit` (20000), returns `STEP_LIMIT`, and `SPDEnv` terminates the
   episode as `STALLED` - zero reward, `truncated = true`. Every episode containing one mob attack would
   end.
4. **`onOperateComplete` is load-bearing.** `Hero.onOperateComplete` is the only thing that opens chests,
   removes keys, changes terrain and spends `Key.TIME_TO_UNLOCK`. `actOpenChest` leaves `curAction` set,
   so a tick that never fires wedges the hero with no timeout.

Plus it would require re-recording the corpus. Recorded here so the next reader does not reach for it.
## 6. Where this leaves the trainer

Unchanged, and that is the finding. For the record, three settings mismatches have now been found
between the two environments, in this order: quickslot bindings, scheduler tie-breaking, and the intro
flag. The first two were trainer-side, the third viewer-side. Each is the same class of error —
**a setting only one side states** — and `LevelPipeline.startRun` and `ReplayLauncher.windowConfig` now
both state theirs explicitly rather than inheriting.

If a fourth turns up, the rule is the one the third established: the side that can be stated at
construction time states it, and neither side inherits from ambient preferences.

## 7. Corrections to `ISSUE-viewer-frame-drift.md`

Recorded so the file stops misleading the next reader:

| claim | verdict |
|---|---|
| "`GameScene.update` advances the world on its own schedule" | **wrong.** Gated off by `Actor.manualScheduling`. |
| "the viewer hands out extra turns" | **wrong.** No actor takes a turn that the trainer did not. One *recorded step* is applied too early. |
| "failing membership varies between runs" | **was true, no longer.** Fixed by a pinned delta and sequential default; 14 of 17 now stable at identical steps. |
| "the suspected cause is redundant `Mob.act()` re-selection" | **wrong.** The 31 idle frames show no such churn. One level up. |
| "make the trainer replicate the viewer" | **backwards.** The trainer already matches the game; see §3. |
| "needs a tool comparing actions and HP" | **done.** `WorldSnapshot` + `WorldDiff`, per-cell and per-field. |
| "`viewcheck` cannot be a gate" | still true, for needing a display. |

## 8. Blast radius

`desktop/.../replay/ReplayPlayer.java`, `desktop/.../replay/FrameDelta.java`,
`superintelligence/.../replay/WorldSnapshot.java`. Engine: none beyond the four getters already landed.

`playbackcheck` drives `ReplayPlayer` with `HeadlessSprite`, where `attack()` completes synchronously
and `next()` runs inside `headlessStep()`, so both new conditions should read false. That is an
expectation, not a claim — `verifyall` is the check.