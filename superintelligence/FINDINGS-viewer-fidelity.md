# Findings: viewer fidelity investigation, measured state

Written after `69fcf8582`. Records what is **established**, what is **refuted**, and what is **open**,
with the measurement each claim rests on. Written after four hypotheses were proposed and three were
refuted by running them, so the refutations are kept rather than tidied away.

Companion to `PLAN-viewer-fidelity.md`, which holds the plan. This file holds the evidence.

---

## Established

### E-1. Headless and viewer agree bit-for-bit for 16 steps, then diverge

`warrior-mid`, produced by:

```
gradle :superintelligence:worldtrace -PworldArgs="<replay> <out>" --no-cells
viewer  -Dspd.worldTrace=<out>
```

compared field by field (`step:N` records only, 19 fields each):

```
parsed: headless steps=154 viewer steps=16 fields/record=19
step:0 … step:15   same  (19 fields)
RESULT: all 16 shared steps agree on all 19 fields.
```

Identical `pos`, `hp`, `turn`, `buffs`, `depth`, `gold`, `str`, and full path-qualified inventory including
nested bag contents.

**The comparator is self-tested.** Tampering one field of a copy produces
`DIFF hp: '7/20' -> '20/20'; turn: '9.0' -> '1.0'`, so the agreement above is a real result and not a
parse failure reporting nothing compared.

Two earlier comparison scripts were wrong and reported agreement from a failure to parse — a PowerShell
one that split on tab where fields are space-separated inside a single token, and a second that
enumerated a hashtable incorrectly. Both were discarded. Only the Python comparator is validated.

### E-2. The divergence is created by the last action, not accumulated

The viewer halts at step 17, so step 16's record is never written. E-1's identical first 16 steps mean
no RNG drift, no AI drift, no accumulated divergence. Everything that differs happened on the final
action.

### E-3. A hunting, hero-targeting rat is unpaid when the hero moves

Per-frame roster from the same run, rat `id=9`:

```
frame 68  state=Sleeping  cd=0.0  sees=N -> Y
frame 70  state=Hunting   cd=1.0  tgt=- -> Hero
frame 73  state=Hunting   cd=0.0  tgt=Hero      <-- hero moves 528 -> 492 on this frame
frame 86  state=Hunting   cd=1.0                 <-- hit lands, hp 20/20 -> 19/20
frame 87  buffs: WarriorShield:0.0 -> 1.0
```

`cd` is `cooldown()` = `time - now`, so `0.0` means due this instant. At frame 73 the rat is awake,
targeting the hero, and **has not been given its turn**. The recorded step applies anyway. The hit lands
13 frames later.

In the recording the hero's step 17 kills the rat (`mob9 hp 8/8 -> 2/8`); in the viewer the rat hits
first. The `WarriorShield` buff one frame after the damage confirms a combat hit rather than a trap.

`turn` never advances past 14.0 across the whole window.

### E-4. The real game parks its scheduler; the viewer does not

`GameScene.java:942`:

```java
if (!Actor.processing() && Dungeon.hero.isAlive() && !Actor.manualScheduling) {
    if (actorThread == null || !actorThread.isAlive()) { ... }
    else if (notifyDelay <= 0f) {
        notifyDelay += 1/60f;
        synchronized (actorThread) { actorThread.notify(); }
    }
}
```

`Actor.processing()` returns `current != null` (`Actor.java:272-274`). So when a mob attacks,
`Mob.doAttack` returns `false` without spending (`Mob.java:817-829`), `current` stays set to that mob, and
`Actor.process()` falls to `if (!doNext)` and parks in `Thread.wait()` at `Actor.java:423` — and the
render thread **stops poking it**, because the poke is gated on the condition that is now false.

**The scheduler does not spin. The mob's turn is genuinely blocked** until `CharSprite.onComplete` fires
`Mob.onAttackComplete`, which applies damage and calls `spend(attackDelay())`.

`PLAN-viewer-fidelity.md` §3 previously claimed the scheduler re-picks the mob and spins. That was wrong,
and wrong in the way that matters: the spin is not what the game does, so it is not what the viewer must
match. The document's *conclusion* survives — game and trainer produce the same ordering — but for a
different reason.

### E-5. `FrameDelta` does not pin the animation clock

Per-frame `clock` record, pin requested at `0.0166666`:

| attempt | measured |
|---|---|
| overwrite `Game.elapsed` | `Game.elapsed` read 0.0166, `timeTotal` advanced **0.0070**/frame — clock split 42/58 |
| write `Game.timeScale` | applies to the *next* frame's delta, unobservable; observed oscillating between 0.001 and 0.14/frame |
| both | no better |

Structural reason: `Game.elapsed` is **derived**. `Game.update` computes
`elapsed = timeScale * frameDelta` and folds it into `timeTotal` at `Game.java:283-284`, *before*
`scene.update()` runs. The frame driver is downstream of the value it would need to influence.

Arithmetic closes: 49 frames x 0.0070 = 0.34 s, the attack animation's real duration. **The animation was
completing correctly all along** — the pin was dividing it across 49 frames instead of 20.

`-Dspd.fixedDelta` now pins playback pacing only and prints a warning saying so. Animation timing remains
frame-rate dependent; fixing that needs an engine change at the point the delta is measured, which is a
separate decision.

### E-6. Two real bugs in the viewer's settings, one of them latent

- **`fullscreen`** — `SPDSettings.fullscreen()` defaults to `true` (`SPDSettings.java:67`).
  `DesktopPlatformSupport.java:85` then calls `setFullscreenMode` from the game's own `create()`, after the
  launcher's window configuration is complete, so no `-D` flag can stop it. GLFW ignores its visibility
  hint for fullscreen windows, so hiding cannot either. Verified in both directions: flags keep it windowed,
  dropping them trips the new `viewcheck` check.
- **`intro`** — `SPDSettings.intro()` also defaults to `true`, and `Hunger.act()` returns early while set
  (`Hunger.java:68`), freezing the hunger clock. The trainer has always stated `intro(false)`
  (`LevelPipeline.java:88`); the viewer inherited `false` from the developer's own settings by luck.
  Isolated preferences made the default apply and recordings that had been passing started failing.

Third instance of the same class — a setting only one side states — after quickslot bindings and
scheduler tie-breaking.

### E-7. The corpus is deterministic

Full corpus, twice, sequential: **14 of 17, identical recordings and identical steps** both runs.
Parallelism at 10 children reports the same recordings but shuffles steps, so `VIEWCHECK_JOBS` defaults to
1 and the gate warns when it is not.

---

## Refuted

Kept because a hypothesis that was measured and killed is worth more than one never questioned, and
because three of these would otherwise be re-proposed.

### R-1. Redundant `Mob.act()` re-selection

**Predicted:** while a mob's attack animation plays, `driveToHeroReady` calls `headlessStep()` up to
`BLOCKED_STEPS = 3` times per frame, so ~147 extra `Mob.act()` calls over a 49-frame stall.

**Refuted:** the 49 stall frames show no cooldown churn on the rat. It is not spinning.

*(Not separately measured: whether those ~147 calls consume RNG. Still open — see O-3.)*

### R-2. Blocking on an in-flight animation

**Predicted:** `readyToAct()` lets a step be applied across a pending mob attack.

**Refuted by measurement**, frames 74-121 of `warrior-mid`:

```
applied=false  drain=PENDING  ready=false  current=Rat@9  anim=1
```

**48 consecutive frames** where the viewer applies nothing and waits for the animation. The proposed check
would have forced behaviour the code already exhibits, so it was dropped rather than implemented.

**Scoped:** this tested steps applied *during an animation*. It does not cover a step applied while an
unrelated actor holds the turn with no animation running — which E-3 shows is what actually happens.

### R-3. `Actor.currentActor() != null` as the gate

**Predicted:** mirroring `GameScene.java:942`'s `!Actor.processing()`.

**Refuted:** at frame 73, when the hero moves, `Actor.current` is `Hero@1` or a blob — **not the rat**.
The rat had not been given a turn at all, so `current` cannot see it.

### R-4. `cooldown() <= 0` as the gate

**Predicted:** a due, unpaid mob is the defect.

**Refuted, and it wedged playback.** Sleeping mobs legitimately sit at `cd=0.0` until they wake, so "due"
has no discriminating power:

```
[replay] halted: stalled - a recorded step was withheld for 600 frames,
          still waiting on Sentry@5 owing a turn at cd=0.0
[replay] 607 frames, 0 applied a step, 607 did not
[viewcheck] ok    wm.replay  (0/154 steps)
```

**Reverted before this file was written**; the working tree is clean of it. Two lessons recorded: the gate
needed a bound (it had one, and it reported rather than hung), and `cd=0.0` distinguishes nothing because
sleeping and about-to-strike both read the same.

---

## Open

### O-1. No fix yet, and three wrong gates is the reason to be careful

Four candidates proposed, three refuted. The remaining evidence (E-3) points at turn accounting — the drain
declares hero-ready while a mob that owes a turn has not taken it — but **no gate has been shown to fix
it**, and the natural narrowing ("hunting and targeting the hero") is a guess from one recording.

### O-2. Corpus-wide test before another single-recording guess

Three wrong gates in a row came from picking a predicate and testing it on `warrior-mid`. The next move is
instrumentation that answers across all 17: record, per frame, whether the drain returned ready while a
hunting mob targeting the hero was unpaid. Let the corpus say which recordings that bites on before any
behaviour changes.

### O-3. Whether the redundant acts draw RNG

R-1 refuted the *spinning* but not the RNG question. Reading finds no draw in `Char.act()`, `chooseEnemy()`
or `processSwarmIntel()`, but `Mob.chooseEnemy` is long and "no draw found by reading" is not "no draw".

### O-4. Three anomalies with different signatures

- **`mage-alt`, `mage-mid`** — `hp is 20, recording says 18`. The viewer has **more** health than the
  recording, the opposite sign of every other failure. Something is failing to *apply* a hit.
- **`duelist-short`** — fails on position (`hero at 169, recording says 170`), not health.

Neither is explained. E-4 may bear on the first.

### O-5. The animation clock cannot be pinned from the viewer

See E-5. Closing it needs an engine change at `Game.java:283-284`. It would make animation timing
deterministic for diagnosis, and on its own would **not** fix the divergence — it changes *when* an
animation completes, not *what happens when it does*.

---

## Notes on method

**Measurements are reliable; predictions from reading were not.** Three of four mechanisms proposed from
source were wrong on running. The reads that survived were the ones backed by a measurement, and every one
that changed a conclusion came from running something.

**Two comparison scripts reported agreement while failing to parse.** A tool that says "all fields agree"
when it compared nothing is worse than no tool. The Python comparator prints its field count and aborts
below 5, and is self-tested against a tampered copy.

**The wedge guard earned its place.** R-4's gate hung for 600 frames and reported exactly what it was
waiting on, instead of either hanging forever or silently applying steps it should not have.