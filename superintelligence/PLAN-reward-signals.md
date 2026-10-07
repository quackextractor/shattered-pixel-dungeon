# Reward signals - the stall strategy

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

- **The agent still may not learn.** Nothing here makes the environment easier.
  If the policy cannot survive floor 1 with an honest reward, the correct result
  is a policy that dies often, which is progress of a different kind.
- **`depthReward` of +10 against `deathPenalty` of -100 is not obviously the
  right shape** and has not been examined. It is deliberately untouched here,
  because changing it and the stall penalty together would make the next result
  uninterpretable.
- **The curriculum is still inert** (`observe()` is never called, so shaping is a
  constant 1f). It stays inert until a run with an honest terminal reward can be
  judged, for the same reason this plan exists.
- **`KILL` still has no emission site.** Same reasoning.

## 6. Verification

Baseline to beat, from the run that motivated all of this:

```
meanScore   -> -5.00 (converged on STALLED)
meanTurns   -> 8-11
bestDepth   -> 1
STALLED     -> (not measured; inferred from score arithmetic)
```

Success looks like: the `STALLED` share of end reasons falling away, `meanTurns`
rising toward 150+, and `meanScore` no longer converging on any single terminal
reward. `depth` moving off 1 would be the real result, but that is
`TODO.md` 1.4 and it is a longer run than belongs in this commit.

That last distinction is the reason commit 1 is instrumentation rather than a
fix: the `STALLED` share above is currently *inferred* from `meanScore` being
-5.0. It should be read, not inferred.
