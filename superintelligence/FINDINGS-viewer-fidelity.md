# Findings: viewer fidelity investigation, measured state

Written after `020751c2a`, revised after the divergence was found and fixed. Records what is
**established**, what is **refuted**, and what is **open**, with the measurement each claim rests on.
Six hypotheses were proposed and five were refuted by running them, so the refutations are kept rather
than tidied away.

Companion to `PLAN-viewer-fidelity.md`, which holds the plan. This file holds the evidence.

---

## Resolved

### E-8. The corpus was verified clean in the rendered viewer

**Cause found, fixed, and measured across the whole corpus.** `gradle :desktop:viewcheck` reports:

```
[OK]  rendered playback: 17 recordings played clean in the real viewer (1 at a time, 201.3s)
```

Two consecutive full runs, and every recording replayed three times individually, give the same verdict
all 17 times each. Before the work it was 14 of 17 diverging, with two of those reporting a
*different* field value on different runs.

The cause was not the scheduler. See E-9.

### E-9. Four presentation draws were on the gameplay RNG stream

Found by comparing the two sides' base-generator streams value by value, not by counting them.

`Random.reseedBase` seeds the base generator from the run seed, so its output is computable: generate
the first few hundred values from `new java.util.Random( scrambleSeed( Dungeon.seed ) )` and look up
where each observed draw falls. Measured on `warrior-long`, seed 2547414048295, scrambled
2466687093574357806:

```
headless first gameplay draw = generator value #5
viewer   first gameplay draw = generator value #17
```

Both report `generationBaseDraws = 0`. So both reached the first recorded step with the base generator
freshly seeded and, on the trace's own account, never drawn — while the two were in fact four and
sixteen values along. The four sites, all presentation, all spending the gameplay stream:

| site | viewer-only draws | what it decides |
| --- | --- | --- |
| `CharSprite.link:156` | 12 (base) | the random facing a sprite is given when it is linked |
| `AttackIndicator.checkEnemies:130` | 1 per step (base) | which mob the attack overlay highlights |
| `Wand.staffFx:453` | per cast (base) | which way a cosmetic particle flies |
| `MagesStaff.StaffParticle.update:558` | per frame (base) | cosmetic size jitter on a particle |
| `DungeonTileSheet.setupVariance:485` | 962 (pushed) | alternate tile visuals; inside `pushGenerator`/`popGenerator`, so inert |

All four base ones are now `PRandom`, which is the stream `PRandom` was created for and which every
other presentation draw in the game already uses. `DungeonTileSheet` was left alone: it draws inside a
pushed generator that is discarded, so it cannot affect an outcome and moving it would be churn.

`CharSprite.link` is the one that mattered most, and it explains the whole count. `HeadlessSprite`
overrides `link()` and so never reaches that line, which is exactly why the trainer never made the draw
and the viewer always did: 12 base values, once per actor the viewer attaches a real sprite to.

### E-10. The trace could not see the draws that caused the fault

`Random.Int(int, boolean)` and `Random.shuffle(List)` both advanced the generator and never called
`RandomTrace.record`. Only `Float()` and `Long()` were counted.

This is why the investigation went the way it did. `RngTrace` reported identical `baseDraws` at every
step of every recording, and `PLAN-replay-parity.md` §1.4 concluded from that — correctly, given the
instrument — that "the RNG stream is not offset". Every hypothesis after that was about ordering,
because a counter that could not see the offset said there was no offset. Restoring the two paths
makes the counts agree *and* the fingerprints agree, which is a different and much stronger statement.

`observecheck` now asserts the invariant directly: all eighteen public draw paths must move the counter,
and `shuffle` must too. Mutation-tested — removing the `record` call from `Int` fails two of its six
checks and names `Int(10), Int(10, false), Int(1, 2), IntRange(1, 2), element(list)`.

### E-11. The world diff was not comparing the hero field by field

`WorldSnapshot`'s hero row joins its fields with **spaces** (`pos=684 hp=20/20 turn=11.0`) while every
other row joins with tabs. `WorldDiff.fields()` split on tab only, so every hero record parsed as a
single key: a comparison of the hero — the record that carries position, health and engine time, and
the one every divergence is decided on — compared the whole row and reported the failure as a difference
in whichever field came first, always `pos`.

Measured, and it changed the answer. On `warrior-long` the two sides differ at step 13 in exactly one
hero field, `exp`, and the tool reported `hero field pos` for a position that was identical in both.
Splitting on whitespace as well names `exp`, and turns the inventory blob into per-item keys.

---

## Established, and still true

### E-1. Headless and viewer agreed for 12 steps, then diverged

`warrior-mid`, produced by:

```
gradle :superintelligence:worldtrace -PworldArgs="<replay> <out>" --no-cells
viewer  -Dspd.worldTrace=<out>
```

compared field by field (`step:N` records, 19 fields each):

```
step:0 … step:11   same  (19 fields)
step:12            hero field exp
  expected: 0
  actual:   1
```

Identical `pos`, `hp`, `turn`, `buffs`, `depth`, `gold`, `str`, and full path-qualified inventory
including nested bag contents. The comparator is self-tested against a tampered copy.

### E-2. The divergence was created by one action, not accumulated

Nothing accumulates. With E-9 understood the chain is short and total: the renderer's stream starts
twelve values along, so a damage roll at step 12 comes out differently, and the hero's swing that the
trainer recorded as leaving the rat at 3/8 kills it instead. Every later difference is downstream of
that one.

### E-3. What the traces said about the rat, once the tool worked

`Rat@15` adjacent to the hero, hunting, `cd=0.0`, and the hero's recorded `INTERACT` resolved to an
attack. Per-frame roster on the old build, then a stack trace at the death (`Mob.die`, from
`Hero.onAttackComplete` ← `CharSprite.onComplete` ← `GameScene.update`):

```
headless step 13   Rat@15 hp=3/8   exp=0
viewer   step 13   Rat@15 dead     exp=1
```

The hit landed; the damage roll simply differed.

### E-4. The real game parks its scheduler; `headlessStep` does not

`Actor.process()` blocks in `Thread.wait()` when an actor returns `false`, and `GameScene.java:942`
only re-pokes when `!Actor.processing()`. `Actor.headlessStep()` has no equivalent — it re-selects on
every call. **This is real and it is not the cause.** Measured on `warrior-long` before any change: 290
of 303 scheduler calls re-selected the actor already holding the scheduler. Two things that looks like
are not it:

- Re-selecting does not restart the animation. `MovieClip.play` returns immediately when the same
  non-looped animation is already in flight.
- `Actor.current` cannot tell a finished turn from an unfinished one. It is cleared only by
  `Actor.next()`, which neither `Hero.act` nor `Buff.act` calls — `Hero.act` calls `ready()` and
  `Buff.act` diactivates, both returning without it. `Actor.process` hides this by setting
  `current = null` at the top of every iteration; `headlessStep` does not.

The park was implemented, measured, and reverted. Parking on any non-hero actor wedged on the first
buff (`Regeneration@2`, 600 frames, three scheduler calls); parking on a `Char` wedged on
`Sentry@5 / Piranha@5 / Rat@5 / Snake@5`, every one reporting `cd=1.0` — which is `attackDelay()`, so
they had spent their turn and `Actor.current` was simply stale.

### E-5. `FrameDelta` cannot pin the animation clock

Per-frame `clock` record, pin requested at `0.0166666`:

| attempt | measured |
| --- | --- |
| overwrite `Game.elapsed` | `Game.elapsed` read 0.0166, `timeTotal` advanced **0.0070**/frame — clock split 42/58 |
| write `Game.timeScale` | applies to the *next* frame's delta, unobservable; observed oscillating between 0.001 and 0.14/frame |
| both | no better |

Structural reason: `Game.elapsed` is **derived**. `Game.update` computes `elapsed = timeScale *
frameDelta` and folds it into `timeTotal` at `Game.java:283-284`, *before* `scene.update()` runs. The
frame driver is downstream of the value it would need to influence.

Arithmetic closes: 49 frames x 0.0070 = 0.34 s, the attack animation's real duration. **The animation
was completing correctly all along.** `-Dspd.fixedDelta` pins playback pacing only and prints a
warning saying so.

### E-6. Two real bugs in the viewer's settings, one of them latent

- **`fullscreen`** — `SPDSettings.fullscreen()` defaults to `true` (`SPDSettings.java:67`), and
  `DesktopPlatformSupport` then calls `setFullscreenMode` from the game's own `create()`, after the
  launcher's window configuration is complete, so no `-D` flag can stop it.
- **`intro`** — `SPDSettings.intro()` also defaults to `true`, and `Hunger.act()` returns early while
  set, freezing the hunger clock. The trainer has always stated `intro(false)`; the viewer inherited
  `false` from the developer's own settings by luck.

Third instance of the same class — a setting only one side states — after quickslot bindings and
scheduler tie-breaking.

### E-7. The corpus is deterministic

Full corpus, twice, sequential: identical recordings and identical steps both runs. Parallelism at 10
children shuffles steps, so `VIEWCHECK_JOBS` defaults to 1 and the gate warns when it is not.

---

## Refuted

Kept because a hypothesis that was measured and killed is worth more than one never questioned, and
because five of these would otherwise be re-proposed.

### R-1. Redundant `Mob.act()` re-selection — refuted as a cause, confirmed as real

The 290-of-303 measurement stands, and the earlier claim that it was "not spinning" was **refuted by
the wrong observable**: `cooldown()` cannot change during a park, so it could not have detected it.
Re-selection is real and harmless — it costs `Mob.act()` evaluations and no animation restart, and it
happens identically on the headless side. Recorded here because the original refutation is in the
change history and would otherwise stand.

### R-2. Blocking on an in-flight animation — refuted

**48 consecutive frames** where the viewer applies nothing and waits for the animation. The proposed
check would have forced behaviour the code already exhibits, so it was dropped rather than implemented.

### R-3. `Actor.currentActor() != null` as the gate — refuted

At the frame where the hero moves, `Actor.current` is `Hero@1` or a blob, not the rat. See E-4 for the
stronger version: `Actor.current` is not a turn-ownership signal at all under `headlessStep`.

### R-4. `cooldown() <= 0` as the gate — refuted, and it wedged playback

Sleeping mobs legitimately sit at `cd=0.0` until they wake, so "due" has no discriminating power:

```
[replay] halted: stalled - a recorded step was withheld for 600 frames,
          still waiting on Sentry@5 owing a turn at cd=0.0
```

Reverted before this file was written; the working tree is clean of it.

### R-5. A step applied while another actor held the scheduler — refuted

Counted at the injection point, where the question is actually asked. **Zero, on every recording
measured** (`warrior-mid`, `mage-mid`, `warrior-long`, `cleric-mid`). This was the plan's leading
hypothesis and it is simply not what happens.

### R-6. The RNG stream being offset — right answer, wrong reason, held too long

`PLAN-replay-parity.md` §1.4 declared this dead from matching draw counts. The counts matched because
`Random.Int` was not counted (E-10). The stream *was* offset, by 4 and 16 values, from the very first
gameplay draw.

---

## Open

### O-1. `viewcheck` is still outside `gates`

It needs a display, so it is excluded and the fault class it covers returns silently. Recorded rather
than solved: 203 seconds sequential, and it forks a JVM per recording.

### O-2. The animation clock cannot be pinned from the viewer

See E-5. Closing it needs an engine change at `Game.java:283-284`. It would make animation timing
deterministic for diagnosis and on its own would not have fixed any of this — it changes *when* an
animation completes, not *what happens when it does*.

### O-3. Four engine changes are uncommitted

The four presentation draws in E-9 are game files. They are engine changes and `instructions.md` §12.3
keeps them uncommitted unless proposed. They are the fix, and without them `viewcheck` is 14 of 17 red
on a committed tree exactly as before.

### O-4. A new public draw path would not be caught

`observecheck` enumerates the eighteen draw paths that exist. A nineteenth added later does not
appear in the list. The invariant is about the paths it knows about, not about future ones.

### O-5. The remaining divergence shapes were never separately explained

`duelist-short` failing on position by one cell, and `mage-mid` reporting two points of health below
the recording, were recorded as distinct signatures. Both were present before the fix and both are
gone now, so they were downstream of the same cause — but that was not shown at the time, and the
shape of a fault class is worth more than its tally.

---

## Notes on method

**Measurements are reliable; predictions from reading were not.** Six mechanisms proposed from source;
one survived contact with the run. The reads that survived were the ones backed by a measurement, and
every conclusion that changed came from running something.

**The instrument was the thing that was wrong, twice.** `RngTrace` could not see `Random.Int`, so it
reported two streams as identical while they were sixteen values apart. `WorldDiff` split on tab only,
so it never named the hero field that differed. A tool that says "they agree" when it compared less
than it claims to is worse than no tool, and both of these said exactly that for as long as anyone
relied on them.

**Recovering values from a fingerprint turned a symptom into a position.** `RngTrace` folds each draw
with `fp = (fp ^ bits) * PRIME`, so the values behind two traces are computable, and the base
generator's output is computable from its seed. Comparing those located the fault in two runs. Every
previous attempt on this had to infer the offset from a count.
