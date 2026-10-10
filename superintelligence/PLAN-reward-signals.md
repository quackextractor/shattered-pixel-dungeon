# Reward signals - the stall strategy

> **Read §7 before §1.** Sections 1-6 diagnose the stall as a reward-arithmetic
> problem reached by waiting. That diagnosis was wrong, and §7 is the correction:
> `REST` was a one-way door, and the agent was trapped by the harness rather than
> choosing to idle. §8 records two further faults that the stall was hiding.
> The earlier sections are kept unmodified rather than rewritten, because how the
> wrong answer looked reasonable is part of what §7 is about.

Why the agent learned to stop moving instead of play, and what to do about it.

Written 2026-10-07, after the first 20-generation training run on the corrected
update. Cross-references: `TODO.md` section 2, `PLAN-data-flow.md` for the
worker data flow.

---

## 1. What happened

The run was instrumented, which is the only reason this is visible. From
`metrics.csv`:

| gen | meanScore | meanTurns | clip | entropy |
| --- | --- | --- | --- | --- |
| 0 | +5.17 | 62 | 0.734 | 2.077 |
| 5 | -4.98 | 11 | 0.096 | 2.162 |
| 12 | -5.02 | 8 | 0.036 | 2.245 |
| 18 | -5.00 | 9 | 0.048 | 2.101 |

`meanScore` converges on exactly **-5.0** - by generation 18 it is 0.0024 away.
`RewardModel.terminate` gives `STALLED` a reward of `-depthReward * 0.5` = -5.0
(`RewardModel.java`). Turns collapse from 62 to 8-11 while entropy stays flat.

**The agent found a way to end an episode deliberately and is now optimising it.**
Depth never moved off 1 in twenty generations, and this is why.

The losses look healthy throughout - `valueLoss` flat around 3, `clipFraction`
settling from 0.73 to 0.05 - which is the part worth dwelling on. Every number the
trainer reports said the update was working. The objective was the problem.

## 2. The mechanism

Three separate defects, compounding.

### 2.1 `WAIT` is reported as a failed action

`ActionMapper.apply` (`ActionMapper.java:209`):

```java
case WAIT:
    hero.next();
    return false;      // reports "the action did nothing"
```

That return value is read by `SPDEnv.step` (`SPDEnv.java:243`) as failure:

```java
if (!acted){
    reward.note( RewardTerm.INVALID_ACTION, 0 );
}
```

The amount is 0, so no reward is actually moved - but `WAIT` is a *valid* action
and it is being classified as an invalid one. `REST` and `SEARCH` return `false`
for the same reason.

### 2.2 The stall guard is trivially reachable

`SPDEnv.checkFloorLimits` (`SPDEnv.java:374`) ends the episode when the hero's
position and HP are both unchanged for `stallLimit` (120) turns:

```java
if (hero.pos == lastStallPos && hero.HP == lastStallHp){
    stallCount++;
    if (stallCount >= config.stallLimit){
        terminate( RewardModel.TerminateReason.STALLED );
    }
}
```

`WAIT` costs a turn, does not move the hero, and changes no HP. So:

> **`WAIT` × 120 → `STALLED` → -5.0, having paid 120 × 0.002 = -0.24 in turn cost.**

Total ≈ **-5.24** for ending the episode on purpose.

### 2.3 Everything else costs more

| Outcome | Cost | Magnitude |
| --- | --- | --- |
| `WAIT` × stallLimit → `STALLED` | **-5.24** | ← what it learned |
| Survive to the 40,000-turn cap → `TURN_LIMIT` | -80 | 15× worse |
| Die | **-100** | 20× worse |

**Stalling is twenty times cheaper than dying.** With `turnCost` at 0.002 and a
death penalty of 100, the cheapest way to end an episode is to trip a timeout
guard. The reward function states this preference as clearly as if it had been
written to.

There was also a duplicated call - `SPDEnv.java:263-264` invokes
`terminate(STALLED)` twice, so that particular path charged -10.0 rather than
-5.0. It is not the path the agent found (the `stallLimit` guard at 374 is), but
it is a real bug and it means the -5.0 convergence is *not* explained by it.

### 2.4 And it was penalised while bootstrapping

`SPDEnv.terminate` (`SPDEnv.java:389`) marks `STALLED` as truncated:

```java
truncated = (reason == TURN_LIMIT || reason == STALLED);
```

So a stall is simultaneously **penalised by -5.0** and treated by GAE as an
episode that was merely cut short, whose value bootstraps onward. Only `DEATH`
and `VICTORY` satisfy `endedNaturally()`. A stall is a harness artefact and a
real outcome at the same time, which is why the critic never settled either.

## 3. The fix

### 3.1 Report what actually happened

`WAIT`, `REST` and `SEARCH` are valid actions and must return `true`. The
`INVALID_ACTION` note exists to catch actions the environment refused - an
unusable item, a missing slot - and it cannot do that job while every no-op
action is reported through it.

### 3.2 Stop making a timeout into a terminal

`STALLED` becomes **zero** rather than -5.0, and stays truncated/bootstrapping.

Zero rather than a small negative penalty, and the reasoning is the interesting
part. An earlier version of this plan argued for a small penalty to break the tie
against `TURN_LIMIT`; measuring first showed the tie is not the problem. At -5.0
the agent had a *dominant* strategy available and took it immediately. At zero,
idling costs exactly what idling should cost - `turnCost` per turn - and nothing
more. That is the honest price of an action that does nothing, and it is already
being charged.

A small negative value was rejected because it would be a guess about a failure
mode nobody has observed. There is no evidence the agent gets *trapped*; every
stall observed is one it walked into with `WAIT`. Inventing a penalty for a
hypothetical trap, on top of a reward function that has already misled the agent
once, is how the next wrong preference gets baked in.

### 3.3 Fix the duplicated terminate

One call at `SPDEnv.java:263`.

### 3.4 Make the terminal vocabulary honest

`endedNaturally()` continues to mean death or victory - those are the only two
outcomes where the game, rather than the harness, decided. A stall and a turn
limit are truncations: the episode was cut off, not lost.

## 4. The gate

`rewardcheck` asserts the property that was violated, behaviourally rather than
by reading constants:

- **`WAIT` × `stallLimit` must not be cheaper than dying.** The degenerate
  strategy is replayed through the real environment and the resulting episode
  score compared against a real death. This is the regression test; the constants
  could be changed in any order and it would still hold the property.
- **`WAIT` must not report as an invalid action.**
- **A stall must not be marked as a natural ending.**
- **`STALLED` must carry no terminal reward**, and `TURN_COST` must still be
  charged for the turns it consumed - the point being that zero means "priced by
  turn cost alone", not "free".
- **`DEATH` must remain the most expensive outcome**, so the ordering cannot
  invert again silently.

Mutation-tested: reverting each fix fails the case it corresponds to.

## 5. What this does not fix

Being explicit, because the temptation after a training run goes flat is to keep
flipping reward weights until the number moves.

- **The agent still does not learn.** Section 6 measures it: it still stalls every
  episode, it is merely no longer paid to. Nothing here makes the environment
  easier. §6 says what is left, which is not a reward-arithmetic problem.
- **`depthReward` of +10 against `turnCost` of 0.002** means descending one floor
  pays 5,000 turns of idling, and that ratio is the next thing to examine.
  Deliberately untouched here, because changing it in the same commit as the stall
  penalty would have made §6's measurement uninterpretable. It is `TODO.md` 1.8.
- **The curriculum is still inert** (`observe()` is never called, so shaping is a
  constant 1f). It stays inert until the reward shape is settled — a fade schedule
  over an unsettled objective would hide the question rather than answer it.
- **`KILL` still has no emission site.** Same reasoning, and `KILL` is one of the
  terms that would pay for playing well.

## 6. Verification

Baseline, from the run that motivated all of this:

```
meanScore   -> -5.00 (converged on STALLED)
meanTurns   -> 8-11
bestDepth   -> 1
valueLoss   -> 2.4-3.5, flat
```

After the fix, same 20 generations, 8 workers, same seed:

| | before | after |
| --- | --- | --- |
| meanScore, gen 19 | -4.46 | **+3.29** |
| meanScore, converged to | exactly -5.00 | no constant |
| meanTurns, gen 19 | 11 | **56** |
| valueLoss, gen 19 | 3.53 | **0.44** |
| stall share of episodes | 100% | 100% |
| bestDepth | 1 | 1 |

**The stall share did not move, and that is the result worth reading carefully.**

What the fix achieved: stalling is no longer profitable. `meanScore` no longer
converges on a constant, `meanTurns` roughly quintupled, and `valueLoss` fell by
8x — the critic can finally fit targets that were previously asked to reconcile a
-5.0 penalty with a bootstrap through the same step.

What it did not achieve: the agent still ends every episode the same way. It is no
longer *rewarded* for idling; it simply has nothing better to do, and `meanTurns`
oscillates between 11 and 86 without trending up.

That is a different problem, and a better one. §5 predicted it: "the agent still
may not learn; if the policy cannot survive floor 1 with an honest reward, the
correct result is a policy that dies often." The agent does not die often either —
it stalls — and that points at the next question rather than at the reward
arithmetic being wrong again.

### What is now the blocker

**Superseded by §7. The paragraph below was written before the stall source was
instrumented, and its diagnosis turned out to be wrong.** It is kept because §7
is mostly an account of arriving somewhere else, and the reasoning error is part
of that.

`depthReward` is +10 against `turnCost` of 0.002, so descending one floor pays
5,000 turns of idling. The terms that would reward playing well — `KILL`,
`GOLD_GAIN`, `ITEM_PICKUP` — are all shaping terms, and the one concrete outcome
the agent can currently cause is a stall.

So the question is not "what number should `STALLED` be" any more. It is **what
does surviving look like, and which of those terms should be doing the work.**
That is `TODO.md` 1.8, and it is deliberately not answered here: this document
changed one thing and measured it, and changing two would make the measurement
above uninterpretable.

### What would have caught this sooner

The end-reason breakdown, which arrived as commit 1 of this work. For twenty
generations the diagnosis was reached by subtracting 5.0 from `meanScore` and
recognising the answer — a correct inference from an indirect signal. `ended
stalled 16 (100%)` is the same fact, read.

The breakdown is what made §7 possible: it said `STALLED` without saying *which*
guard produced it, and the guard turned out to be the entire answer.

## 7. The stall was not a reward problem at all

Sections 1 through 6 are about `WAIT`. The agent was not choosing to wait.

Instrumenting the two guards that can end an episode as `STALLED` — the idle
guard in `SPDEnv.settle` and `LevelPipeline`'s `Outcome.STALLED` — gave:

```
ended  stalled 36 (100%)     endDEATH 0   endTURN_LIMIT 0   endVICTORY 0
  STALL source:  idle 0   actorStepLimit 0   pipeline 36
```

**The idle guard fired zero times.** It cannot, in fact: it needs
`stallLimit` (120) consecutive turns of unchanged position and HP, and the
stalls were arriving at turns 10, 31, 24, 51, 168. §2.2 described a route that
produces a stall at turn 121, and the measured stalls were not at turn 121.

### The actual mechanism

`ActionMapper.apply` set `hero.resting = true` and nothing else:

```java
case REST:
    hero.resting = true;
    hero.next();
```

`Hero.act` branches on `curAction == null` *before* it branches on `resting`:

```java
if (curAction == null) {
    if (resting) { spendConstant( TIME_TO_REST ); next(); }
    else          { ready(); }
}
```

A resting hero therefore takes the rest branch forever. `ready()` is never
reached, control is never handed back, and no further action is ever read.

Nothing recovers it. The recovery path of the time refused a resting hero
outright — correct for a player, since a player escapes rest by choosing another
action. Headless input has nobody to do that. After 8 scheduler steps
`LevelPipeline` returns `STALLED`.

So `REST` was a one-way door, and the agent fell into it within a few dozen turns
of any policy that chose `REST` once. That is what "100% stalled" was measuring.

### Why every case in §4 passed anyway

Worth stating plainly, because it is the more useful half of this. The gate in
§4 asserted that stalling is not cheaper than dying and that the `STALLED` reward
is zero. Both were true, and both stayed true while the agent was being trapped
by something else entirely.

A check written against the reward arithmetic cannot detect a harness bug in the
mechanism that produces the episode. `rewardcheck` grew an 8th case only once
there was a specific invariant to assert — that resting is released by the next
action — and that case failed when the fix was deleted, which the §4 case would
not have.

### Measured

| | before | after |
| --- | --- | --- |
| stall share | 100% | **0%** |
| idle-guard stalls | 0 | 0 |
| pipeline stalls | 36 | **0** |
| bestDepth | 1 | **1** |

**`bestDepth` did not move.** It is 1 in every generation before this and 1 in
every generation after, and every one of the 147 recordings available at the
time is `depth=1`. Depth 2 has still never happened.

> **Correction, and the reason this section was wrong.** An earlier version of
> this table read `episodes reaching depth 2 | 0 | yes`, with the note "depth 2
> appears in training for the first time". It was taken from a trend line that
> printed `depth 1.0..2.0`. That `2.0` was not measured: `Graph.bar` widens the
> scale by 1 whenever a series is flat so that its division is defined, and then
> prints that widened span as the axis range. `bestDepth` was 1.0 in every
> generation, so the axis read `1.0..2.0` and the flat line read as a floor
> reached.
>
> Nothing else disagreed with it. Every replay file and every metrics row said
> depth 1, and a summary line is what a reader trusts over the raw column. See
> the graph fix in `CHANGELOG.md` (now `superintelligence/CHANGELOG.md`; upstream ships no root
> changelog, so there is only ever one in this repository) and the `graphcheck` gate.

So the REST fix is real and measured - 36 stalls became 0, and episodes run to
the turn cap instead of dying at turn 10 - and the milestone in `TODO.md` 1.4
is still unmet.

## 8. Two more faults, and why they were invisible

Both were unreachable while episodes ended at turn ~50. Fixing the stall made
episodes live long enough to reach them. Neither is a reward problem; both are
the agent's ability to *have an episode at all*.

### 8.1 Headless had no texture, and every texture dereferenced it

`TextureCache.get` returns `null` when `Gdx.gl == null`, which is the normal
state headless. Every `TextureFilm` constructor dereferenced that on the next
line.

Most films are built in **static initialisers**, so this did not need rendering
to fire — it needed the class to be *touched*. `Char.damage` reads `PHYS_DMG`
off `FloatingText`, whose `iconFilm` is such a field. So the path in was:

```
SEARCH, or simply living long enough
  -> Hunger.affectHunger -> Hero.damage
  -> Char.damage -> static init of FloatingText
  -> new TextureFilm(null, 7, 8) -> NPE
```

Thrown as an unhandled `ExceptionInInitializerError`, which killed the worker
process and took the generation with it. A plain `SEARCH` crashed on this too,
before the stall fix; it just could not be reached by a policy that ended at
turn 10.

The constructors now fall back to the requested frame size. That is not trying to
be correct — nothing renders headless — but it keeps the atlas math defined:
`cols` and `rows` come out 1 instead of dividing by zero.

Fixed in the constructor rather than at each call site, deliberately. There are
many static films; the next one touched would have had the same crash. Anything
that genuinely needs a real texture still gets `null` from the cache and will say
so.

### 8.2 Every death blew the stack

`HeadlessSprite.die` called `ch.die( ch )` — invoking the death callback to stand
in for an animation that does not exist headless. The callback re-enters
`Hero.die` → `Char.die` → `sprite.die` → the callback. `Hero.die` has a guard for
a repeated death, but it keys on the *cause*, and each bounce passed a different
`Char`, so it never matched:

```
FRAME 1016  HeadlessSprite.die(HeadlessSprite.java:118)
FRAME 1017  Char.die(Char.java:1130)
FRAME 1018  Hero.die(Hero.java:2237)
FRAME 1019  HeadlessSprite.die(HeadlessSprite.java:118)     ... to 1024
```

**This is the worst of the three.** A stall costs an episode; a null texture
costs a worker; this means the ending the whole reward function is built around
was unreachable. `StackOverflowError` is not a `DEATH`, so no terminal reward was
recorded, GAE had no terminal state to treat as terminal, and `endedNaturally`
was never set.

Now a no-op: the callback is the animation finishing, and there is none.

The new `rewardcheck` case cannot fail the way a gate normally does. Reinstating
the bug kills the check *process* with `StackOverflowError` before any assertion
runs. The mutation test cannot see a failure — it sees the JVM disappear, which
is the clearest possible confirmation of the diagnosis.

### What this pattern has in common

Every fault in this document was found by **driving paths that a weak policy
almost never reaches**, not by reading code. Hunger damage needs ~350 turns.
Death needs the hero to actually be in danger. Both were sealed behind an
episode that ended at turn 10.

That is the argument for fixing crashes in the order they are *found* rather
than in the order they are *listed*: the first two faults were invisible until
the third was fixed, and the third was invisible until the first two were. The
list order was the reverse of the dependency order.

## 9. Where this leaves the reward work

`TODO.md` 1.8 asked "what does surviving look like". It is now a question that
can be asked, which it could not be for §6.

The measured state after all three fixes, 6 generations, 6 workers, seed 12345:

```
gen          0      1      2      3      4      5
meanScore  186.1  118.7  114.5   75.7   59.1   66.6
meanTurns  1500   1500   1500   1500   1500   1500
endSTALLED    0      0      0      0      0      0
endTURN_LIMIT 36     36     36     36     36     36
endDEATH      0      0      0      0      0      0
valueLoss   1.79   0.92   1.18   0.60   0.46   0.57
bestDepth     1      1      1      1      1      1
```

Every episode now runs the full 1500 turns. No stalls, and — worth flagging
plainly — **no deaths either**, which the death fix makes newly possible to
observe: `endDEATH` is 0 because the policy wanders away from everything rather
than because dying is unreachable.

`meanScore` falling from 186 to 59 with `valueLoss` falling alongside it is the
critic fitting a longer, more varied episode, not a collapse. `bestDepth` is 1
in every generation, so nothing has reached a second floor.

Two things are now genuinely open, and neither is a stall:

1. **The agent survives but does not progress.** 1500 turns of `turnCost` is the
   only cost being paid, and `depthReward` is +10 against 0.002 per turn. There
   is still no term that pays for the thing the objective actually wants.
2. **Nothing dies, so the death penalty is untested in training.** It is now
   correct and gated, but no run has yet produced one.

Neither should be answered by tuning numbers in the same commit as the fixes
above. The measurement in §9 is only interpretable because each of the three
faults was fixed and measured on its own.

## 10. A fourth fault, found while checking the viewer

Sections 7 and 8 fixed three faults. A fourth turned up while investigating why
the desktop viewer still reported a divergence the headless verifier no longer
saw, and it is the one that had been reaching training the longest.

`OPEN_INVENTORY` and `CANCEL` were missing from `ActionMapper.apply`'s switch, so
both fell through to `pickInteractCell()` + `handleCell()` - the path
`INTERACT` takes. Choosing to look in your backpack therefore also acted on an
adjacent cell: moved the hero, attacked a mob, picked up a heap, opened a locked
door, or took a floor transition.

It was documented in place as a no-op, and that comment is why it survived. The
note was about the mode assignment in `SPDEnv`, which mattered at the time; the
cell handling underneath it was never examined. `pickInteractCell` finds
something and `handleCell` acts on it, so the fall-through was never nothing.

It is legible in the recordings. On one run the hero moves a cell on an
`OPEN_INVENTORY` step and moves back on the next:

```
step 41  OPEN_INVENTORY/0   before 834   after 869
step 46  OPEN_INVENTORY/0   before 869   after 834
```

**Why it matters more than its size suggests.** The agent was credited for two
things at once whenever it opened its inventory, and `depthReward` is the term
this whole document is trying to get the agent to want. A term that fires
because the agent chose `INTERACT` while it believed it had chosen
`OPEN_INVENTORY` is not a term that can be reasoned about. It also means the
recordings before this fix contain positions that no correct run reproduces,
which is why several of them now report as diverged - they were captured under
the bug.

This is the fifth instance in this document of the same shape: a fault found by
driving something rather than by reading it, sitting behind a fault that had to
be fixed first. The pattern is in §8 and it held again.

### Still open

The desktop viewer and the headless trainer implement "advance to the next
decision point" separately - `LevelPipeline.runToHeroReady` versus
`ReplayPlayer.settle` - and they do not agree step for step. Freshly recorded
runs verify exactly under the trainer and still diverge in the viewer, usually by
one cell at an early step. Both are individually reasonable; they are simply two
implementations of one rule.

The fix is to have one implementation, which in practice means the viewer should
drive the trainer's `SPDEnv` rather than the live game. That is a redesign of
the viewer rather than a patch, and it is not done. What is done is that the
divergence message now names the action, the mode and both positions, so the
disagreement is diagnosable rather than just reported.


