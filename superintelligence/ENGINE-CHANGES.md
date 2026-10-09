# Changes to the game, and why each one is here

Everything this project has changed in `:core` and `:SPD-classes`, and what each change cost or bought.

The rule `instructions.md` §12.3 sets is that engine *logic* changes stay out of a commit unless
proposed, while getters and observation-only additions may be committed freely. This file is the
proposal record, and it is ordered by what each change is rather than by when it was made.

**Nothing here changes what the game does.** Every entry is one of:

- a **getter**, added so an observer can ask a question the engine would not answer;
- an **instrumentation** change, which makes randomness countable without altering which values come
  out;
- a **presentation draw moved off the gameplay RNG stream** onto `PRandom`, which is what that stream
  exists for and which no gameplay outcome depends on.

The last category is the one that looks like a behaviour change and is not, and it is also the one that
did real damage: see §4 for the twelve values it cost and the fourteen recordings it broke.

`superintelligence/README.md` lists these under "Changes to the game" without the reasoning, and
`superintelligence/FINDINGS-viewer-fidelity.md` holds the measurements.

---

## 1. Getters

Each one exists because an observer needed a fact the engine kept to itself, and each is a pure read.

| Addition | Why |
| --- | --- |
| `Bones.clear()` | Forgets a fallen hero's remains. Nothing in the game calls it — a normal playthrough wants the opposite — because an environment playing thousands of independent runs in one process cannot have the world depend on which hero died last. Found by `paritycheck`: 2 of 32 recordings diverged, both carrying the wrong hero class's remnant. |
| `Actor.clear()` reachability | Reached through `Dungeon.init()`, which already empties the actor registry. Called from `RunState` only where it is safe to. |
| `Random.reseedBase(long)` | The base generator is created unseeded, which is fine for one playthrough — it is created once and consumed sequentially — and fatal for replaying a run in a second process. Seeds the base generator in place, leaving anything pushed on top alone, and reseeds `PRandom` with it. |
| `RandomTrace` | Counts and fingerprints every draw, with optional per-call-site attribution. Off by default; one branch per draw when nobody is measuring. Exists because the trace is the only artefact that can compare a headless run against the rendered game. |
| `Actor.currentActor()` | Who the scheduler handed a turn to. Lets an observer name which actor acted rather than only how many turns passed. |
| `Mob.currentEnemy()`, `Mob.enemySeen()`, `Mob.alerted()` | The same for a mob's decision, so a trace can say what it was doing rather than inferring it from a cooldown. |
| `MovieClip.animationInFlight()` | The only way to ask whether an action's effect is still pending. `CharSprite.isMoving` is set by movement and nothing else, so an attack in flight is invisible to it — and whether an attack is in flight is exactly what decides whether the attacker's turn has been spent. |
| `GameScene.answerableWindow()` | `GameScene.show()` parks a dialog in `headlessWindow` **only when there is no scene**. So a caller reading that slot directly saw no dialog in the rendered game, and a recorded `MENU` step resolved against nothing while `ReplayController` deliberately left the `WndOptions` on screen forever. This prefers the scene's live window and falls back to the slot. |
| `PRandom.element(Collection)` | Mirrors `Random.element` for the presentation stream, so a caller choosing presentation can be moved across without reimplementing the indexing. |

## 2. Null-tolerance for a bypassed renderer

The headless build leaves `Gdx.gl` null rather than mocking it, so every presentation path has to
survive that. Each of these is a guard, not a stub: with a renderer present every one is a no-op.

| File | Change |
| --- | --- |
| `GameScene` | `show`, `selectCell`, `checkKeyHold`, `resetKeyHold` handle a null scene or cell selector, and stash pending dialogs and aiming requests for a headless caller |
| `Button.press()`, `WndOptions.optionCount/optionSelectable/selectOption` | let a caller drive a dialog with no UI |
| `CharSprite.sprint/updateArmor/read/fall` | presentation hooks moved up from `HeroSprite` and `MobSprite`, so game logic stops depending on the concrete sprite type |
| `Item.throwAt()` | the sprite-free half of `Item.cast` |
| `Chrome.get()`, `ShadowBox`, `NinePatch`, `TextureCache`, `PlatformSupport` | tolerate a null GL context |
| `ItemSpriteSheet.Icons.film()` | the icon film is built on first use, so an item constructor no longer forces a texture decode. The icon *indices* are game data and are still pure arithmetic |
| `TargetHealthIndicator`, `AttackIndicator`, `QuickSlotButton` | null guards for headless operation |

These are load-bearing for a code path most contributors will never run, which is worth stating
rather than leaving implicit.

## 3. Determinism of the turn order

`Actor.all`, `Actor.chars`, `Level.mobs` and `Level.blobs` were hash-based collections keyed on
identity hash codes, so their iteration order differed between JVM runs. `Actor.headlessStep` broke
time ties on iteration order, which meant *who moved first* varied.

- `Actor.all` and `Actor.chars` are now `LinkedHashSet`, `Level.mobs` a `LinkedHashSet`, `Level.blobs`
  a `LinkedHashMap`. Membership semantics unchanged.
- `Actor.headlessStep` breaks remaining ties on `Actor.id()`, which is assigned in creation order and
  is stable.
- `Random.resetGenerators()` pushed an unseeded generator and `Dungeon.init()` called it *after*
  pushing the seeded stack, discarding it. `reseedBase` replaced it.
- `EntranceRoom.placeEarlyGuidePages` pushed an unseeded generator deliberately — "so meta progression
  doesn't affect levelgen", which is the right intent and made the first guidebook page land on a
  different tile in every process. Seeded from the floor's own seed with a fixed offset.
- A second pass: `Char.buffs(Class)` handed callers a `HashSet`, `Random.chances(HashMap)` chose secret
  room contents off a `Class`-keyed map, `Mob.chooseEnemy` resolved ties by iteration order, and
  `CursingTrap` / `VaultLevel` / `Hero` used `Collections.shuffle`, which ignores the seeded generator
  entirely. Insertion-ordered, or switched to `Random.shuffle`.

## 4. Presentation draws moved to `PRandom`

**This is the one that broke fourteen recordings.**

`PRandom` was introduced for exactly this and is used by particles, emote icons, music, sewer ambience
and sound pitch. Four draws were missed, and only the rendered game makes any of them, so the viewer's
gameplay RNG stream ran away from the trainer's.

| File and line | Draw | Moved from/to |
| --- | --- | --- |
| `CharSprite.link` | `turnTo( ch.pos, Random.Int( level.length() ) )` — the random facing a sprite is given when it is linked | `Random` → `PRandom` |
| `AttackIndicator.checkEnemies` | `Random.element( candidates )` — which mob the overlay highlights | `Random` → `PRandom.element` |
| `Wand.staffFx` | `particle.speed.polar( Random.Float(2π), 2f )` — which way a cosmetic particle flies | `Random` → `PRandom` |
| `MagesStaff.StaffParticle.update` | `Random.Float( sizeJitter )` — cosmetic size jitter | `Random` → `PRandom` |

`CharSprite.link` accounts for the entire base-stream cost: **12 values per run**, one per actor the
viewer attaches a real sprite to. `HeadlessSprite` overrides `link()` and never reaches that line, so
the trainer never made the draw and the viewer always did.

Measured, on `warrior-long`, recorded seed `warrior-long` → 2547414048295, scrambled
2466687093574357806. Computing the base generator's expected output from that seed and looking up where
each side's first gameplay draw falls:

```
headless first gameplay draw = generator value #5
viewer   first gameplay draw = generator value #17
```

So every damage roll, defence roll and mob decision after the first draw came from a different point
in the stream than the recording was made from. On that recording the hero's recorded `INTERACT` at
step 13 left the rat at 3/8 in the trainer and killed it in the viewer — one roll differing by enough —
and everything after it. Thirteen of the fourteen failures reported health and one reported engine time,
which is what that one cause looks like from the outside.

`gradle :desktop:viewcheck`: **14 of 17 diverged → 0 of 17.**

### Left alone deliberately

- **`DungeonTileSheet.setupVariance`** draws 962 times during generation, and the viewer makes it while
  headless does not. It draws inside a `Random.pushGenerator(seed)` / `popGenerator()` pair, so it
  consumes a stream that is discarded and cannot affect any outcome. Moving it would be churn for its
  own sake, and the generation-window tally records it so the difference is visible rather than
  surprising.
- **`HeadlessSprite`'s inert emitter** deliberately *does* draw `Random.Float(interval)` where a real
  `Emitter` would, so the two consume the same number of values. That is the right shape for a
  substitution that stands in for the real thing, and it is the opposite of the fault above: this one
  compensates, the four above failed to.
- **`Sample.INSTANCE.play` sound pitch** in `Hero.move` and 33 other sites is still on `Random`. It is
  the largest remaining presentation draw on the gameplay stream and it is symmetric — both
  environments make it — so it does not currently separate the two. It is worth moving on its own
  merits.

## 5. Instrumentation that had to become honest

Two instrument changes, both of which are the reason the fault above survived as long as it did.

**`Random.Int(int, boolean)` now records its draws.** It did not, and neither did
`Random.shuffle(List)`, which handed the generator straight to `Collections.shuffle` and let it drive.
Only `Float()` and `Long()` were counted. So `RngTrace` reported identical base-draw counts at every
step of every recording while the streams were sixteen values apart, and `PLAN-replay-parity.md` §1.4
concluded from that — correctly, given the instrument — that the stream was not offset.

`shuffle` is routed through `Int` with its permutation **unchanged**: `Collections.shuffle` walks the
list downwards taking `nextInt(i + 1)` at index `i - 1`, which is exactly the replacement loop. Getting
that order wrong would reshuffle item generation for every run in the game, so `observecheck` asserts
the property rather than trusting it.

**`RngTrace` records the generation window's draw tally** as a `# generationSites=` header line. It is
the one part of a run the per-step comparison deliberately excludes, so it had nowhere to be looked at,
and it is where an offset introduced before playback begins appears with nothing else to point at it.

**`RandomTrace` also gains `onSoleGenerator()`'s answer being used consistently.** `Random.Float` and
`Random.Long` already classified a draw as base by generator identity (`peekFirst() == peekLast()`)
rather than by `!useGeneratorStack`; an earlier version used the latter, which on an empty stack
classified every ordinary gameplay draw as *non*-base and left `baseDraws` at zero for entire runs —
reading exactly like a simulation that consumes no randomness at all.

## 6. Gated, so none of this can come back silently

| Gate | What it refuses |
| --- | --- |
| `observecheck` | All eighteen public `Random` draw paths and `shuffle` must move the counter. Removing the `record` call from `Int` fails two of its six checks and names every affected path. Without this, a count that agrees for the wrong reason reads as parity. |
| `observecheck` | Encoding an observation or capturing quickslots draws nothing — the invariant whose one violation, `HeroEncoder` calling `hero.drRoll()`, offset the stream from the first floor of every run. |
| `resetcheck` | A reset is a function of its arguments. Needs two episodes in one process, because with one there is nothing to leak from — which is why every single-episode check passed while a static listener carried an aim across every reset. |
| `paritycheck` | A recording survives a write, a read, and a second episode. The only gate that lets the hero die repeatedly, which is where `Bones` becomes visible. Found that fault on its first run. |
| `paritycheck` | A run following a hero's death inherits none of that hero's remains. |
| `slotcheck` | A recorded slot index resolves to the item it was recorded against — measured on `KXY-JHB-LXK`, where a recorded `DROP` dropped a `Waterskin` in one environment and a `VelvetPouch` in the other because the quickslot bindings differed. |
| `:desktop:viewcheck` | Every committed recording plays clean in the **real** viewer. Needs a display, so it is outside `gates` and is a manual gate. |

## 7. Known remaining gaps in the engine changes

- **`viewcheck` is not in `gates`** and stays out: it needs a display, forks a JVM per recording, and
  costs about 200 seconds sequential. That is a real hole rather than a decision — this class of
  regression returns silently between manual runs.
- **Sound pitch still draws from `Random`** in 34 places, symmetric across both environments.
- **`observecheck` enumerates the draw paths that exist.** A nineteenth added later does not appear in
  its list, so the gate protects what it knows about rather than what is added next.
- **`Mob` and `Char` do not override `hashCode`.** Nothing found iterates them through a hash
  collection during play, but that is an invariant a future change could break.
- **`Game.elapsed` cannot be pinned from outside.** It is derived in `Game.update` before any game code
  runs, so animation timing remains frame-rate dependent and a future timing fault will need an engine
  change at the point the delta is measured.
