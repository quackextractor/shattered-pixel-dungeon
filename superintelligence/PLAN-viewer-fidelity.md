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

`Actor.process()` — the scheduler the shipped game runs — blocks on exactly one thing:

```java
//Actor.java:379-391
if (acting instanceof Char && ((Char) acting).sprite != null) {
    synchronized (((Char)acting).sprite) {
        if (((Char) acting).sprite.isMoving) {
            ((Char) acting).sprite.wait();
        }
    }
}
```

`sprite.isMoving` is set by `CharSprite.move()` (`CharSprite.java:230`) and **by nothing else** — not by
`attack()`, not by `operate()`. So in a real game an attack animation does **not** park the scheduler.
What it does is leave `Mob.act()` returning `false` with `mob.time` unchanged, so the scheduler selects
the same mob again, calls `Mob.act()` again, and spins there until the animation finishes and
`onAttackComplete` spends the turn.

The event order is therefore the same on both sides:

| | order |
|---|---|
| real game | decide → [animation] → damage → spend turn → next actor |
| trainer | decide → damage → spend turn → next actor |
| viewer, currently | decide → [animation] → **hero acts** → damage → spend turn |

The only thing between "decide" and "damage" is a wall-clock pause. Nothing in it changes state, and
the repeated `Mob.act()` calls are side-effect free: `Char.act()` recomputes FOV, `Mob.act()` calls
`chooseEnemy()` and `processSwarmIntel()`, and `Hunting.act()` returns `doAttack()`. `chooseEnemy`
builds a `PathFinder` distance map — deterministic, no draws. The one AI state that rolls is
`Sleeping.act()`'s detection check (`Mob.java:1220-1300`), and it calls `spend(TICK)`, so it never
spins.

**Consequence:** the trainer already produces the game's ordering, so teaching it to wait would teach
the agent a reordering the game does not have — and would need a virtual clock in a loop that has none,
plus a re-recorded corpus. The user raised exactly this concern when the plan still pointed the other
way, and the evidence settles it: **fix the viewer.**

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

## 4a. Measurement: the gate is not the bug

Recorded because a plan that predicted the wrong mechanism, and was caught by measurement, is worth more
than one that was never questioned.

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

- The `readyToAct()` short-circuit identified in §4 is real, but it is **not** the fault. `ready=false`
  throughout the stall, so the short-circuit never fires during it.
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

### Step 1 — measure

Add four fields to the frame record in `WorldSnapshot`, off unless `-Dspd.worldTrace` is set:

| field | why |
|---|---|
| `readyToAct` | the gate's own answer, per frame |
| `currentActor` | non-null means an actor took a turn and has not called `next()` |
| `animating` | count of actors with a non-looping animation in flight |
| `drain` | already recorded: `READY` / `PENDING` / `STALLED` / `-` |

The signature to look for is **a step applied on a frame where `readyToAct=true` and
`currentActor!=null`**. That is the hypothesis, directly.

Run: `warrior-mid`, `-Dspd.fixedDelta=0.0166666`, one pass.

### Step 2 — the gate

`readyToAct()` gains two conditions:

1. **`Actor.currentActor() == null`.** `Actor.current` is set by `headlessStep` and cleared only by
   `next()`, which is called from `onAttackComplete`. So non-null means *an actor has taken a turn and
   not completed it* — which is exactly the state the hero must not be given input in.
2. **No `animationInFlight()` on any actor.** Covers a mob attacking while another is still mid-swing,
   where the mob that attacked has already cleared `current` but the animation has not resolved.

Both use getters added in `e0c986c27` (`Actor.currentActor()`, `MovieClip.animationInFlight()`). No new
engine surface.

**Bounded, or it can deadlock.** Count consecutive frames the gate refuses. Past a limit, halt and
report the blocking actor and its animation state rather than hanging: a gate that can wedge a
playback forever is a worse failure than the divergence being fixed.

### Step 3 — verify

Full corpus, twice, sequential (`VIEWCHECK_JOBS` defaults to 1, from `e0c986c27`).

Both membership **and** step numbers must match between runs. One recording must fail identically
before and after — `warrior-mid@17 hp is 19, recording says 20` is the known instance, and a change that
merely moves failures onto other recordings has fixed nothing.

### Step 4 — the survivors

Three failures have a different signature from the other eleven and may be separate causes:

- **`mage-alt`, `mage-mid`** report the viewer having **more** health than the recording
  (`hp is 20, recording says 18`). Opposite sign. Something is failing to *apply* a hit, not applying
  one early.
- **`duelist-short`** fails on position (`hero at 169, recording says 170`), not health.

Step 3 says whether they survive the gate. Each is then traced with the same tools.

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