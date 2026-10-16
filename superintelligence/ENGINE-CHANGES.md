# Changes to the game, and why each one is here

Everything this project has changed in `:core` and `:SPD-classes`, and what each change cost or bought.

The rule `instructions.md` §12.3 sets is that engine *logic* changes stay out of a commit unless
proposed, while getters and observation-only additions may be committed freely. This file is the
proposal record, and it is ordered by what each change is rather than by when it was made.

**Almost nothing here changes what the game does.** Every entry is one of:

- a **getter**, added so an observer can ask a question the engine would not answer;
- an **instrumentation** change, which makes randomness countable without altering which values come
  out;
- a **presentation draw moved off the gameplay RNG stream** onto `PRandom`, which is what that stream
  exists for and which no gameplay outcome depends on.

The last category is the one that looks like a behaviour change and is not, and it is also the one that
did real damage: see §4 for the twelve values it cost and the fourteen recordings it broke.

**One entry is a fourth thing**, and it is called out where it lives: `Level.drop` widens a guard so a
renderer-bypassed process presses the cell a thrown item lands on (§7). In a game with a renderer the
guard's answer is bit-for-bit what it was, because the new term is only ever set by a process that has
no scene — but it does change what a *headless* run does, and that is the whole point of it.

`superintelligence/README.md` lists these under "Changes to the game" without the reasoning, and
`issues.md` 31 holds the measurements, recovered from the findings record that has since been removed.

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
| `GameScene.headlessGameplay()` | Declares that this process is simulating gameplay with the renderer bypassed. `Level.drop` presses the cell a thrown or dropped item lands on only when the current scene is a `GameScene`, which separates gameplay drops from the two scene drops that must not press — `AlchemyScene` crafting onto the cell the hero stands on, and `InterlevelScene.resurrect` dropping the LostBackpack on the cell he died on. A headless process has no scene at all, so the test was false and every thrown item landed inert: measured, an armed `WornDartTrap` was still armed after a `Food` was dropped on it on four seeds out of four. Both scene drops need a renderer, so neither can exist here, which is what makes the flag safe. See §7. |
| `PRandom.element(Collection)` | Mirrors `Random.element` for the presentation stream, so a caller choosing presentation can be moved across without reimplementing the indexing. |

## 2. Null-tolerance for a bypassed renderer

The headless build leaves `Gdx.gl` null rather than mocking it, so every presentation path has to
survive that. Each of these is a guard, not a stub: with a renderer present every one is a no-op.

| File | Change |
| --- | --- |
| `GameScene` | `show`, `selectCell`, `checkKeyHold`, `resetKeyHold` handle a null scene or cell selector, and stash pending dialogs and aiming requests for a headless caller. `show` parks the window in a one-slot `Group` rather than in a bare field, so the game's own `Window.hide()` closes it — a bare field has no parent to erase from, so a dialog whose handler declines the click (Chasm's "jump?" prompt does, until it has been up a fifth of a second) used to be reported answered by the caller and removed anyway. `answerableWindow()` reads the accessor rather than the field for the same reason |
| `Button.press()`, `WndOptions.optionCount/optionSelectable/selectOption` | let a caller drive a dialog with no UI |
| `Image` | the five-argument constructor and `frame(int,int,int,int)` tolerate a null texture. `frame(RectF)` had carried the guard since the bypass landed and the two callers that derive the frame rectangle *from* the texture had not, which is how one hero stepping at a chasm killed a worker: `Chasm.heroJump` builds its "jump?" prompt out of `new Image(Dungeon.level.tilesTex(), ...)` and there is no renderer to load that texture |
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
  entirely. Insertion-ordered, or switched to `Random.shuffle`. One was missed - `Mob.holdAllies` -
  and is proposed and applied uncommitted; see section 7.

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
- **`Sample.INSTANCE.play` sound pitch** at 11 sites is still on `Random`. It is the largest remaining
  presentation draw on the gameplay stream and it is symmetric — both environments make it — so it does
  not currently separate the two. It is worth moving on its own merits.

  The 11, counted by scanning `core` and `SPD-classes` for a `Random.*` draw passed as the pitch
  argument of `play` / `playDelayed` / `hitSound` / `blockSound`:

  | Site | Note |
  | --- | --- |
  | `Hero.move` × 5 (`Hero.java:2337`, `:2339`, `:2344`, `:2346`, `:2349`) | one per terrain type the step lands on |
  | `Char.takeDamage` × 2 (`Char.java:502`, `:503`) | `blockSound` then `hitSound` |
  | `HolyLance.java:126`, `GreatCrab.java:104`, `WandOfLivingEarth.java:178` | |
  | `WndSettings.java:931` | `Random.oneOf` over sound ids, not a pitch |

  An earlier version of this section said 34. That was wrong; 11 is the measured number, and the
  neighbouring sites that already read `PRandom` (`GreatCrab.java:88`, `WandOfLivingEarth.java:111`,
  `:167`, `Char.java:384`, and others) are what a partial conversion leaves behind.

## 5. Instrumentation that had to become honest

Two instrument changes, both of which are the reason the fault above survived as long as it did.

**`Random.Int(int, boolean)` now records its draws.** It did not, and neither did
`Random.shuffle(List)`, which handed the generator straight to `Collections.shuffle` and let it drive.
Only `Float()` and `Long()` were counted. So `RngTrace` reported identical base-draw counts at every
step of every recording while the streams were sixteen values apart, and `issues.md` 31 §1.4
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
| `observecheck` | All 17 public `Random` draw paths and `shuffle` must move the counter. Removing the `record` call from `Int` fails two of its six checks and names every affected path. Without this, a count that agrees for the wrong reason reads as parity. |
| `observecheck` | Encoding an observation or capturing quickslots draws nothing — the invariant whose one violation, `HeroEncoder` calling `hero.drRoll()`, offset the stream from the first floor of every run. |
| `resetcheck` | A reset is a function of its arguments. Needs two episodes in one process, because with one there is nothing to leak from — which is why every single-episode check passed while a static listener carried an aim across every reset. |
| `paritycheck` | A recording survives a write, a read, and a second episode. The only gate that lets the hero die repeatedly, which is where `Bones` becomes visible. Found that fault on its first run. |
| `paritycheck` | A run following a hero's death inherits none of that hero's remains. |
| `slotcheck` | A recorded slot index resolves to the item it was recorded against — measured on `KXY-JHB-LXK`, where a recorded `DROP` dropped a `Waterskin` in one environment and a `VelvetPouch` in the other because the quickslot bindings differed. |
| `presscheck` | A dropped item fires the trap it lands on and tramples the grass it lands on, and level generation — which runs with no current level — presses nothing. The second half is what stops widening the guard from becoming a loosening. |
| `:desktop:viewcheck` | Every committed recording plays clean in the **real** viewer. Needs a display, so it is outside `gates` and is a manual gate. |

## 7. Known remaining gaps in the engine changes

- **`viewcheck` is not in `gates`** and stays out: it needs a display, forks a JVM per recording, and
  costs about 200 seconds sequential. That is a real hole rather than a decision — this class of
  regression returns silently between manual runs.
- **Sound pitch still draws from `Random`** at 10 sites, symmetric across both environments, and it
  is **declined rather than deferred** - see `REJECTED.md`. Moving them is a one-line change per
  site and it invalidates every recording in the committed corpus.
- **`Mob.holdAllies` already draws from the seeded stream - and it is committed, contrary to what the
  rest of this section used to say.** `Mob.java:1782` is `Random.shuffle(candidatePositions)` and it
  landed in commit `b4f717992`, whose message is about `rolloutcheck`. An earlier version of this
  entry recorded it as applied and uncommitted and `CHANGELOG.md` repeated the claim; both were wrong
  about the tree. Everything else below it still holds: the site was missed by 3, `Collections.shuffle`
  hands the generator to the JDK's own and so ignores the game's seeded stream entirely, and no
  recording in the corpus reaches `holdAllies`, which is why nothing would have noticed either way.
  `LevelPipeline.handleFall` still does not call it, for the same reason: `InterlevelScene.fall()`
  opens with `Mob.holdAllies`, and copying that would put a draw into every fall in the trainer.
- **`observecheck` enumerates the draw paths that exist** - 17 of them, in `ObserveCheck.java:224-241`. An eighteenth added later does not appear in
  its list, so the gate protects what it knows about rather than what is added next.
- **`Mob` and `Char` do not override `hashCode`.** Nothing found iterates them through a hash
  collection during play, but that is an invariant a future change could break.
- **`Game.elapsed` cannot be pinned from outside.** It is derived in `Game.update` before any game code
  runs, so animation timing remains frame-rate dependent and a future timing fault will need an engine
  change at the point the delta is measured.
- **`Levitation.detach()` only re-presses the hero's cell when a `GameScene` exists** (`Levitation.java:58`),
  and `Level.drop` only calls `pressCell` under a scene test too (`Level.java:1068`). A gameplay guard
  keyed on a scene — the same shape as the animation clock above.

  The levitation half is covered from the supervisor. `LevelPipeline.runToHeroReady` makes the
  decision the guard was suppressing and calls the game's own `Chasm.heroFall`, so the outcome matches
  without `:core` changing. The guard itself is still in the game.

  **`Level.drop`'s half is now closed too, and it is the only change here that widens a condition rather
  than tolerating a null.** One line in `:core`:

  ```java
  if (Dungeon.level != null && (ShatteredPixelDungeon.scene() instanceof GameScene || GameScene.headlessGameplay())) {
      pressCell( cell );
  }
  ```

  **`GameScene.headlessGameplay` is a static that only `HeadlessGame.install()` sets, so a real game
  never reads it as anything but `false` and the guard's answer there is unchanged.** That is the part
  that makes it a different change from the one that was tried and reverted: that one deleted the scene
  test, which altered alchemy and resurrection in a game that is not this module's. The scene test is
  still there, still doing its job — the flag only answers the question the headless process cannot
  pose, and it answers "gameplay", which is correct because `AlchemyScene` and `InterlevelScene` both
  need a renderer and so cannot exist in the process that sets it.

  `Level.drop` is the only chokepoint: `Item.onThrow` and `Item.doDrop` both call it and nothing else
  puts an item on a floor, which is why one line covers throwing, dropping, the blacksmith, the shop,
  the imp and the rest. Measured after: an armed `WornDartTrap` disarms, and a `Food` dropped on high
  grass changes its terrain 15 → 2.

  `presscheck` is the gate, is **in `gates`**, and is green on three cases. `duelist-throw.replay` is
  the recording that makes a *recorded* press reproducible in both environments — see `CHANGELOG.md`.


## 8. An item that creates a mob leaves it with no sprite - **COMMITTED, GATED**

Found by a 30-generation training run under `reward.profile=depth_only`, 16 workers, seed 20261013. The
agent **used a Scroll of Mirror Image**, and two workers died with the same `NullPointerException`:

```
java.lang.NullPointerException: Cannot assign field "visible" because "this.sprite" is null
  at Char.move(Char.java:1334)
  at Mob.move(Mob.java:756)
  at ScrollOfTeleportation.appear(ScrollOfTeleportation.java:296)
  at ScrollOfMirrorImage.spawnImages(ScrollOfMirrorImage.java:85)
  at ScrollOfMirrorImage.doRead(ScrollOfMirrorImage.java:50)
  at Scroll.execute(Scroll.java:189)
  at SlotAction.use(SlotAction.java:96)
  at SPDEnv.step(SPDEnv.java:262)
```

**Not exotic, and reachable on floor 1** - it needs only a mirror image scroll and a policy that reads
things it finds, which is what a random policy does within a hundred turns. The same class of fault as
§1's unguarded `SentryRoom` and `ArmoredStatue` sprite casts: a hand-written policy never read a scroll,
and this module's own has now.

**The gap is `GameScene.add( Mob, float )`.** It adds the mob to `Dungeon.level.mobs` and then, only
`if (scene != null)`, gives it a sprite and puts it in `Actor.chars`. A renderer-bypassed process has no
scene, so a mob created by an item path is left with **no sprite at all**, and `Char.move` assigns
`sprite.visible` unconditionally for any `Char` that is not the hero. `ScrollOfMirrorImage` does the
right thing by the game's own lights - it calls `GameScene.add( mob )` before `appear()` - which is
exactly why the gap was invisible until something ran it.

**`GameScene.add( Heap )` already answers this shape.** With no scene it builds the `ItemSprite` the game
logic needs and links it, and its comment says why: *game logic calls `heap.sprite` unconditionally once a
heap exists*. The mob branch has no such branch, and the mob is the one that moves.

Two changes, both uncommitted, both no-ops in a game with a renderer:

```java
// GameScene.java - the scene-less branch, mirroring add( Heap )
} else if (headlessGameplay && headlessMobSprite != null ) {
    if (mob.sprite == null ) headlessMobSprite.accept( mob );
    Actor.add( mob );
    mob.spendToWhole();
}
```

```java
// MirrorImage.java - the identical guard SentryRoom.act already carries, seventeen lines above
if (sprite instanceof MirrorSprite) ((MirrorSprite)sprite).updateArmor( armTier );
```

`GameScene.headlessMobSprite( Consumer<Char> )` is a new static in the same shape as the existing
`headlessEmitter`: a stand-in the scene would otherwise supply, set by `HeadlessGame.install()` and by
nothing else, so a real game never reads it. `Actor.add` is in the same branch for the reason
`RewardCheck.placeMobBesideHero` documents it: without it the mob is in `level.mobs` but not in
`Actor.chars`, so it never acts and `Actor.findChar` - which `Hero.handle` reads to decide whether a cell
holds a mob - cannot see it.

**The second change is not cosmetic to argue about, and it was found by the first.** With only the
`GameScene` half, the probe stopped crashing with an NPE and started crashing with
`ClassCastException: HeadlessSprite cannot be cast to MirrorSprite` - `MirrorImage.act` casts its sprite
whenever the hero's armour tier changes, which is on the image's first turn. `MirrorSprite` itself cannot
be built headlessly, measured: `new MirrorSprite()` dies with `NullPointerException: Cannot read field
"width" because "texture" is null`. So a stand-in that satisfied the cast is not available, and the guard
is the honest shape - the same one the project already applied twice to the same mistake.

Measured after both, on `MIRRORPROBE` with a real `ScrollOfMirrorImage` used through `SlotAction.use`:

```
PROBE slot after refresh=4
PROBE result=no crash
PROBE mobs 8 -> 10, mirror images=2 with sprite=2 in Actor.chars=2
```

**It is committed and gated.** On the owner's word, with `gradle :superintelligence:mobspritecheck` as the
gate: 3 cases, **in `gates`**, mutation-tested by reverting each half, and the third case is the one that
makes the rest safe to ship - with `headlessGameplay` off, `GameScene.add( mob )` never consults the
stand-in factory, asserted rather than argued. `verifyall` green and `:desktop:viewcheck` green at 20 of 20,
which is the one that matters here because this file is `GameScene` and the viewer loads it.

**What it still does not cover.** Other item paths that create a mob through a route `GameScene.add( Mob )`
does not guard - a `SummoningTrap`-style spawn does go through it, and that one was already right, but
nothing has enumerated every `createMob` call in the game. The guard is at the chokepoint rather than at
the twelve call sites, which is the same argument §7 makes for `Level.drop`, and it has the same limit:
a path that builds a mob and never adds it through `GameScene` is still uncovered.
