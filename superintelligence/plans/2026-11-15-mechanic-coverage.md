# Full Mechanic Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every game mechanic in the denominator below (969 keys) is covered by a scenario that passes `scenariocheck` with reach `POLICY` and replays clean in `:desktop:scenarioview`, and a committed gate proves it.

**Architecture:** A scenario declares the keys it covers (`Scenario.covers`). A new gate `coveragecheck` scans the core jar for the denominator, subtracts an explicit exclusion list, and fails naming every key no scenario declares. `scenariocheck` additionally refuses a declaration it never witnessed in the run. A `pending.txt` ratchet lets each task commit green while coverage grows; the last task deletes it. Bulk coverage comes from parameterised families (one table per category, as `RangedScenarios` loops over wands); hand-written scenarios only for bosses, quests, rooms and talents with special triggers.

**Tech Stack:** Java 17+, Gradle (`:superintelligence`, `:desktop`), the scenario framework in `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/scenario/`.

**Spec:** the task brief of 2026-11-15 (full coverage, everything passing); audit `superintelligence/out/evidence/mechanic-audit/static.txt`; binding rules `CLAUDE.md`, `superintelligence/AGENTS.md`, `superintelligence/testing-guide.md`.

## Global Constraints

- No code comments of any kind: no `//`, `/* */`, Javadoc, headers. Names carry meaning. Delete lengthy comments in code you touch.
- 500-line limit per file (600 for tests) outside `core`, `SPD-classes`, `desktop`; split a family file by category before it passes 500. Never refactor `core`/`SPD-classes`/`desktop` for line count.
- Conventional Commits 1.0.0. Never `--no-verify`. No `Co-Authored-By`, no "Generated with", no tool or model name, no `noreply@` in any commit or PR text.
- No emoji anywhere. Logs use `[OK]` / `[WARN]` / `[ERROR]`.
- Never stage `superintelligence/AGENTS.md`. Stage by explicit path, never `git add -A` / `git add .`. Every new file must be staged: `hooks/pre-commit` moves untracked files aside and runs `verifyall` on the staged tree only.
- Engine boundary: `core`/`SPD-classes` logic edits are for local testing and mutation only and are reverted before commit. Getters and observation-only additions may be committed. Fidelity fixes go in `superintelligence/` or `desktop/.../replay/`.
- Replay fidelity outranks the trainer and outranks coverage. Never weaken a scenario, assertion, verify, or viewer check to make it pass.
- A reach `GAP` is closed in the action space (mask, `EpisodeCollector`, `SlotAction`, `WindowBridge`, a new appended `Action`), never listed in `ReachExpectations.EXPECTED` or `KNOWN_FAILURES`.
- Artefacts go under `superintelligence/out/`; anything a later reader needs goes to `superintelligence/out/evidence/mechanic-coverage/` in the same session. Nothing to the system temp dir.
- One gate or job at a time, each under `timeout`. Run `scenarioview` only with a `-PscenarioOnly` filter until Task 35. Never run a gate alongside a training run.
- After three failed attempts on a build, test or gate step: stop, report each attempt with the exact error and the diagnosis. Never disable or delete a failing scenario to pass.
- Catalogue loading must draw no RNG and build no game object: families may reference `Class` literals and enum constants at load time, and create game objects only inside `Setup`/`Script` lambdas (`ScenarioCheck` self-test "loading the catalogue draws nothing").
- Shell: commands below are Git Bash. In PowerShell, run them one per line, never `&&`.

## Review Focus

1. **A vacuous effect predicate.** A predicate ORed with something already true before the trigger passes with the mechanic broken. Every family predicate compares against a value captured immediately before the trigger. Pinned by each task's mutation step (the scenario must go red with the mechanic removed).
2. **Generation state leaking out of a room scenario.** The prepare hook sets `SpecialRoom.runSpecials` / `SecretRoom.runSecrets`; a later scenario or the viewer could inherit it. Task 17 adds a self-test that runs `room.ArmoryRoom` then `melee` in one process and requires `melee` to verify with the queue restored, proved red by a local no-op of `SpecialRoom.initForRun`.
3. **The new actions changing old recordings.** `ITEM_MENU` and `INDICATOR` must be appended after `THROW` so every existing action index is unchanged; `verifyall`'s corpus playback and `paritycheck` must stay bit-identical. Task 19 runs `paritycheck` and requires the corpus to play unchanged; Tasks 20 and 21 change the ALCHEMY menu indices and run `paritycheck` too.
4. **Pinned seeds rotting.** A scenario whose effect is a rare random outcome uses a seed found by search; a generation change can make it `NOFIXTURE`. The gate then fails (correct). The fix is re-pinning by search, never dropping the scenario. Task 2 adds `--seeds N` search support and the rule to `testing-guide.md`.
5. **A declaration that the run never exercised.** Declaring `Bat` on a scenario that spawns a `Rat` must fail. Task 2 adds the witness and a self-test that a scenario declaring an unseen class fails with `declared covers never witnessed`.

---

## Definition of covered

A key is covered when a scenario (a) declares it with `covers(...)`, (b) drives it through the real `SPDEnv.step` via `Driver`, (c) passes with status `OK` and reach `POLICY` (`judgeReach` true, not in `ReachExpectations`), (d) its key was witnessed during the run (Task 2), (e) its recording verifies alone and after the catalogue and plays clean in `scenarioview`. Spawning or holding a thing is never enough; each category has a required observable, captured as a before/after delta:

| Category (key form) | Drive | Required observable |
| --- | --- | --- |
| Hostile mob (`actors.mobs.X`, nested `X$Y`) | spawned hunting beside the hero (melee) or at range 3-4 in a clear line (ranged); hero `sturdy()`; MOVE-attacks or WAITs, at most 12 steps | mob HP below HT or dead, **and** the mob's signature effect when the task table names one (it replaces the generic condition); otherwise hero HP dropped or a NEGATIVE hero buff appeared |
| Passive mob / NPC (`Pylon`, `RotHeart`, mimics, `Sheep`, NPCs, vault devices) | per-task table: MOVE into it (INTERACT), answer MENU, or trigger its condition | its state change (revealed, talked, quest given, shop opened, door opened, alarm) |
| Ally / summon (`$LightAlly`, `$GhostHero`, `$Ward$WardSentry`, ...) | created by its source item or ability through the policy's actions | the ally damages a 100-HP rat within 8 steps |
| Buff (`actors.buffs.X`) | applied by its natural source in the owning task, or by `kit.buff` in setup (Task 25) | the quantity the buff changes (HP, `Actor.now()` delta of a move, damage taken, a mask, position) differs from the pre-trigger capture; a `FlavourBuff` also expires |
| Blob (`actors.blobs.X`) | produced by its source (trap, potion, room, boss) | volume > 0 **and** a char's HP, buffs or position changed within 5 steps |
| Trap (`levels.traps.X`) | placed visible beside the hero; hero MOVEs onto it | trap not active or depth changed, **and** the per-trap observable |
| Plant and seed (`plants.X`, `plants.X$Seed`) | seed THROWn from the pack at a cell beside the hero, hero MOVEs onto the plant | plant gone **and** the per-plant observable |
| Melee weapon | USE equips it (WARRIOR); MOVE-attacks a 100-HP rat | `belongings.weapon` is it; rat HP drops within 6 attacks |
| Duelist weapon ability (same key, second scenario) | DUELIST, weapon worn, USE (default `AC_ABILITY`) then aim | `MeleeWeapon.Charger` charges fell; rat HP, position or buffs changed |
| Missile / dart | USE or THROW, aim nearest at a rat at range 3 | stack shrank; the missile hit: rat HP, buffs or position changed against the builder's pre-throw capture (tipped dart: its buff or effect); a miss that only leaves a heap fails |
| Armour | USE equips it | `belongings.armor` is it; `DRMax()` above the `ClothArmor` value at the same level |
| Glyph / armour curse | on worn armour at level 30 (curses: level 0, cursed); a 1000-HP rat with 10 damage attacks for up to 20 turns | the glyph's observable in the task table |
| Enchantment / weapon curse | on a worn weapon at level 30 (curses: level 0, cursed); hero attacks a 1000-HP rat up to 20 times | the proc's observable in the task table |
| Ring | USE equips it | the ring's static getter (e.g. `RingOfMight.strengthBonus(hero)`) differs from before equip |
| Artifact | USE equips (toggle), USE or `ITEM_MENU` activates | its effect |
| Potion, scroll, exotic, brew, elixir, alchemy spell | USE (drink/read/cast) or THROW; answer INVENTORY/MENU/TARGETING | its effect |
| Runestone, bomb | THROW at a cell (stones with pickers: USE then pick) | its effect (bombs: after the fuse) |
| Food | USE when hungry | hunger fell **and** the food's extra effect |
| Recipe (`...$Recipe`, `Potion$SeedToPotion`, ...) | pack emptied, ingredients given, pot beside the hero, ALCHEMY + MENU_SELECT of that recipe (`Driver.brew`) | product in the pack, `Statistics.itemsCrafted` +1 |
| Trinket | dropped beside the hero in setup, picked up by a MOVE, then carried (trinkets act from the pack) | its static modifier getter, captured before the pickup, differs after it; active trinkets also their in-world effect within 30 turns |
| Talent (`talent:NAME`) | class, level, subclass and ability set in setup; TALENT mode, MENU_SELECT of that talent's index | `pointsInTalent == 1`, then the talent's effect versus the capture before selection (combat talents: the proc or buff, never a damage comparison) |
| Subclass (`subclass:NAME`) | TengusMask USE, MENU index, confirm | `hero.subClass` changed during a step **and** its active (via `INDICATOR`) or passive observed |
| Armour ability (`actors.hero.abilities...`) | KingsCrown on class armour, MENU choice; USE on class armour (default `AC_ABILITY`), aim | `hero.armorAbility` changed during a step (choice scenario) or ability effect with the preset ability witnessed by `sample()` (use scenario) |
| Cleric spell (`actors.hero.spells.X`) | HolyTome USE, MENU index in `WndClericSpells`, aim if targeted | tome charge fell **and** the spell's effect |
| Room (`levels.rooms...`) | forced by the prepare hook at a depth, hero placed beside its door | the room's feature interacted with (task table) |
| Level (`levels.X`) | depth jump lands on it | boss levels: boss HP dropped in a bot-free scenario (`judgeReach` true); others: TRAVEL_STAIRS descends from it |
| Hero class (`heroclass:NAME`) | scenario played as that class | its signature kit item used |
| Extra (`items.wands.CursedWand`) | cursed wand zapped at a rat | charge spent and the world digest changed |

## Denominator

Re-derived 2026-11-15 by a class-path scan of `core/build/libs/core-4.0.2.jar` (prototype run from the scratchpad, rules identical to Task 1's `Coverage.scanned()`):

- **Scanned (808):** every concrete, non-anonymous, non-local class in packages `actors.mobs.**`, `actors.buffs`, `actors.blobs`, `levels.traps`, `plants`, `items.**`, `actors.hero.abilities.**`, `actors.hero.spells`, `levels.rooms.special`, `levels.rooms.secret`, `levels.rooms.quest.**`, assignable to one of `Mob`, `Buff`, `Blob`, `Trap`, `Plant`, `Item`, `Weapon.Enchantment`, `Armor.Glyph`, `ArmorAbility`, `Recipe`, `Room`, `ClericSpell`; member (nested) classes only when assignable to `Mob`, `Plant`, `Plant.Seed` or `Recipe`; plus top-level `Level` subclasses in package `levels`.
- **Enums (178):** 160 `Talent`, 12 `HeroSubClass` (without `NONE`), 6 `HeroClass`.
- **Extra (1):** `items.wands.CursedWand` (a mechanic with no content class).
- **Excluded (18)**, each with its reason in `coverage/excluded.txt`:

| Key | Reason |
| --- | --- |
| `actors.blobs.Blob` | concrete base, never seeded as itself |
| `actors.buffs.Buff` | concrete base, never attached as itself |
| `actors.buffs.FlavourBuff` | concrete base for timed buffs |
| `actors.buffs.CounterBuff` | concrete base for counting buffs |
| `actors.buffs.Sleep` | immunity marker, only named in `Char.isImmune` sets, never attached |
| `items.Item` | concrete base |
| `items.armor.Armor` | concrete base of worn armour |
| `items.artifacts.Artifact` | concrete base of artifacts |
| `items.bags.Bag` | concrete base of bags |
| `items.weapon.melee.MeleeWeapon` | concrete base of melee weapons |
| `items.rings.Ring` | concrete base of rings |
| `items.potions.Potion` | concrete base of potions |
| `items.potions.exotic.ExoticPotion` | concrete base of exotic potions |
| `actors.mobs.npcs.DirectableAlly` | concrete base of directable allies |
| `actors.mobs.DelayedRockFall` | concrete base, only its subclasses `GnollGeomancer$GnollRockFall` and `DM300$FallingRockBuff` are attached |
| `plants.Plant$Seed` | concrete base of seeds |
| `plants.Plant$Seed$PlaceHolder` | inventory filter placeholder, never an item |
| `levels.DeadEndLevel` | generated only for a depth or branch the game never sends a hero to |

**Denominator = 808 + 178 + 1 - 18 = 969.** Task 1 prints the gate's own count; if it differs, the gate's number is authoritative and the difference is recorded in `CHANGELOG.md`. A new exclusion must be abstract-like, a base, or internal, carries a one-line reason, and is named in the task's CHANGELOG entry.

## Runtime budget

Measured: `scenariocheck` ran 171 scenarios in 4,152 ms (`out/evidence/commit-p6.log`), 24 ms a scenario including verify and the re-verify after the catalogue; boss and depth-jump scenarios 40-70 ms (`out/scenarios/report.csv`). `scenarioview` played 171 recordings in 57.5 s at 20 children (`out/evidence/issue50/scenarioview.log`).

The plan adds 710 scenarios (per-task counts below), to about 881. Expected:

- `scenariocheck`: ~881 x 24 ms = 21 s, plus ~140 depth-jump scenarios at ~40 ms extra = 6 s: **about 27-28 s wall** inside the gate (from 4.2 s). Watchdog default raised from 60 s to 120 s (Task 2).
- `scenarioplayback` (in `verifyall`): no timing logged; estimated at the same per-recording cost, about 25 s.
- Pre-commit hook (`verifyall`): grows by about 45-50 s.
- `scenarioview` full run: about 5 min at 20 children; run once, in Task 35, under `timeout 900`. Per task only the task's prefixes (Task 2 adds prefix and list filters).

**Decision: run sequentially, every scenario in `gates`.** Sharding across `IsolatedLoader`s would save ~20 s but split the "verified after the whole catalogue" and `leak.*` cross-checks, which exist to catch cross-episode static state; trading a fidelity check for 20 s contradicts the replay-first rule. Trigger: if `scenariocheck`'s printed total passes 45 s, stop and report with `report.csv` rather than sharding on your own.

## Fidelity and reach handling

- A scenario that passes headless and diverges in `scenarioview`: reproduce with `-PscenarioOnly=<id>`, compare with `:superintelligence:viewdiff` and `rngtrace` (testing-guide rows), fix in `superintelligence/` (headless stage, `HeadlessSprite`, `LevelPipeline`, `WindowBridge`) or `desktop/.../replay/` (`ReplayPlayer`, `ViewCheck`). Never change the scenario to avoid the divergence. If the root cause is engine logic in `core`, stop: write the patch to `out/evidence/mechanic-coverage/<id>.patch`, keep it uncommitted, and report.
- A step `PolicyReach` flags: close it in the action space in the same task, add a CHANGELOG line naming the closed gap, and keep the scenario as written. Known gaps to close are listed in the tasks that first need them: Task 6 (`WndMetamorphChoose`, `WndMetamorphReplace`, `TalentButton` in `buttons()`), Task 7 (`WndAugment`, `WndGuess`, `IconButton` in `buttons()`), Task 15 (`WndClericSpells`), Task 19 (`ITEM_MENU`, `INDICATOR` actions, `WndResurrect`), Task 20 (`WndGhostHero`, toolkit alchemy), Task 21 (`WndTrinket`, `TrinketCatalyst.Recipe` skip in `AlchemyAction.recipes`), Task 22 (`WndAlchemizeItem`), Task 31 (`WndCombo`, `WndMonkAbilities`), Task 33 (`WndUseTrinity`, `Trinity` for `Trinity$WndItemtypeSelect`, `WndItemConfirm`). Window closures are additions to `WindowBridge.CHOICE_WINDOWS` (simple class names; `choice()` already walks enclosing classes), verified by the scenario's reach going `POLICY`. `WindowBridge.buttons()` collects only `RedButton` and `ItemButton`; a window whose choices are another button type also needs `buttons()` extended to that type (Tasks 6, 7, 15).

## Standard task cycle

Every content task (3-34) runs these steps; the task text gives only what differs (files, keys, scenarios, gaps, mutation, message).

- [ ] **A. Write the scenarios** in the task's family file(s), each with `.covers(...)` for exactly the keys it exercises, ids under the task's prefixes. Register new family classes in `ScenarioCatalog`.
- [ ] **B. Remove the task's keys from `superintelligence/src/main/resources/coverage/pending.txt`.**
- [ ] **C. Coverage gate.** `timeout 300 ./gradlew :superintelligence:coveragecheck` (~20 s). Expected: `[OK]     coverage: <n> of 969 covered, <p> pending, 18 excluded` with `<n>` grown by the task's key count.
- [ ] **D. Scenarios headless.** `timeout 300 ./gradlew :superintelligence:scenariocheck --args="--only <prefixes>"` (~30 s). Expected: every listed scenario `[OK] ... OK` with no reach column (reach `POLICY`), `[OK] scenarios: <k> recorded, verified twice and written, 0 expected reach gaps, 0 known failures`.
- [ ] **E. Scenarios in the viewer.** `timeout 600 ./gradlew :desktop:scenarioview -PscenarioOnly="<prefixes>"` (~2 min: it runs the full `scenariocheck` and `scenarioplayback` first). Expected: `[OK]     rendered playback: <k> recordings played clean in the real viewer`.
- [ ] **F. Mutation.** Apply the task's mutation (an uncommitted edit), re-run step D for the named scenario, see the named failure, then revert with `git checkout -- <file>` and re-run D once to see it green. Paste the red line into the CHANGELOG entry.
- [ ] **G. Tests.** `timeout 600 ./gradlew test`.
- [ ] **H. Docs.** `superintelligence/CHANGELOG.md` `## [Unreleased]` / `### Added`: one entry naming the family, the keys covered, gaps closed, the mutation and its red line. `superintelligence/TODO.md`: update the "Full mechanic coverage" line to `covered <n> of 969`. `superintelligence/testing-guide.md`: only where the task changes framework behaviour (named in the task).
- [ ] **I. Commit.** `git add` the exact files, then `git commit -m "<message>"`; the hook runs `verifyall` (~2-3 min). Copy the step D and E output to `superintelligence/out/evidence/mechanic-coverage/task-<N>.log`.

---

### Task 1: Coverage declarations and the `coveragecheck` gate

**Files:**
- Create: `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/scenario/Coverage.java`
- Create: `superintelligence/src/main/java/com/shatteredpixel/shatteredpixeldungeon/superintelligence/diag/CoverageCheck.java`
- Create: `superintelligence/src/main/resources/coverage/excluded.txt`, `superintelligence/src/main/resources/coverage/pending.txt`
- Modify: `scenario/Scenario.java` (covers API), every existing `scenario/*Scenarios.java` (backfill), `Main.java` (case + usage line), `superintelligence/build.gradle` (`cliTask('coveragecheck', ...)` and add to `gates`), `superintelligence/README.md` ("**Forty-five gates, one invocation.**"), `docs/documentation.md` (gate table row), `superintelligence/testing-guide.md` (new row), `superintelligence/TODO.md` (worklist line), `superintelligence/CHANGELOG.md`.

**Interfaces:**
- Produces:
```java
public final class Scenario {
	public final java.util.Set< String > covers = new java.util.LinkedHashSet<>();
	public Scenario covers( Class< ? >... types );
	public Scenario covers( String... keys );
}
public final class Coverage {
	public static final String PREFIX = "com.shatteredpixel.shatteredpixeldungeon.";
	public static String key( Class< ? > type );
	public static String talent( Talent talent );
	public static String subclass( HeroSubClass subclass );
	public static String heroClass( HeroClass heroClass );
	public static java.util.SortedSet< String > scanned();
	public static java.util.SortedSet< String > enums();
	public static java.util.SortedSet< String > extra();
	public static java.util.SortedMap< String, String > excluded();
	public static java.util.SortedSet< String > pending();
	public static java.util.SortedSet< String > denominator();
	public static java.util.SortedSet< String > declared( java.util.List< Scenario > scenarios );
	static java.util.SortedSet< String > missing( java.util.Set< String > denominator, java.util.Set< String > declared, java.util.Set< String > pending );
	static java.util.SortedSet< String > stillPending( java.util.Set< String > declared, java.util.Set< String > pending );
}
```
- Key forms: `key(type)` = `type.getName()` without `PREFIX` (nested keep `$`: `actors.mobs.Shaman$RedShaman`); `talent:HEARTY_MEAL`, `subclass:BERSERKER`, `heroclass:WARRIOR`.
- Resource formats: `excluded.txt` one `key<TAB>reason` per line; `pending.txt` one key per line, sorted.

- [ ] **Step 1: Write `CoverageCheck`'s self-tests first** (in `CoverageCheck.main`, run before the real check, failing through `GateReport.fail`):
  - `scanned()` contains `actors.mobs.Rat`, `actors.mobs.Shaman$RedShaman`, `items.potions.brews.BlizzardBrew$Recipe`, `plants.Icecap$Seed`, `levels.SewerBossLevel`, `actors.hero.spells.Smite`; does not contain `actors.mobs.Mob` (abstract), `items.rings.RingOfMight$Might` (nested buff), `levels.rooms.standard.EmptyRoom` (package not scanned), any name containing `$1`.
  - `enums()` has 160 `talent:` keys, 12 `subclass:` keys (no `subclass:NONE`), 6 `heroclass:` keys.
  - `missing(denominator, declared, pending)` on a synthetic set `{actors.mobs.Rat, actors.mobs.Bat}` with declared `{actors.mobs.Bat}` and empty pending returns exactly `[actors.mobs.Rat]`.
  - `stillPending` on declared `{actors.mobs.Bat}` and pending `{actors.mobs.Bat}` returns `[actors.mobs.Bat]`, which the gate reports as `covered and still pending, remove it from pending.txt: actors.mobs.Bat`.
- [ ] **Step 2: Run it to see it fail:** `timeout 300 ./gradlew :superintelligence:coveragecheck`. Expected: build failure (`Coverage` not defined) or `[ERROR]` lines naming each self-test.
- [ ] **Step 3: Implement `Coverage`.** Scan the jar at `Dungeon.class.getProtectionDomain().getCodeSource().getLocation()` (a directory is also accepted: walk it). Load each entry with `Class.forName( name, false, Coverage.class.getClassLoader() )` (no static initialisation), skip on `Throwable`. Apply the Denominator rules above exactly. `excluded()`/`pending()` read the resources with `Coverage.class.getResourceAsStream`. `denominator()` = `scanned() + enums() + extra()` minus `excluded().keySet()`.
- [ ] **Step 4: Implement `CoverageCheck.main( String[] args )`.** Real checks over `ScenarioCatalog.all()`, each failing with every offending key named, one per line:
  1. a denominator key neither declared nor pending: `uncovered: <key>`;
  2. a pending key that is declared: `covered and still pending, remove it from pending.txt: <key>`;
  3. a declared, pending or excluded key not in `scanned() + enums() + extra()`: `unknown key: <key>`;
  4. an excluded key that is declared or pending: `excluded and declared: <key>`;
  5. `covers` on a scenario with `judgeReach == false` or listed in `ReachExpectations`: `<id> declares covers but is not judged for reach`.
  `--print-missing` prints `denominator - declared` one per line and exits 0. Success line: `[OK]     coverage: <declared and denominator> of <denominator> covered, <pending> pending, <excluded> excluded`. Wire `case "coveragecheck"` in `Main.java`, a usage line, `cliTask('coveragecheck', 'Fails if a game class has no scenario that covers it, naming every one.')`, and add `'coveragecheck'` to the `gates` list.
- [ ] **Step 5: Backfill the existing 171 scenarios.** Add `.covers(...)` where the scenario's setup creates the thing and its script asserts on it. Exact backfill (141 class keys and 4 hero classes):
  - `RangedScenarios`: `bow` SpiritBow + `heroclass:HUNTRESS`; `staff` MagesStaff + `heroclass:MAGE`; `thrown.X` X, and its predicate drops `|| landed` (`RangedScenarios.java:122`): a scenario that declares covers must hit, a miss that leaves a heap fails; `wand.X` its wand; `potion-throw` PotionOfLiquidFlame, Fire; `knockback` WandOfBlastWave.
  - `TrapScenarios`: `trap.X` its trap (11); `trap.Gripping` also Cripple.
  - `MobScenarios`: `zap.X`/`mob.X`/`eye-beam` its mob (Shaman variants as nested keys); `goo-pump` Goo; `necro-summon` Necromancer, Skeleton; `mob.Succubus` Succubus, Charm; `mob.Thief` Thief; `mob.Swarm` Swarm; `ally.MirrorImage` ScrollOfMirrorImage, MirrorImage.
  - `CreatureScenarios`: `mob.Brute|Ripper|Golem|DM200|Statue|Ghoul` their mobs (Ripper = RipperDemon); `ally.LivingEarth` WandOfLivingEarth$EarthGuardian; `ally.Corruption` Corruption; `ally.Ward` WandOfWarding$Ward.
  - `StateScenarios`: `drink.X` PotionOfX and its buff (Haste, Invisibility, Levitation, MindVision; Healing buff for `drink.Healing`); `drink.unknown-ParalyticGas` PotionOfParalyticGas, ParalyticGas; `scroll.X` ScrollOfX (Terror + Terror buff, Lullaby + Drowsy, Recharging + Recharging buff, Rage + Amok); `eat` Food, Hunger; `equip` LeatherArmor; `equip-cursed` Sword; `levelup` Rat; `death` nothing.
  - `EffectScenarios`: `buff.X` its buff; `blob.X` its blob (Web also Roots).
  - `ConditionScenarios`: `buff.Levitation|Invisibility|Slow|Charm` their buffs; `ankh` Ankh.
  - `CraftScenarios`: `picker.Upgrade` ScrollOfUpgrade, ClothArmor; `picker.Identify` ScrollOfIdentify, RingOfAccuracy; `picker.RemoveCurse` ScrollOfRemoveCurse, Dagger; `picker.Transmutation` ScrollOfTransmutation; `picker.Enchantment` ScrollOfEnchantment; `brew.seeds` Potion$SeedToPotion; `brew.exotic-potion` ExoticPotion$PotionToExotic; `brew.exotic-scroll` ExoticScroll$ScrollToExotic; `brew.meat-pie` MeatPie$Recipe, MeatPie, Pasty, MysteryMeat.
  - `FeatureScenarios`: `well.Health` WaterOfHealth; `well.Awareness` WaterOfAwareness, Awareness; `plant.Sungrass|Firebloom|Sorrowmoss` the plant (not the seed).
  - `WorldScenarios`: `locked-door` IronKey; `chest.LOCKED_CHEST` GoldenKey; `chest.CRYSTAL_CHEST` CrystalKey; `chest.Mimic` Mimic.
  - `GapScenarios`: `artifact.Cloak` CloakOfShadows + `heroclass:ROGUE`; `artifact.EtherealChains` EtherealChains; `shop-buy` Shopkeeper; `melee`-family (`CombatScenarios.melee`) `heroclass:WARRIOR`.
  - `NpcScenarios`: `npc.Ghost|Wandmaker|Blacksmith|Imp` the NPC; `reward.subclass` TengusMask.
  - `CombatScenarios`: `guard-chain` Guard.
  - Not backfilled: `teacher.*` (bot-driven, `judgeReach` false), so `HeavyBoomerang`, `Wayward` and `Piranha` stay pending; `boss.*` in `DepthScenarios` (bot-driven, `judgeReach` false, check 5 would fail them), so `SewerBossLevel`, `Tengu`, `PrisonBossLevel`, `DM300`, `CavesBossLevel`, `DwarfKing` and `CityBossLevel` stay pending for the bot-free boss scenarios of Tasks 3, 26, 27 and 28.
- [ ] **Step 6: Generate `pending.txt`:** `timeout 300 ./gradlew :superintelligence:coveragecheck --args="--print-missing" > superintelligence/out/evidence/mechanic-coverage/missing-task1.txt`, then copy the key lines (sorted) into `pending.txt`; write the 18 rows of `excluded.txt` from the Denominator table (`actors.mobs.DelayedRockFall` included: a concrete base whose subclasses `GnollGeomancer$GnollRockFall` and `DM300$FallingRockBuff` are the only ones attached).
- [ ] **Step 7: Run the gate:** `timeout 300 ./gradlew :superintelligence:coveragecheck`. Expected: four self-test lines, then `[OK]     coverage: 145 of 969 covered, 824 pending, 18 excluded` (141 classes + 4 hero classes; exact numbers from the gate win, record them).
- [ ] **Step 8: Mutation.** Remove `.covers( AlarmTrap.class )` from `trap.Alarm`: expect `uncovered: levels.traps.AlarmTrap`. Restore; then delete `items.potions.PotionOfFrost` from `pending.txt` without a scenario: expect `uncovered: items.potions.PotionOfFrost`. Restore.
- [ ] **Step 9:** `timeout 600 ./gradlew test`; docs: README count to Forty-five; `docs/documentation.md` gate table row `coveragecheck | a game class with no covering scenario`; testing-guide row for `coveragecheck` (what it runs, the five checks, the self-tests, the mutation above); TODO.md worklist line `- [ ] **Full mechanic coverage** (plan superintelligence/plans/2026-11-15-mechanic-coverage.md): covered 145 of 969.`; CHANGELOG entry (names the `DelayedRockFall` exclusion and its reason).
- [ ] **Step 10: Commit:** `feat(gates): coveragecheck names every game class no scenario covers`.

### Task 2: Witness, list filters, seed search, budget

**Files:**
- Create: `scenario/CoverageWitness.java`
- Modify: `scenario/Driver.java` (sample after each step, `brew`, `brewRecipe`, `talent`, `witness`), `scenario/ScenarioRunner.java` (fail on unwitnessed), `scenario/ScenarioCatalog.java` (`selected`), `diag/ScenarioCheck.java` (list filter, `--seeds`, budget 120, witness self-test), `scenario/CraftScenarios.java` (use `Driver.brew`), `desktop/.../replay/ViewCheck.java` (list filter at line 180; `desktop/.../replay` is trainer-owned viewer code and committable, not engine), `testing-guide.md` (scenariocheck and scenarioview rows), CHANGELOG, TODO.

**Interfaces:**
- Consumes: `Coverage.key`, `Scenario.covers` (Task 1).
- Produces:
```java
final class CoverageWitness {
	void sample();
	void chosen( String key );
	void witness( String key );
	java.util.List< String > unwitnessed( java.util.Set< String > declared );
}
public void brew( Class< ? extends Item > product );
public void brewRecipe( Class< ? extends Recipe > recipe );
public void talent( Talent talent );
public void witness( Class< ? > type );
```
In `ScenarioCatalog`:
```java
public static boolean selected( String filter, String id );
```
- `sample()` records `Coverage.key` of: every mob in `Dungeon.level.mobs`; every buff on the hero and on every mob; every blob with `volume > 0`; every item in the hero's belongings (bags' contents and equipped slots included) and in `Dungeon.level.heaps`, plus the enchantment of the worn weapon and the glyph of the worn armour; every trap in `level.traps`; every plant in `level.plants`; every room in `((RegularLevel) level).rooms()` when the level is a `RegularLevel`; `Dungeon.level.getClass()`; `heroclass:` of the hero; `hero.armorAbility.getClass()` when `hero.armorAbility` is non-null (so an ability preset in setup is witnessed). Reading only: no RNG draw, no `name()`/`desc()` calls.
- `Driver.step` calls `sample()` before the first step and after every step, and calls `chosen` for: the recipe at the MENU_SELECT index in ALCHEMY mode (read before stepping); a talent whose points rose during the step; `subclass:` when `hero.subClass` changed during the step; `Coverage.key(hero.armorAbility.getClass())` when it changed during the step.
- `witness( key )` accepts only keys of `ClericSpell` subclasses and `Coverage.extra()`; any other key throws `Driver.Stop( "witness() is only for keys with no observable instance: " + key )`.
- `Driver.brew` is `CraftScenarios.brew`'s recipe-choice body moved verbatim (ALCHEMY, find index whose `yields`, `menu`); it takes the first recipe yielding the product. `Driver.brewRecipe` is the same choice matched on the recipe's class (`recipe.getClass() == type`) for products several recipes share (`StewedMeat$oneMeat|twoMeat|threeMeat`); it cannot be a `brew` overload, since both erase to `brew( Class )`. `Driver.talent` is TALENT then `menu( env.mapper().talentChoices().indexOf( talent ))`, `Stop` if absent.
- `ScenarioCatalog.selected( filter, id )`: filter is comma-separated tokens; a token ending `*` matches ids starting with the rest; otherwise exact. Called by `ScenarioCheck --only` and by `ViewCheck` (which depends on `:superintelligence` already) on the recording name without `.replay`.
- `ScenarioCheck --seeds N` replaces every chosen scenario's seeds with `SCN-0`..`SCN-(N-1)` (seed search for pinning); default budget `--budget-seconds` 120.

- [ ] **Step 1: Self-test first** in `ScenarioCheck`: a scenario `selftest.witness` (WARRIOR, spawns a Rat, WAITs once) declaring `covers( Bat.class )` must end `FAIL` with failure containing `declared covers never witnessed: actors.mobs.Bat`; the same scenario declaring `covers( Rat.class )` must end `OK`. Both run before the catalogue and are not written to `out/scenarios`.
- [ ] **Step 2: Run to see it fail:** `timeout 300 ./gradlew :superintelligence:scenariocheck --args="--only melee"`. Expected: compile error or the self-test `[ERROR]`.
- [ ] **Step 3: Implement** `CoverageWitness`, the `Driver` hooks, `ScenarioRunner` (after the script, when status is `OK`, `unwitnessed` non-empty fails with `declared covers never witnessed: <keys>`), the filters and `--seeds`.
- [ ] **Step 4: Run:** `timeout 300 ./gradlew :superintelligence:scenariocheck`. Expected: self-tests `[OK]`, all 171 `OK`. Any backfilled scenario failing the witness had a wrong declaration in Task 1: fix the declaration (remove the key, put it back in pending), never the witness.
- [ ] **Step 5:** `timeout 600 ./gradlew :desktop:scenarioview -PscenarioOnly="trap.*,wand.*"`. Expected: 24 recordings clean. Then `timeout 600 ./gradlew :desktop:viewcheck` once (the change touches `desktop/.../replay/ViewCheck.java`). Expected: the committed corpus plays clean.
- [ ] **Step 6: Mutation.** Delete both `sample()` calls in `Driver`: expect every scenario whose covers are witnessed only through `sample()` red with `declared covers never witnessed: ...` (e.g. `trap.Alarm` naming `levels.traps.AlarmTrap`); scenarios whose covers are all recipes (`brew.seeds`, `brew.exotic-potion`, `brew.exotic-scroll`) stay green, since `chosen` witnesses recipes. Revert.
- [ ] **Step 7:** tests, docs (testing-guide: scenariocheck row gains the witness, `--only` lists and prefixes, `--seeds N` and the re-pin rule from Review Focus 4; scenarioview row gains the list filter), CHANGELOG.
- [ ] **Step 8: Commit:** `feat(scenario): witness every declared cover and filter runs by prefix`.

### Task 3: Sewers (floors 1-5) and Goo

**Files:** Create `scenario/FoeFamily.java` (the shared builder), `scenario/SewerScenarios.java` (all `sewer.*` scenarios, `sewer.goo-kill` included). `DepthScenarios.java` is not modified: its `boss.*` scenarios are bot-driven and cannot carry covers.

**Interfaces:**
- Produces:
```java
final class FoeFamily {
	static Scenario fight( String id, Class< ? extends Mob > type, int range, int steps, java.util.function.BooleanSupplier signature, String failure );
	static Scenario passive( String id, Class< ? extends Mob > type, Scenario.Script interact, java.util.function.BooleanSupplier changed, String failure );
}
```
`fight` builds the hostile-mob row of the Definition table (MOVE-attack when range 1, WAIT otherwise; captures hero HP, hero buff set and mob HP before). The pass condition is mob HP below HT or dead, **and**: when `signature` is non-null, the signature (it replaces the generic disjunction, so a mutation that removes only the signature goes red); when `signature` is null, hero HP dropped or a NEGATIVE hero buff appeared. Later region tasks reuse it.

**Keys (24):** `actors.mobs.Albino Gnoll Snake Crab Slime CausticSlime FetidRat GreatCrab HermitCrab Piranha PhantomPiranha GoldenMimic CrystalMimic EbonyMimic`, `actors.buffs.Ooze Bleeding LockedFloor Regeneration`, `actors.blobs.GooWarn StenchGas`, `items.keys.WornKey`, `items.quest.GooBlob`, `levels.SewerLevel SewerBossLevel`.

**Scenarios (prefix `sewer.`, 18):**
| id | covers | signature / interaction |
| --- | --- | --- |
| `sewer.Albino` | Albino, Bleeding | hero gains Bleeding |
| `sewer.Gnoll`, `sewer.Snake`, `sewer.Crab`, `sewer.Slime`, `sewer.HermitCrab` | the mob | generic fight |
| `sewer.CausticSlime` | CausticSlime, Ooze | hero gains Ooze |
| `sewer.FetidRat` | FetidRat, StenchGas | StenchGas volume > 0 beside the hero |
| `sewer.GreatCrab` | GreatCrab | attacked from the front, its HP unchanged while it blocks; from behind HP drops |
| `sewer.Piranha` | Piranha | hero standing in water beside a pool: hero HP drops; piranha HP drops when hit |
| `sewer.PhantomPiranha` | PhantomPiranha | in water: hero hit, piranha teleports when hit |
| `sewer.GoldenMimic`, `sewer.CrystalMimic`, `sewer.EbonyMimic` | the mimic | passive: MOVE into the disguised mimic reveals it (`alignment` ENEMY, `state` HUNTING) and it attacks |
| `sewer.goo-kill` | GooWarn, GooBlob, WornKey, LockedFloor, SewerBossLevel | bot-free (`judgeReach` true, scripted `Driver` steps only), depth 5 on SewerBossLevel, Goo at 5 HP after first pump warning: GooWarn volume > 0 during the pump; Goo HP drops; hero kills Goo; GooBlob and WornKey drop; LockedFloor present before the kill and gone after |
| `sewer.worn-key` | WornKey | the WornKey dropped as a heap beside the hero in setup (so `sample()` witnesses it), picked up by a MOVE, then MOVE into the locked exit door opens it |
| `sewer.descend` | SewerLevel | depth 2: TRAVEL_STAIRS descends to depth 3 |
| `sewer.regen` | Regeneration | hero at half HP WAITs 30: HP rose |

- [ ] Standard cycle A-I with `--only "sewer.*"`.
- **Mutation:** in `core/.../actors/mobs/Albino.java` `attackProc`, remove the `Buff.affect( enemy, Bleeding.class )` line: expect `sewer.Albino` red with its failure text. Revert.
- **Commit:** `test(scenario): cover sewer foes, Goo's drops and the sewer floor`.

### Task 4: Every remaining trap

**Files:** Modify `scenario/TrapScenarios.java` (extend the list; add a `Map< Class, BooleanSupplier >` of observables; split into `TrapEffects.java` if it passes 500 lines).

**Keys (28):** `levels.traps.BlazingTrap ChillingTrap ConfusionTrap CorrosionTrap CursingTrap DisarmingTrap DistortionTrap ExplosiveTrap FlashingTrap FlockTrap FrostTrap GatewayTrap GeyserTrap GnollRockfallTrap GrimTrap GuardianTrap GuardianTrap$Guardian OozeTrap ShockingTrap StormTrap TenguDartTrap WarpingTrap WeakeningTrap`, `actors.buffs.Chill Frost Corrosion Weakness`, `actors.blobs.CorrosiveGas`.

**Observables (scenario `trap.<Name>`, 22):** Blazing: Fire volume > 0 around; Chilling: hero Chill; Confusion: ConfusionGas volume > 0; Corrosion: CorrosiveGas volume > 0 and hero Corrosion; Cursing: worn weapon (given) becomes cursed; Disarming: worn weapon leaves the hand (on the floor or teleported); Distortion: a mob appears on the level; Explosive: hero HP drops; Flashing: hero Blindness and Cripple; Flock: a Sheep appears; Frost: hero Frost; Gateway: hero position changes and the trap stays active; Geyser: water terrain spreads and an adjacent rat is knocked back; GnollRockfall: hero HP drops; Grim: a rat in range dies or hero HP drops; Guardian: a `GuardianTrap$Guardian` appears and hunts; Ooze: hero Ooze; Shocking: Electricity volume > 0; Storm: Electricity on several cells; TenguDart: hero Poison; Warping: hero position changes and map knowledge is cleared; Weakening: hero Weakness.

- [ ] Standard cycle with `--only "trap.*"`.
- **Mutation:** empty `ChillingTrap.activate()`'s body: expect `trap.Chilling` red. Revert.
- **Commit:** `test(scenario): cover every trap`.

### Task 5: Every plant through its seed

**Files:** Create `scenario/PlantScenarios.java`. Modify `scenario/HeroKit.java` (`subclass` preset).

**Interfaces:** Produces in `HeroKit`: `HeroKit subclass( HeroSubClass s )` (sets `hero.subClass` and calls `Talent.initSubclassTalents`). Later tasks (15, 31, 32) consume it.

**Keys (24):** `plants.BlandfruitBush Blindweed Earthroot Fadeleaf Icecap Mageroyal Rotberry Starflower Stormvine Swiftthistle` and the `$Seed` of each of those ten plus `Sungrass$Seed Firebloom$Seed Sorrowmoss$Seed` (the existing `plant.*` scenarios plant in setup, so they cover the plant, not the seed), and `actors.buffs.AdrenalineSurge`.

**Scenarios (prefix `seed.`, 13):** THROW the seed at a free cell beside the hero (`aimNearest` is wrong here: aim the target index of that cell via `Driver.direction`), then MOVE onto it. `seed.Rotberry` and `seed.Starflower` play as HUNTRESS with `kit.subclass( HeroSubClass.WARDEN )`, since their hero buffs are Warden-only (`Rotberry.activate`, `Starflower.activate`); the rest as WARRIOR. Observables: BlandfruitBush: a Blandfruit heap appears; Blindweed: hero Blindness; Earthroot: `Earthroot$Armor` on hero; Fadeleaf: hero position changes; Icecap: Freezing volume > 0; Mageroyal: hero NEGATIVE buffs removed (Poison given in setup); Rotberry (Warden): hero AdrenalineSurge; Starflower (Warden): hero Bless and Recharging (read `Starflower.activate`); Stormvine: hero Vertigo; Swiftthistle: `Swiftthistle$TimeBubble` on hero; Sungrass: Healing; Firebloom: Fire; Sorrowmoss: Poison.

- [ ] Standard cycle with `--only "seed.*"`.
- **Mutation:** empty `Blindweed.activate()`: expect `seed.Blindweed` red. Revert.
- **Commit:** `test(scenario): cover every plant planted from its seed`.

### Task 6: Potions and scrolls, exotic included

**Files:** Create `scenario/PotionScenarios.java`, `scenario/ScrollScenarios.java`. Modify `env/WindowBridge.java`: `WndMetamorphChoose` and `WndMetamorphReplace` in `CHOICE_WINDOWS`, and `buttons()` (`WindowBridge.java:40-50`, today `RedButton` and `ItemButton` only) extended to collect visible, active `ui.TalentButton`s, which are both windows' choices. `WndDivination` is info-only (`ScrollOfDivination.java:125`): it is not a choice window and the scenario dismisses it with CANCEL (`d.cancel()`).

**Keys (39):** `items.potions.PotionOfFrost PotionOfPurity`; `items.potions.exotic.PotionOfCleansing PotionOfCorrosiveGas PotionOfDivineInspiration PotionOfDragonsBreath PotionOfEarthenArmor PotionOfMagicalSight PotionOfMastery PotionOfShielding PotionOfShroudingFog PotionOfSnapFreeze PotionOfStamina PotionOfStormClouds`; `items.scrolls.ScrollOfRetribution`; `items.scrolls.exotic.ScrollOfAntiMagic ScrollOfChallenge ScrollOfDivination ScrollOfDread ScrollOfForesight ScrollOfMetamorphosis ScrollOfMysticalEnergy ScrollOfPassage ScrollOfPrismaticImage ScrollOfPsionicBlast ScrollOfSirensSong`; `actors.mobs.npcs.PrismaticImage`; `actors.buffs.BlobImmunity MagicalSight Stamina Barrier Barkskin MagicImmune Dread Foresight ArtifactRecharge PrismaticGuard`; `actors.blobs.SmokeScreen StormCloud`.

**Scenarios (prefixes `potion.`, `scroll.`, 26):** one per item; THROW for Frost, CorrosiveGas, ShroudingFog, SnapFreeze, StormClouds, DragonsBreath (aimed); USE otherwise. Observables: Frost: Freezing volume / rat Frost; Purity: hero BlobImmunity and gas removed (ToxicGas seeded); Cleansing: hero NEGATIVE buffs removed; CorrosiveGas: CorrosiveGas volume; DivineInspiration: an extra talent point (`talentPointsAvailable` rises); DragonsBreath: rat Burning; EarthenArmor: Barkskin level > 0; MagicalSight: MagicalSight buff; Mastery: picked weapon's STR requirement fell; Shielding: Barrier shield > 0; ShroudingFog: SmokeScreen volume; SnapFreeze: rat Frost; Stamina: Stamina buff; StormClouds: StormCloud volume; Retribution: rat HP drops; AntiMagic: MagicImmune; Challenge: `ScrollOfChallenge$ChallengeArena` on hero and the rat aggroed; Divination: three unidentified items identified (`WndDivination` dismissed with CANCEL); Dread: rat Dread; Foresight: Foresight buff; Metamorphosis: the picked talent replaced (`WndMetamorphChoose` then `WndMetamorphReplace` answered through their TalentButtons, `hero.metamorphedTalents` grew); MysticalEnergy: ArtifactRecharge; Passage: depth or position changes per its code; PrismaticImage: a PrismaticImage appears and hits a rat (PrismaticGuard on hero once it is gone); PsionicBlast: rat HP drops and hero Weakness/Blindness; SirensSong: rat gains `ScrollOfSirensSong$Enthralled` and turns ally.

- [ ] Standard cycle with `--only "potion.*,scroll.*"`.
- **Mutation:** empty `PotionOfPurity.apply`'s body (drink path): expect `potion.Purity` red. Revert.
- **Commit:** `test(scenario): cover every potion and scroll, exotics included`.

### Task 7: Runestones

**Files:** Create `scenario/StoneScenarios.java`. Modify `env/WindowBridge.java`: `WndAugment` and `WndGuess` in `CHOICE_WINDOWS`, and `buttons()` (`WindowBridge.java:40-50`, `RedButton` and `ItemButton` only before Tasks 6-7) extended to collect visible, active `ui.IconButton`s, which are `WndGuess`'s class choices (`StoneOfIntuition.java:193`).

**Keys (15):** `items.stones.StoneOfAggression StoneOfAugmentation StoneOfBlast StoneOfBlink StoneOfClairvoyance StoneOfDeepSleep StoneOfDetectMagic StoneOfEnchantment StoneOfFear StoneOfFlock StoneOfIntuition StoneOfShock`, `items.scrolls.Scroll$ScrollToStone`, `actors.buffs.MagicalSleep`, `actors.mobs.npcs.Sheep`.

**Scenarios (prefix `stone.`, 13):** THROW at the rat's cell for Aggression (rat gains `StoneOfAggression$Aggression`), Blast (rat HP drops), Blink (hero lands on the target cell), Clairvoyance (cells mapped count rises), DeepSleep (rat MagicalSleep), Fear (rat Terror), Flock (Sheep appear), Shock (rat HP drops / Electricity). USE + picker for Augmentation (picked armour's augment changes via `WndAugment`), DetectMagic (picked item's cursed/uncursed known), Enchantment (picked weapon gains an enchantment), Intuition (`WndGuess` answered through its IconButton for the right class, then its guess RedButton, identifies it). `stone.brew` brews a stone from a scroll (`Driver.brew( Runestone.class )`) and covers `Scroll$ScrollToStone`.

- [ ] Standard cycle with `--only "stone.*"`.
- **Mutation:** in `StoneOfBlink.activate`, skip the teleport: expect `stone.Blink` red. Revert.
- **Commit:** `test(scenario): cover every runestone and stone brewing`.

### Task 8: Missiles and darts

**Files:** Create `scenario/MissileScenarios.java`. Modify `scenario/RangedScenarios.java`: make `thrown` package-visible as `static Scenario thrown( String id, Class< ? extends Item > type, java.util.function.BiPredicate< Mob, Capture > hit )`, with `record Capture( int hp, int pos, java.util.Set< Class< ? extends Buff > > buffs )` taken by the builder from the target rat immediately before the throw and passed to `hit` after it. The builder's default predicate compares against that capture and has no `|| landed` term (Task 1 removed it): a missile that misses and only leaves a heap fails. Update the existing `thrown.X` callers to the new signature.

**Keys (27):** `items.weapon.missiles.Bolas FishingSpear ForceCube HeavyBoomerang Javelin Kunai Shuriken ThrowingClub ThrowingHammer ThrowingSpear ThrowingSpike Tomahawk Trident`; `items.weapon.missiles.darts.AdrenalineDart BlindingDart ChillingDart CleansingDart DisplacingDart HealingDart HolyDart IncendiaryDart PoisonDart RotDart ShockingDart`; `actors.buffs.PinCushion Adrenaline Bless`.

**Scenarios (prefixes `missile.`, `dart.`, 24):** each thrown at a 100-HP rat at range 3. Signatures: Bolas rat Cripple; Tomahawk rat Bleeding; HeavyBoomerang returns to the pack within 4 steps; ForceCube hits every char around the cell; a missile that sticks leaves PinCushion on the rat; darts: Blinding rat Blindness; Chilling rat Chill; Cleansing a buffed rat loses buffs (versus `Capture.buffs`); Displacing rat position differs from `Capture.pos`; Healing hit ally gains HP (MirrorImage ally given); Holy hero/ally Bless; Incendiary Fire; Poison rat Poison; Rot rat Corrosion; Shocking rat HP; Adrenaline on an ally gives Adrenaline.

- [ ] Standard cycle with `--only "missile.*,dart.*"`.
- **Mutation:** in `Bolas.proc`, remove the Cripple: expect `missile.Bolas` red. Revert.
- **Commit:** `test(scenario): cover every missile and tipped dart`.

### Task 9: Bombs and the honeypot

**Files:** Create `scenario/BombScenarios.java`.

**Keys (15):** `items.bombs.ArcaneBomb Bomb Bomb$EnhanceBomb Firebomb FlashBangBomb FrostBomb HolyBomb Noisemaker RegrowthBomb ShrapnelBomb SmokeBomb WoollyBomb`, `items.Honeypot`, `actors.mobs.Bee`, `actors.blobs.Regrowth`.

**Scenarios (prefix `bomb.`, 13):** THROW beside a 100-HP rat, WAIT out the fuse (at most 3). Bomb rat HP; Arcane rat HP; Fire Fire volume; FlashBang rat Blindness; Frost rat Frost; Holy rat HP (undead rat-like `Skeleton` used); Noisemaker mobs alerted (state HUNTING); Regrowth Regrowth volume and grass; Shrapnel rat HP; Smoke SmokeScreen; Woolly Sheep. `bomb.enhance` brews an ArcaneBomb from Bomb + ingredient and covers `Bomb$EnhanceBomb`. `bomb.Honeypot` THROWs it: a Bee appears and attacks the rat.

- [ ] Standard cycle with `--only "bomb.*"`.
- **Mutation:** in `FlashBangBomb.explode`, skip the Blindness: expect `bomb.FlashBangBomb` red. Revert.
- **Commit:** `test(scenario): cover every bomb and the honeypot`.

### Task 10: Food and floor pickups

**Files:** Create `scenario/FoodScenarios.java`.

**Keys (20):** `items.food.Berry Blandfruit Blandfruit$CookFruit ChargrilledMeat FrozenCarpaccio PhantomMeat SmallRation StewedMeat StewedMeat$oneMeat StewedMeat$twoMeat StewedMeat$threeMeat SupplyRation`, `actors.buffs.WellFed`, `items.Gold Dewdrop Waterskin EnergyCrystal`, `items.journal.AlchemyPage GuidePage Guidebook`.

**Scenarios (prefixes `food.`, `pickup.`, 18):** `food.X` eats X hungry (Berry, PhantomMeat (extra effect: its buffs), SmallRation, SupplyRation); `food.cook-fruit` brews Blandfruit + Sungrass seed (`Blandfruit$CookFruit`) then eats it: Healing; `food.stew-1|2|3` brews StewedMeat from 1, 2, 3 MysteryMeat with `d.brewRecipe( StewedMeat.oneMeat.class )`, `twoMeat`, `threeMeat` (one recipe each; `Driver.brew( StewedMeat.class )` would always pick `oneMeat`) and eats one; `food.chargrill` MysteryMeat dropped in Fire becomes ChargrilledMeat, then eaten; `food.carpaccio` MysteryMeat frozen by Freezing becomes FrozenCarpaccio, then eaten (its extra effect); `food.MeatPie` eats a MeatPie: WellFed. `pickup.Gold` gold rises; `pickup.Dewdrop` HP rises; `pickup.Waterskin` dew collected into it then USE drinks: HP rises; `pickup.EnergyCrystal` `Dungeon.energy` rises; `pickup.AlchemyPage|GuidePage|Guidebook` the page's document is marked found.

- [ ] Standard cycle with `--only "food.*,pickup.*"`.
- **Mutation:** in `SupplyRation`'s eat override, skip its extra effect: expect `food.SupplyRation` red. Revert.
- **Commit:** `test(scenario): cover every food, cooking recipe and floor pickup`.

### Task 11: Melee weapons and Duelist abilities

**Files:** Create `scenario/MeleeScenarios.java`, and `scenario/WeaponAbilityScenarios.java`.

**Keys (32):** `items.weapon.melee.AssassinsBlade BattleAxe Crossbow Cudgel Dirk Flail Gauntlet Glaive Gloves Greataxe Greatshield Greatsword HandAxe Katana Longsword Mace Quarterstaff Rapier RoundShield RunicBlade Sai Scimitar Shortsword Sickle Spear WarHammer WarScythe Whip WornShortsword`, `heroclass:DUELIST`, `actors.buffs.Daze GreaterHaste` (32).

**Scenarios (prefixes `weapon.`, `wability.`, 60):** `weapon.X` for the 29 (WARRIOR, `kit.strength( 20 )`); `wability.X` for those 29 plus Dagger and Sword (DUELIST, weapon given and worn, `MeleeWeapon.Charger` charges set full in setup). Ability observables come from each weapon's `duelistAbility`: Mace rat Daze; any ability granting GreaterHaste (read the class) hero GreaterHaste; otherwise rat HP, position or buffs changed. `wability.Rapier` also covers `heroclass:DUELIST`.

- [ ] Standard cycle with `--only "weapon.*,wability.*"`.
- **Mutation:** in `Mace.duelistAbility`, skip the Daze: expect `wability.Mace` red. Revert.
- **Commit:** `test(scenario): cover every melee weapon and its Duelist ability`.

### Task 12: Armour, glyphs and armour curses

**Files:** Create `scenario/ArmourScenarios.java`.

**Keys (24):** `items.armor.MailArmor ScaleArmor PlateArmor`; `items.armor.glyphs.Affection AntiMagic Brimstone Camouflage Entanglement Flow Obfuscation Potential Repulsion Stone Swiftness Thorns Viscosity`; `items.armor.curses.AntiEntropy Bulk Corrosion Displacement Metabolism Multiplicity Overgrowth Stench`.

**Scenarios (prefixes `armor.`, `glyph.`, `acurse.`, 24):** observables from each `proc`: Affection attacker Charm; AntiMagic magic damage reduced (a Warlock attacker); Brimstone hero burning does not hurt; Camouflage hero Invisible in grass; Entanglement hero Roots + Barkskin; Flow speed in water (move time); Obfuscation hero stealth higher (`stealth()` getter); Potential hero wand charge rises; Repulsion attacker knocked back; Stone hero evasion zero, damage reduced; Swiftness move speed with no foe adjacent; Thorns attacker Bleeding; Viscosity damage deferred (`Viscosity$DeferedDamage`); AntiEntropy hero Frost or attacker burns; Bulk move time longer at a door; Corrosion CorrosiveGas; Displacement hero teleported; Metabolism hero heals and hunger rises; Multiplicity a MirrorImage or copy appears; Overgrowth grass/plant appears; Stench ToxicGas appears.

- [ ] Standard cycle with `--only "armor.*,glyph.*,acurse.*"`.
- **Mutation:** in `Thorns.proc`, skip the Bleeding: expect `glyph.Thorns` red. Revert.
- **Commit:** `test(scenario): cover armour tiers, every glyph and armour curse`.

### Task 13: Enchantments and weapon curses

**Files:** Create `scenario/EnchantScenarios.java` (split the weapon curses into `WeaponCurseScenarios.java` before it passes 500 lines).

**Keys (27):** `items.weapon.enchantments.Blazing Blocking Blooming Chilling Corrupting Crystal Elastic Eldritch Grim Kinetic Lucky Projecting Shocking Unstable Vampiric Venomous Vorpal`; `items.weapon.curses.Annoying Dazzling Displacing Explosive Friendly Polarized Pressurized Sacrificial Wayward Wondrous`.

**Scenarios (prefixes `enchant.`, `wcurse.`, 27):** observables from each `proc`: Blazing rat Burning; Blocking `Blocking$BlockBuff` on hero; Blooming grass appears; Chilling rat Chill; Corrupting a dying rat becomes ally; Crystal weapon repaired / shield; Elastic rat knocked back; Eldritch rat Terror or Vertigo; Grim a low-HP rat dies outright; Kinetic stored damage applied; Lucky rat drops loot; Projecting hits at range 2; Shocking Electricity arcs; Unstable some other enchant's observable (seed-pinned with `--seeds`); Vampiric hero HP rises; Venomous rat Poison; Vorpal rat Bleeding; Annoying mobs alerted; Dazzling hero Blindness; Displacing rat teleported; Explosive an explosion; Friendly rat Charm; Polarized damage doubled or zero; Pressurized gas burst; Sacrificial hero Bleeding; Wayward attacks miss more (accuracy getter); Wondrous a random wand effect (seed-pinned).

- [ ] Standard cycle with `--only "enchant.*,wcurse.*"`.
- **Mutation:** in `Venomous.proc`, skip the Poison: expect `enchant.Venomous` red. Revert.
- **Commit:** `test(scenario): cover every weapon enchantment and curse`.

### Task 14: Rings

**Files:** Create `scenario/RingScenarios.java`.

**Keys (11):** `items.rings.RingOfArcana RingOfElements RingOfEnergy RingOfEvasion RingOfForce RingOfFuror RingOfHaste RingOfMight RingOfSharpshooting RingOfTenacity RingOfWealth`.

**Scenarios (prefix `ring.`, 11):** USE equips the ring (+3, identified); getter compared before/after: `RingOfArcana.enchantPowerMultiplier`, `RingOfElements.resist` (for `Burning`), `RingOfEnergy.wandChargeMultiplier`, `RingOfEvasion.evasionMultiplier`, `RingOfForce.armedDamageBonus`/unarmed damage, `RingOfFuror.attackSpeedMultiplier`, `RingOfHaste.speedMultiplier`, `RingOfMight.strengthBonus`, `RingOfSharpshooting.levelDamageBonus`, `RingOfTenacity.damageMultiplier`, `RingOfWealth.dropChanceMultiplier` (use the exact static getters each class declares).

- [ ] Standard cycle with `--only "ring.*"`.
- **Mutation:** make `RingOfHaste.speedMultiplier` return 1: expect `ring.Haste` red. Revert.
- **Commit:** `test(scenario): cover every ring`.

### Task 15: Cleric spells

**Files:** Create `scenario/ClericScenarios.java` (split the subclass and ability spells into `ClericAdvancedScenarios.java` before it passes 500 lines). Modify `env/WindowBridge.java` (`WndClericSpells` in `CHOICE_WINDOWS`; its choices are `WndClericSpells$SpellButton`, an `IconButton`, so `buttons()` must collect visible, active `IconButton`s: Task 7 adds that, add it here if it is absent), `scenario/HeroKit.java` (presets).

**Interfaces:** Consumes `HeroKit subclass( HeroSubClass s )` (Task 5). Produces in `HeroKit`: `HeroKit ability( ArmorAbility a )` (sets and calls `Talent.initArmorTalents`; call it only inside a `Setup` lambda, never at catalogue load), `HeroKit talent( Talent t, int points )`.

**Keys (29):** `actors.hero.spells.AuraOfProtection BeamingRay BlessSpell BodyForm Cleanse DivineIntervention DivineSense Flash GuidingLight HallowedGround HolyIntuition HolyLance HolyWard HolyWeapon Judgement LayOnHands LifeLinkSpell MindForm MnemonicPrayer Radiance RecallInscription ShieldOfLight Smite SpiritForm Stasis Sunray WallOfLight`, `items.artifacts.HolyTome`, `heroclass:CLERIC`.

**Scenarios (prefix `spell.`, 27):** CLERIC; the spell unlocked in setup by `kit.talent`/`kit.subclass`/`kit.ability` (the spell is the covered key, not the talent); USE HolyTome, MENU index of the spell, aim when targeted; `d.witness( SpellClass )` after the effect assert. Effects from each spell's `onCast`, or `onTargetSelected` for targeted spells (`GuidingLight.java:59`): e.g. GuidingLight rat HP; HolyWeapon `HolyWeapon$HolyWepBuff`; ShieldOfLight hero shield; Sunray rat Blindness; DivineSense mind vision; BlessSpell Bless; Cleanse NEGATIVE buffs removed; Radiance rats Blindness; HolyLance rat HP; HallowedGround grass and healing; MnemonicPrayer buff extended; LayOnHands HP; AuraOfProtection aura buff; WallOfLight `WallOfLight$LightWall` blob; Smite rat HP; DivineIntervention shields; Judgement rats HP; Flash hero blinks; BodyForm/MindForm/SpiritForm their Trinity buffs; BeamingRay ally moved; LifeLinkSpell LifeLink; Stasis ally removed and stored; HolyIntuition item curse known; RecallInscription a used scroll/stone returns. `spell.GuidingLight` also covers HolyTome and `heroclass:CLERIC`.

- [ ] Standard cycle with `--only "spell.*"`.
- **Mutation:** in `GuidingLight.onTargetSelected`, skip the damage: expect `spell.GuidingLight` red. Revert.
- **Commit:** `test(scenario): cover every cleric spell through the holy tome`.

### Task 16: Tier-1 talents

**Files:** Create `scenario/TalentFamily.java` (builder) and `scenario/TalentTier1.java` (probe table).

**Interfaces:**
```java
final class TalentFamily {
	record Probe( Talent talent, HeroClass hero, Scenario.Setup setup, Scenario.Script trigger, java.util.function.BooleanSupplier effect, String failure ){}
	static Scenario of( Probe probe );
}
```
`of` builds the `Scenario` at catalogue load from enum constants only (`probe.hero`, `probe.talent`); it builds no game object there. Inside the Setup lambda it resolves the talent's owner by filling a scratch list with `Talent.initClassTalents( probe.hero, list )`, `Talent.initSubclassTalents( sub, list )` for each of the class's subclasses and `Talent.initArmorTalents( ability, list )` for each of `probe.hero.armorAbilities()` plus `new Ratmogrify()` (offered by the Rat King, not by `armorAbilities()`; these `ArmorAbility` instances exist only inside the lambda), throws `Driver.Stop` if the talent is not the class's, then sets `kit.level( Talent.tierLevelThresholds[ tier ] )`, the subclass/ability preset for tier 3/4, runs `probe.setup`. The script runs `d.talent( t )`, expects `pointsInTalent == 1`, runs `probe.trigger`, expects `probe.effect`. Id `talent<tier>.<NAME>`, covers `Coverage.talent( t )`.

**Keys (24):** `talent:HEARTY_MEAL VETERANS_INTUITION PROVOKED_ANGER IRON_WILL EMPOWERING_MEAL SCHOLARS_INTUITION LINGERING_MAGIC BACKUP_BARRIER CACHED_RATIONS THIEFS_INTUITION SUCKER_PUNCH PROTECTIVE_SHADOWS NATURES_BOUNTY SURVIVALISTS_INTUITION FOLLOWUP_STRIKE NATURES_AID STRENGTHENING_MEAL ADVENTURERS_INTUITION PATIENT_STRIKE AGGRESSIVE_BARRIER SATIATED_SPELLS HOLY_INTUITION SEARING_LIGHT SHIELD_OF_LIGHT`, plus `actors.buffs.WandEmpower PhysicalEmpower` (26).

**Probes:** triggers by group: meals (HEARTY_MEAL, EMPOWERING_MEAL, CACHED_RATIONS, NATURES_BOUNTY, STRENGTHENING_MEAL, SATIATED_SPELLS) eat food and see the meal effect (WandEmpower, PhysicalEmpower, heal, Barrier, berry/ration drop); intuitions (VETERANS_, SCHOLARS_, THIEFS_, SURVIVALISTS_, ADVENTURERS_, HOLY_) equip or carry the item class and see it identified sooner (`isIdentified`, or `cursedKnown`); combat (PROVOKED_ANGER, SUCKER_PUNCH, FOLLOWUP_STRIKE, PATIENT_STRIKE, SEARING_LIGHT) attack a rat and assert the talent's proc or buff (its tracker buff on the hero or the rat, or the proc's own observable, read from `Talent` and the hit code), never a damage comparison, since two damage rolls differ by RNG; defensive (IRON_WILL, BACKUP_BARRIER, PROTECTIVE_SHADOWS, NATURES_AID, AGGRESSIVE_BARRIER, SHIELD_OF_LIGHT) trigger the condition and see the shield/buff; LINGERING_MAGIC zap then melee: its bonus buff or proc.

- [ ] Standard cycle with `--only "talent1.*"`.
- **Mutation:** in `Talent.onFoodEaten`, skip the HEARTY_MEAL heal: expect `talent1.HEARTY_MEAL` red. Revert.
- **Commit:** `test(scenario): cover every tier-1 talent`.

### Task 17: Special rooms

**Files:** Create `scenario/RoomForcing.java`, `scenario/RoomScenarios.java`. Modify `scenario/Scenario.java` (`prepare`, `branch`), `scenario/ScenarioSetup.java` (`jump` runs `prepare` then transitions to `scenario.branch`; `landed` also checks `Dungeon.branch`), `scenario/World.java` (`room`), testing-guide scenariocheck row (prepare hook).

**Interfaces:**
```java
public Runnable prepare = () -> {};
public int branch = 0;
public Scenario prepare( Runnable before );
public Scenario branch( int branch );
public < T extends Room > T room( Class< T > type );
static Runnable special( Class< ? extends SpecialRoom > type );
static Runnable secret( Class< ? extends SecretRoom > type );
```
`special` sets `SpecialRoom.runSpecials` to three entries of `type` (it is public static; `initForFloor` clones it, `createRoom` picks among the three). `secret` sets `SecretRoom.runSecrets` likewise and, by reflection on the private `SecretRoom.regionSecretsThisRun`, the target depth's region count (`depth / 5`) to at least `floorsLeft = 5 - depth % 5`, so `secretsForFloor` yields at least one secret room deterministically (a count of 1 at depth 3 yields one only about half the time). `room` returns the instance from `((RegularLevel) level()).rooms()` or throws `NoFixture`. Both run in `jump()`, which the viewer's `ReplayPlayer` calls too, so both sides generate the same floor.

**Keys (29):** `levels.rooms.special.ArmoryRoom CryptRoom CrystalChoiceRoom CrystalPathRoom CrystalVaultRoom DemonSpawnerRoom GardenRoom LaboratoryRoom LibraryRoom MagicWellRoom MagicalFireRoom PitRoom PoolRoom RunestoneRoom SacrificeRoom SentryRoom SentryRoom$Sentry ShopRoom StatueRoom StorageRoom ToxicGasRoom TrapsRoom TreasuryRoom WeakFloorRoom`, `actors.mobs.ArmoredStatue`, `actors.buffs.Shadows`, `actors.blobs.Alchemy Foliage SacrificialFire`.

**Scenarios (prefix `room.`, 24), depth 2 unless the room needs more (DemonSpawnerRoom 22, CrystalChoice/Path/Vault 3, SentryRoom 6, MagicalFireRoom 6):** hero teleported beside the room's door in setup; every room forced by `RoomForcing.special` except `room.DemonSpawnerRoom`, which `HallsLevel.initRooms` adds unconditionally (`HallsLevel.java:92`) and so needs no forcing; interaction: Armory pick up a weapon/armour; Crypt open the tomb (heap) and a wraith/loot; CrystalChoice open one crystal door with a key; CrystalPath walk the crystal path doors; CrystalVault open with a crystal key; DemonSpawner kill the spawner (`DemonSpawner` from Task 29 is in its own key there); Garden Foliage blob and Shadows on hero inside; Laboratory brew at the pot (covers Alchemy); Library pick up a scroll; MagicWell drink; MagicalFire extinguish with a potion of frost and take the item; Pit (its entrance is a CRYSTAL door and it is the landing room of a fall) open the entrance with a CrystalKey, or arrive by falling through the floor above's WeakFloorRoom, then take the item; Pool dive for the item, piranhas attack; Runestone pick up stones; Sacrifice kill a rat on the altar: SacrificialFire volume, reward appears; Sentry walk past: `SentryRoom$Sentry` zaps; Shop buy; Statue the statue animates (and `room.ArmoredStatue` spawns the armoured variant directly); Storage pick up; ToxicGas ToxicGas vents; Traps cross and trigger one; Treasury pick up gold; WeakFloor fall to the next floor.

- [ ] Before step A: add a `ScenarioCheck` self-test that runs `room.ArmoryRoom` then `melee` in one process and requires `melee`'s recording to verify and `SpecialRoom.runSpecials` to hold the run's own queue again (not three `ArmoryRoom`s). `Dungeon.init` rebuilds the queue through `SpecialRoom.initForRun()` (`Dungeon.java:249`) on every reset, so the test passes with `RoomForcing` in place; prove it can fail by a local, uncommitted no-op of that `initForRun()` call: the self-test must go red naming the leaked queue. Revert with `git checkout -- core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java` and see it green.
- [ ] Standard cycle with `--only "room.*"`.
- [ ] After step E: `timeout 600 ./gradlew :desktop:viewcheck` once (the prepare hook runs in the viewer's `ReplayPlayer.jump()` path). Expected: the committed corpus plays clean.
- **Mutation:** make `RoomForcing.special` a no-op: expect the forced `room.*` scenarios (all but `room.DemonSpawnerRoom`) `NOFIXTURE` (`no seed gave a fixture`). Revert.
- **Commit:** `test(scenario): force and cover every special room`.

### Task 18: Secret rooms

**Files:** Create `scenario/SecretRoomScenarios.java`.

**Keys (14):** `levels.rooms.secret.RatKingRoom SecretArtilleryRoom SecretChestChasmRoom SecretGardenRoom SecretHoardRoom SecretHoneypotRoom SecretLaboratoryRoom SecretLarderRoom SecretLibraryRoom SecretMazeRoom SecretRunestoneRoom SecretSummoningRoom SecretWellRoom`, `actors.mobs.npcs.RatKing`.

**Scenarios (prefix `secret.`, 13), depth 3:** each forced by `RoomForcing.secret( type )` (Task 17), which raises the region's secret count to at least `floorsLeft` (2 at depth 3) so the floor always has a secret room; SEARCH reveals the secret door beside the hero (placed by setup next to the room's door), then the room's feature: RatKing talk (MENU); Artillery pick up missiles; ChestChasm reach a chest across the chasm (levitation); Garden pick up seeds; Hoard pick up gold; Honeypot pick up; Laboratory brew; Larder pick up food; Library pick up; Maze reach the prize; Runestone pick up; Summoning trigger a summoning trap; Well drink.

- [ ] Standard cycle with `--only "secret.*"`.
- **Mutation:** make `RoomForcing.secret` skip the region count: expect `secret.*` `NOFIXTURE`. Revert.
- **Commit:** `test(scenario): force and cover every secret room`.

### Task 19: Action space: item menu and action indicator; misc items

Closes the two largest reach gaps before the content that needs them.

**Files:** Modify `env/Action.java` (append `ITEM_MENU( Kind.ITEM, 0, 0 )` and `INDICATOR( Kind.MENU, 0, 0 )` **after** `THROW`), `env/SlotAction.java`, `env/ActionMapper.java` (masks), `env/SPDEnv.java`, `rl/EpisodeCollector.java` (`headFor`, `subModeAction`), `env/WindowBridge.java` (`WndResurrect`, item-menu window), `scenario/Driver.java` (`itemMenu`, `indicator`), the network/action-count sites the build points to, and the action lists in `superintelligence/README.md` and `docs/documentation.md` (the two appended actions). Create `scenario/ItemScenarios.java`.

**Interfaces:** `public void itemMenu( Class< ? extends Item > type, String verb )` (ITEM_MENU, slot, MENU index of `verb` in the item's `actions(hero)` minus DROP/THROW/EQUIP/UNEQUIP), `public void indicator()` (INDICATOR; masked unless `ActionIndicator.action != null`).

Rules: `ITEM_MENU` is masked for an item with fewer than two verbs besides DROP, THROW, EQUIP, UNEQUIP; selecting it opens a MENU listing those verbs in `actions(hero)` order (the game's `WndUseItem` semantics) and executes the chosen one exactly as `SlotAction.use` does for the default. `INDICATOR` calls `ActionIndicator.action.doAction()`; the result enters TARGETING or MENU through the existing paths. `USE` is unchanged. Old checkpoints no longer load (action count changed): `checkpointcheck` must refuse them with a clear message, as after the macro actions.

**Keys (27):** `items.ArcaneResin ArcaneResin$Recipe BrokenSeal LiquidMetal LiquidMetal$Recipe LostBackpack Stylus Torch`, `items.bags.MagicalHolster PotionBandolier ScrollHolder VelvetPouch`, `items.remains.BowFragment BrokenHilt BrokenStaff CloakScrap SealShard TornPage`, `items.wands.CursedWand` (extra), `actors.buffs.Light LostInventory`, `items.wands.WandOfRegrowth$Dewcatcher WandOfRegrowth$Dewcatcher$Seed WandOfRegrowth$Lotus WandOfRegrowth$Seedpod WandOfRegrowth$Seedpod$Seed WandOfWarding$Ward$WardSentry`.

**Scenarios (prefix `item.`, 21):** `item.ArcaneResin` brew it (recipe) then apply to a wand (picker): wand level +1; `item.LiquidMetal` brew then apply to a missile stack: durability restored; `item.BrokenSeal` WARRIOR affixes it to armour (picker): shield on hit; `item.LostBackpack` unblessed Ankh death then `WndResurrect` answered then LostInventory then pick up the LostBackpack: items back; `item.Stylus` inscribe armour (picker): glyph added; `item.Torch` USE: Light; `item.<Bag>` pick up the bag then the bag's item type: it goes inside, and ScrollHolder protects a scroll from Fire / PotionBandolier a potion from Freezing; `item.<Remains>` USE: its effect (read each class's `doEffect`); `item.CursedWand` zap a cursed wand at a rat: charge spent, world changed, `d.witness( CursedWand.class )`; `item.regrowth-plants` WandOfRegrowth +4 zapped: Dewcatcher, Seedpod and Lotus appear, their seeds drop; `item.WardSentry` WandOfWarding zapped thrice on one ward: it becomes a WardSentry. `item.menu` uses `itemMenu( MagesStaff.class, MagesStaff.AC_IMBUE )` (covers nothing new; proves the action) and `item.indicator` uses `indicator()` on a Gladiator's Combo (covers nothing new).

- [ ] Step 0 (before A): with the actions appended, `timeout 600 ./gradlew :superintelligence:paritycheck` and `timeout 300 ./gradlew :desktop:corpusplayback` must stay green with the corpus unchanged (Review Focus 3). Do not run `verifyall` here: the pre-commit hook runs it at step I.
- [ ] Standard cycle with `--only "item.*"`; also `freeactioncheck`, `actioncheck`, `teachercheck` run inside the hook.
- [ ] After step E: `timeout 600 ./gradlew :desktop:viewcheck` once (the replay action indices are what the viewer plays). Expected: the committed corpus plays clean. Step H also updates the action lists in `superintelligence/README.md` and `docs/documentation.md` with `ITEM_MENU` and `INDICATOR`.
- **Mutation:** make `ITEM_MENU` always masked: expect `item.menu` red with `ITEM_MENU is masked in WORLD`. Revert.
- **Commit:** `feat(env): item verb menu and action indicator actions; cover misc items`.

### Task 20: Artifacts

**Files:** Create `scenario/ArtifactScenarios.java`. Modify `env/AlchemyAction.java` (an equipped `AlchemistsToolkit` counts as a pot, as the game brews anywhere with it), `env/WindowBridge.java` (`WndGhostHero`).

**Keys (14):** `items.artifacts.AlchemistsToolkit CapeOfThorns ChaliceOfBlood DriedRose DriedRose$GhostHero HornOfPlenty LloydsBeacon MasterThievesArmband SandalsOfNature SkeletonKey TalismanOfForesight TimekeepersHourglass UnstableSpellbook`, `actors.buffs.TimeStasis`.

**Scenarios (prefix `artifact.`, 12, one per artifact):** each equipped by USE, charged in setup, used by USE or `itemMenu`: Toolkit brew with no pot; CapeOfThorns damage reflected; Chalice prick: HP drops then regen raised; DriedRose summon: GhostHero fights a rat (`WndGhostHero` answered when directing); HornOfPlenty snack: hunger falls; LloydsBeacon `itemMenu( SET )` then `RETURN`: position restored; Armband steal from a shopkeeper (or `AC_STEAL` target); Sandals root: rat Roots; SkeletonKey insert into a locked door; Talisman scry: cells mapped; Hourglass stasis: TimeStasis; Spellbook read: a scroll effect fires.

- [ ] Before step A, after the `AlchemyAction` change: `timeout 600 ./gradlew :superintelligence:paritycheck` (the toolkit pot changes the ALCHEMY menu indices that `chosen` and the corpus depend on). Expected: green with the corpus unchanged; record the result in the CHANGELOG entry and `out/evidence/mechanic-coverage/task-20.log`.
- [ ] Standard cycle with `--only "artifact.*"`.
- **Mutation:** in `LloydsBeacon`, make `AC_RETURN` a no-op: expect `artifact.LloydsBeacon` red. Revert.
- **Commit:** `test(scenario): cover every artifact`.

### Task 21: Trinkets

**Files:** Create `scenario/TrinketScenarios.java`. Modify `env/AlchemyAction.java` (remove the `TrinketCatalyst.Recipe` skip), `env/WindowBridge.java` (`WndTrinket`).

**Keys (20):** `items.trinkets.ChaoticCenser CrackedSpyglass DimensionalSundial ExoticCrystals EyeOfNewt FerretTuft MimicTooth MossyClump ParchmentScrap PetrifiedSeed RatSkull SaltCube ShardOfOblivion ThirteenLeafClover TrapMechanism TrinketCatalyst TrinketCatalyst$Recipe Trinket$UpgradeTrinket VialOfBlood WondrousResin`.

**Scenarios (prefix `trinket.`, 19):** 17 carried trinkets at level 3, one scenario each: the trinket is dropped as a heap beside the hero in setup, its static modifier getter is captured, a MOVE picks it up, and the getter must differ from the capture; active ones also: ChaoticCenser gas appears within 30 turns; ShardOfOblivion unidentified gear yields bonus; MimicTooth mimics revealed (`trinket.MimicTooth`). `trinket.catalyst` brews a TrinketCatalyst (recipe), USEs it, answers `WndTrinket`: a trinket in the pack. `trinket.upgrade` brews `Trinket$UpgradeTrinket`: trinket level +1.

- [ ] Before step A, after the `AlchemyAction` change: `timeout 600 ./gradlew :superintelligence:paritycheck` (dropping the skip changes the ALCHEMY menu indices that `chosen` and the corpus depend on). Expected: green with the corpus unchanged; record the result in the CHANGELOG entry and `out/evidence/mechanic-coverage/task-21.log`.
- [ ] Standard cycle with `--only "trinket.*"`.
- **Mutation:** make `RatSkull.exoticChanceMultiplier` ignore its level: expect `trinket.RatSkull` red. Revert.
- **Commit:** `test(scenario): cover every trinket and trinket crafting`.

### Task 22: Alchemy spells

**Files:** Create `scenario/SpellScenarios.java`. Modify `env/WindowBridge.java` (`WndAlchemizeItem`).

**Keys (23):** `items.spells.Alchemize BeaconOfReturning CurseInfusion MagicalInfusion PhaseShift ReclaimTrap Recycle SummonElemental TelekineticGrab UnstableSpell WildEnergy` and the `$Recipe` of each, `actors.mobs.Elemental$AllyNewBornElemental`.

**Scenarios (prefix `aspell.`, 11):** each brews the spell (`Driver.brew`, covers its recipe) then casts it: Alchemize item converted (`WndAlchemizeItem`); BeaconOfReturning set and return; CurseInfusion picked weapon cursed with a curse; MagicalInfusion picked item upgraded keeping its enchant; PhaseShift rat teleported; ReclaimTrap trap reclaimed then placed; Recycle item transformed; SummonElemental an `AllyNewBornElemental` fights a rat; TelekineticGrab heap pulled into the pack; UnstableSpell some scroll effect; WildEnergy wand charges and ArtifactRecharge.

- [ ] Standard cycle with `--only "aspell.*"`.
- **Mutation:** in `PhaseShift.affectTarget`, skip the teleport: expect `aspell.PhaseShift` red. Revert.
- **Commit:** `test(scenario): cover every alchemy spell and its recipe`.

### Task 23: Brews and elixirs

**Files:** Create `scenario/BrewScenarios.java`.

**Keys (34):** `items.potions.brews.AquaBrew BlizzardBrew CausticBrew InfernalBrew ShockingBrew UnstableBrew` and each `$Recipe`; `items.potions.elixirs.ElixirOfAquaticRejuvenation ElixirOfArcaneArmor ElixirOfDragonsBlood ElixirOfFeatherFall ElixirOfHoneyedHealing ElixirOfIcyTouch ElixirOfMight ElixirOfToxicEssence` and each `$Recipe`; `actors.blobs.Blizzard Inferno`; `actors.buffs.ArcaneArmor FireImbue FrostImbue ToxicImbue`.

**Scenarios (prefixes `brew.`, `elixir.`, 14):** brew then use. Aqua water terrain spreads; Blizzard Blizzard volume; Caustic rat Ooze; Infernal Inferno volume; Shocking Electricity; Unstable a random potion effect; AquaticRejuvenation `ElixirOfAquaticRejuvenation$AquaHealing`; ArcaneArmor ArcaneArmor; DragonsBlood FireImbue; FeatherFall `ElixirOfFeatherFall$FeatherBuff` and a chasm fall with no damage; HoneyedHealing HP and hunger; IcyTouch FrostImbue; Might `ElixirOfMight$HTBoost` and STR +1; ToxicEssence ToxicImbue.

- [ ] Standard cycle with `--only "brew.*,elixir.*"` (`brew.*` also re-runs Task-1 brews).
- **Mutation:** in `ElixirOfMight.apply`, skip the STR gain: expect `elixir.ElixirOfMight` red. Revert.
- **Commit:** `test(scenario): cover every brew and elixir with its recipe`.

### Task 24: Tier-2 talents

**Files:** Create `scenario/TalentTier2.java`.

**Keys (31):** `talent:IRON_STOMACH LIQUID_WILLPOWER RUNIC_TRANSFERENCE LETHAL_MOMENTUM IMPROVISED_PROJECTILES ENERGIZING_MEAL INSCRIBED_POWER WAND_PRESERVATION ARCANE_VISION SHIELD_BATTERY MYSTICAL_MEAL INSCRIBED_STEALTH WIDE_SEARCH SILENT_STEPS ROGUES_FORESIGHT INVIGORATING_MEAL LIQUID_NATURE REJUVENATING_STEPS HEIGHTENED_SENSES DURABLE_PROJECTILES FOCUSED_MEAL LIQUID_AGILITY WEAPON_RECHARGING LETHAL_HASTE SWIFT_EQUIP ENLIGHTENING_MEAL RECALL_INSCRIPTION SUNRAY DIVINE_SENSE BLESS`, `actors.buffs.ScrollEmpower`.

**Probes:** level 7. Meals (IRON_STOMACH, ENERGIZING_, MYSTICAL_, INVIGORATING_, FOCUSED_, ENLIGHTENING_) eat; potions (LIQUID_WILLPOWER, LIQUID_NATURE, LIQUID_AGILITY) drink a potion; scrolls (INSCRIBED_POWER: ScrollEmpower; INSCRIBED_STEALTH, RECALL_INSCRIPTION) read; kills (LETHAL_MOMENTUM, LETHAL_HASTE) kill a 1-HP rat; RUNIC_TRANSFERENCE, IMPROVISED_PROJECTILES, WAND_PRESERVATION, SHIELD_BATTERY, SWIFT_EQUIP, WEAPON_RECHARGING, DURABLE_PROJECTILES use the item kind; ARCANE_VISION, WIDE_SEARCH, SILENT_STEPS, ROGUES_FORESIGHT, REJUVENATING_STEPS, HEIGHTENED_SENSES, SUNRAY, DIVINE_SENSE, BLESS observe their passive or spell change.

- [ ] Standard cycle with `--only "talent2.*"`.
- **Mutation:** in `Talent.onScrollUsed`, skip INSCRIBED_POWER: expect `talent2.INSCRIBED_POWER` red. Revert.
- **Commit:** `test(scenario): cover every tier-2 talent`.

### Task 25: Remaining buffs

**Files:** Create `scenario/BuffScenarios.java`.

**Keys (12):** `actors.buffs.Degrade Fury GravityChaosTracker HeroDisguise Hex Invulnerability LifeLink Speed SuperNovaTracker SwarmIntelTracker Vulnerable Doom`.

**Scenarios (prefix `buff.`, 12):** applied by `kit.buff` (hero or a rat) in setup: Degrade worn item's effective level lower; Fury hero damage up at low HP; GravityChaosTracker chars pulled; HeroDisguise disguise class set; Hex target accuracy lower; Invulnerability damage ignored; LifeLink damage shared; Speed move time lower; SuperNovaTracker explosion after its countdown; SwarmIntelTracker swarm mobs alerted; Vulnerable damage taken higher; Doom damage doubled on a rat.

- [ ] Standard cycle with `--only "buff.*"`.
- **Mutation:** make `Vulnerable` a no-op in `Char.damage`: expect `buff.Vulnerable` red. Revert.
- **Commit:** `test(scenario): cover every remaining buff`.

### Task 26: Prison (floors 6-10) and the Wandmaker quest

**Files:** Create `scenario/PrisonScenarios.java`.

**Keys (18):** `actors.mobs.Bandit SpectralNecromancer Necromancer$NecroSkeleton Wraith TormentedSpirit RotHeart RotLasher Elemental$NewbornFireElemental Tengu`, `items.quest.CeremonialCandle CorpseDust CorpseDust$DustWraith Embers`, `levels.rooms.quest.MassGraveRoom RitualSiteRoom RotGardenRoom`, `levels.PrisonLevel PrisonBossLevel`.

**Scenarios (prefix `prison.`, 10):** fights by `FoeFamily.fight` (a non-null signature is ANDed with the mob-HP drop and replaces the generic hero-HP/negative-buff condition) for Bandit (signature: steals and blinds), SpectralNecromancer (raises Wraiths), NecroSkeleton (raised by a Necromancer), Wraith, TormentedSpirit (freed by RemoveCurse: turns ally/flees); Wandmaker quests forced by `prepare` setting the quest type (reflection on `Wandmaker.Quest`) at depth 7-9: MassGrave (CorpseDust picked up: DustWraiths spawn), RitualSite (CeremonialCandles placed: NewbornFireElemental spawns, Embers drop), RotGarden (RotLasher fights, RotHeart killed); `prison.descend` PrisonLevel TRAVEL_STAIRS; `prison.tengu` covers Tengu and PrisonBossLevel: bot-free (`judgeReach` true, scripted `Driver` steps only, unlike the bot-driven `boss.tengu10`), depth 10, hero `sturdy()` beside Tengu, MOVE-attacks until Tengu's HP drops.

- [ ] Standard cycle with `--only "prison.*"`.
- **Mutation:** in `Bandit.steal`, skip the Blindness: expect `prison.Bandit` red. Revert.
- **Commit:** `test(scenario): cover prison foes, the Wandmaker quests and the prison floor`.

### Task 27: Caves (floors 11-15) and the mines

**Files:** Create `scenario/CaveScenarios.java`. `WindowBridge` needs no change: `WndSmith` is nested in `WndBlacksmith`, which `CHOICE_WINDOWS` already holds, and `choice()` walks enclosing classes.

**Keys (26):** `actors.mobs.ArmoredBrute DM201 Pylon GnollGuard GnollSapper GnollGeomancer GnollExile FungalCore FungalSentry FungalSpinner CrystalGuardian CrystalSpire CrystalWisp DM300`, `items.quest.DarkGold Pickaxe MetalShard`, `levels.rooms.quest.BlacksmithRoom MineEntrance MineGiantRoom MineLargeRoom MineSecretRoom MineSmallRoom`, `levels.CavesLevel MiningLevel CavesBossLevel`. (`actors.mobs.DelayedRockFall` is excluded as a concrete base: only `GnollGeomancer$GnollRockFall` and `DM300$FallingRockBuff` are attached.)

**Scenarios (prefix `caves.`, 23):** fights for ArmoredBrute, DM201, GnollGuard, GnollSapper, GnollExile, FungalSentry, FungalSpinner, CrystalGuardian, CrystalWisp; passive: Pylon (DM300 arena, activated pylon zaps), FungalCore (killed ends the fungal quest), CrystalSpire (mined with the Pickaxe); `caves.GnollGeomancer` covers GnollGeomancer (boulders fall where marked: a `GnollGeomancer$GnollRockFall` appears and the marked cells are struck); mines (branch 1, depth 11-14, `branch( 1 )`, quest forced): enter by MineEntrance, MineGiant/Large/Small/Secret rooms reached, DarkGold mined with the Pickaxe (MOVE into the ore wall), Blacksmith reward answered through `WndSmith` inside `WndBlacksmith` (covers MetalShard as the ore payment where its code uses it); `caves.descend` CavesLevel; `caves.mine-level` MiningLevel; `caves.dm300` covers DM300 and CavesBossLevel: bot-free (`judgeReach` true, scripted `Driver` steps only, unlike the bot-driven `boss.dm300-15`), depth 15, hero `sturdy()` beside DM300, MOVE-attacks until DM300's HP drops.

- [ ] Standard cycle with `--only "caves.*"`.
- **Mutation:** in `GnollGeomancer`, skip spawning the rockfall: expect `caves.GnollGeomancer` red. Revert.
- **Commit:** `test(scenario): cover cave foes, the mines quest and both cave floors`.

### Task 28: City (floors 16-20) and the Imp

**Files:** Create `scenario/CityScenarios.java`. `DepthScenarios.java` is not modified: `boss.king20` is bot-driven and cannot carry covers, so the king's scenarios are new `city.*` ones.

**Keys (14):** `actors.mobs.Monk Senior Elemental$ChaosElemental DwarfKing DwarfKing$DKGhoul DwarfKing$DKGolem DwarfKing$DKMonk DwarfKing$DKWarlock`, `actors.mobs.npcs.ImpShopkeeper`, `items.quest.DwarfToken ImpStatue`, `levels.rooms.quest.AmbitiousImpRoom`, `levels.CityLevel CityBossLevel`.

**Scenarios (prefix `city.`, 10):** fights by `FoeFamily.fight` (a non-null signature is ANDed with the mob-HP drop and replaces the generic condition): Monk (signature: a `Monk$Focus` parry: the Monk holds `Monk.Focus` before a hero attack and the attack is parried, the buff detached, with no HP lost on that hit; Monk HP drops on a later hit), Senior, ChaosElemental (random effect); `city.king-minions` depth 20, DwarfKing summons each minion type (phase forced in setup) and the hero fights one of each; `city.dwarf-king` covers DwarfKing and CityBossLevel: bot-free (`judgeReach` true, scripted `Driver` steps only), depth 20, hero `sturdy()` beside DwarfKing, MOVE-attacks until its HP drops; `city.imp-quest` DwarfTokens collected and handed in; `city.ImpStatue` its use per its code; `city.AmbitiousImpRoom` the branch-exit room generated and entered (WndOptions answered); `city.ImpShopkeeper` after the quest, buy from the Imp's shop; `city.descend` CityLevel.

- [ ] Standard cycle with `--only "city.*"`.
- **Mutation:** in `Monk`, never apply `Monk.Focus` (skip the `Buff.affect( this, Focus.class )` in `Monk.act`, `Monk.java:88`): expect `city.Monk` red. Revert.
- **Commit:** `test(scenario): cover city foes, the king's minions and the Imp`.

### Task 29: Halls (floors 21-26) and victory

**Files:** Create `scenario/HallsScenarios.java`.

**Keys (19):** `actors.mobs.DemonSpawner Acidic YogDzewa YogDzewa$Larva YogDzewa$YogEye YogDzewa$YogRipper YogDzewa$YogScorpio YogFist$BrightFist YogFist$BurningFist YogFist$DarkFist YogFist$RottingFist YogFist$RustedFist YogFist$SoiledFist`, `levels.HallsLevel HallsBossLevel LastShopLevel LastLevel`, `items.Amulet`, `actors.buffs.AscensionChallenge`.

**Scenarios (prefix `halls.`, 12):** fights by `FoeFamily.fight` (a non-null signature is ANDed with the mob-HP drop and replaces the generic condition): Acidic (signature: corrodes), DemonSpawner (spawns demons, killed); six `halls.fist.<Type>` scenarios at depth 25, each forcing that fist type first (setup orders Yog's fist list) and fighting it, covering YogDzewa and HallsBossLevel; `halls.yog-minions` Larva, YogEye, YogRipper, YogScorpio spawned by Yog's phase and fought; `halls.descend` HallsLevel; `halls.shop` LastShopLevel buy; `halls.amulet` depth 26 LastLevel: covers Amulet, AscensionChallenge and LastLevel by the pickup: a MOVE picks up the Amulet, it is in the pack and AscensionChallenge is on the hero. The victory path (whether `SPDEnv` ends an episode `VICTORY` after the pickup) is not part of this task: add a `superintelligence/TODO.md` line for it, and do not edit `SPDEnv` or `RewardModel`.

- [ ] Standard cycle with `--only "halls.*"`.
- **Mutation:** in `Acidic.defenseProc`, skip the corrosion: expect `halls.Acidic` red. Revert.
- **Commit:** `test(scenario): cover hall foes, Yog's fists and minions, and the amulet`.

### Task 30: The vault branch

**Files:** Create `scenario/VaultScenarios.java` (split into `VaultRoomScenarios.java` past 500 lines).

**Keys (42):** `actors.mobs.quest.vault.VaultBossElemental VaultDM100 VaultDM200 VaultElemental$Fire VaultElemental$Frost VaultElemental$Shock VaultGhoul VaultGolem VaultRat VaultShaman VaultSkeleton`; `actors.mobs.npcs.VaultLaser VaultMirror VaultSentry VaultTokenDoor`; `levels.VaultLevel`; `actors.blobs.VaultFlameTraps`; `items.quest.EscapeCrystal VaultBeacon`; `levels.rooms.quest.vault.VaultAlternatingFireRoom VaultCircleRoom VaultCrossRoom VaultEnemyCenterRoom VaultEntranceRoom VaultFinalRoom VaultHallwayRoom VaultLasersRoom VaultLongRingsRoom VaultQuadrantsRoom VaultRingRoom VaultRingsRoom VaultSimpleEnemyTreasureRoom VaultTokensRoom`; `levels.rooms.quest.vault.treasure.VaultBookcaseTreasureRoom VaultCircleScanTreasureRoom VaultFlamePathRoom VaultFlamesTreasureRoom VaultHardLaserTreasureRoom VaultLaserTreasureRoom VaultManyScansRoom VaultMultipleEnemyTreasureRoom VaultSingleEnemyTreasureRoom`.

**Scenarios (prefix `vault.`, 36):** `branch( 1 ).depth( 16..19 )`, Imp quest state set in `prepare`; each room forced through a `RoomForcing.vault( Class )` runnable (reads `VaultLevel`'s room selection; add it in this task) and its device or enemy interacted with: lasers fire across the path, mirrors reflect, sentries zap, token doors open with a token, flame traps (VaultFlameTraps volume); each vault mob fought with `FoeFamily.fight`; VaultBossElemental fought to its first phase change; EscapeCrystal USE leaves the vault; VaultBeacon USE returns to the vault entrance.

- [ ] Standard cycle with `--only "vault.*"`.
- **Mutation:** make `VaultTokenDoor` never open: expect `vault.VaultTokensRoom` red. Revert.
- **Commit:** `test(scenario): cover the vault branch, its rooms, devices and foes`.

### Task 31: Subclasses

**Files:** Create `scenario/SubclassScenarios.java`. Modify `env/WindowBridge.java` (`WndCombo`, `WndMonkAbilities`).

**Keys (19):** `subclass:BERSERKER GLADIATOR BATTLEMAGE WARLOCK ASSASSIN FREERUNNER SNIPER WARDEN CHAMPION MONK PRIEST PALADIN`, `actors.buffs.Berserk Combo Momentum MonkEnergy Preparation SnipersMark SoulMark`.

**Scenarios (prefix `subclass.`, 12):** class at level 10 with TengusMask; USE, MENU index of the subclass, confirm (witness: subclass changed); then its mechanic: Berserker rage fills (Berserk) and `indicator()` triggers it; Gladiator Combo builds, `indicator()` + `WndCombo` finisher; Battlemage staff melee procs its wand; Warlock zap applies SoulMark, the hero heals on the marked rat's hits; Assassin Preparation then `indicator()` blink-strike; Freerunner Momentum and `indicator()` freerun; Sniper SnipersMark and `indicator()` special shot; Warden grass grants Barkskin/seed effects; Champion second weapon swap via `indicator()`; Monk MonkEnergy and `WndMonkAbilities` ability; Priest a priest spell; Paladin a paladin spell.

- [ ] Standard cycle with `--only "subclass.*"`.
- **Mutation:** in `Combo.hit`, skip the count: expect `subclass.GLADIATOR` red. Revert.
- **Commit:** `test(scenario): choose and use every subclass`.

### Task 32: Tier-3 talents

**Files:** Create `scenario/TalentTier3.java` (split by class pair past 500 lines).

**Keys (51):** `talent:HOLD_FAST STRONGMAN ENDLESS_RAGE DEATHLESS_FURY ENRAGED_CATALYST CLEAVE LETHAL_DEFENSE ENHANCED_COMBO DESPERATE_POWER ALLY_WARP EMPOWERED_STRIKE MYSTICAL_CHARGE EXCESS_CHARGE SOUL_EATER SOUL_SIPHON NECROMANCERS_MINIONS ENHANCED_RINGS LIGHT_CLOAK ENHANCED_LETHALITY ASSASSINS_REACH BOUNTY_HUNTER EVASIVE_ARMOR PROJECTILE_MOMENTUM SPEEDY_STEALTH POINT_BLANK SEER_SHOT FARSIGHT SHARED_ENCHANTMENT SHARED_UPGRADES DURABLE_TIPS BARKSKIN SHIELDING_DEW PRECISE_ASSAULT DEADLY_FOLLOWUP VARIED_CHARGE TWIN_UPGRADES COMBINED_LETHALITY UNENCUMBERED_SPIRIT MONASTIC_VIGOR COMBINED_ENERGY CLEANSE LIGHT_READING HOLY_LANCE HALLOWED_GROUND MNEMONIC_PRAYER LAY_ON_HANDS AURA_OF_PROTECTION WALL_OF_LIGHT`, `actors.buffs.EnhancedRings HoldFast RevealedArea`.

**Probes:** level 13, `kit.subclass` for subclass talents. Each probe triggers its talent's condition and compares the observable to the capture before selection; buffs: HOLD_FAST HoldFast on a WAIT, ENHANCED_RINGS EnhancedRings after an artifact use, SEER_SHOT RevealedArea after a bow shot.

- [ ] Standard cycle with `--only "talent3.*"`.
- **Mutation:** in `Talent`, skip HOLD_FAST's buff: expect `talent3.HOLD_FAST` red. Revert.
- **Commit:** `test(scenario): cover every tier-3 talent`.

### Task 33: Armour abilities

**Files:** Create `scenario/AbilityScenarios.java` (split the nineteen use scenarios into `AbilityUseScenarios.java` before it passes 500 lines). Modify `env/WindowBridge.java`: `WndUseTrinity`, `WndItemConfirm` and `Trinity` in `CHOICE_WINDOWS`. `Trinity` matches `Trinity$WndItemtypeSelect` through `choice()`'s enclosing-class walk; the simple name `WndItemtypeSelect` is not used, since `CustomNoteButton$WndItemtypeSelect` shares it.

**Keys (32):** `actors.hero.abilities.Ratmogrify Ratmogrify$TransmogRat`, `.cleric.AscendedForm PowerOfMany PowerOfMany$LightAlly Trinity`, `.duelist.Challenge ElementalStrike Feint Feint$AfterImage`, `.huntress.NaturesPower SpectralBlades SpiritHawk SpiritHawk$HawkAlly`, `.mage.ElementalBlast WarpBeacon WildMagic`, `.rogue.DeathMark ShadowClone ShadowClone$ShadowAlly SmokeBomb SmokeBomb$NinjaLog`, `.warrior.Endure HeroicLeap Shockwave`, `items.armor.ClericArmor DuelistArmor HuntressArmor MageArmor RogueArmor WarriorArmor`, `items.KingsCrown`.

**Scenarios (prefix `armor-ability.`, 25):** six `armor-ability.crown.<Class>`: wear the class's armour, USE KingsCrown, MENU choice (witness: ability changed), covering the class armour and KingsCrown; nineteen `armor-ability.<Name>`, one per ability, ability preset by `kit.ability` inside the Setup lambda (the ability's key is witnessed because `CoverageWitness.sample()` records `hero.armorAbility`'s class when it is non-null, Task 2), `ClassArmor.charge` 100, USE then aim: effect from each `activate` (TransmogRat from Ratmogrify; LightAlly from PowerOfMany; AfterImage from Feint; HawkAlly from SpiritHawk; ShadowAlly from ShadowClone; NinjaLog from SmokeBomb; Trinity answered through its windows).

- [ ] Standard cycle with `--only "armor-ability.*"`.
- **Mutation:** in `Shockwave.activate`, skip the damage: expect `armor-ability.Shockwave` red. Revert.
- **Commit:** `test(scenario): choose and use every armour ability`.

### Task 34: Tier-4 talents

**Files:** Create `scenario/TalentTier4.java` (split by class pair past 500 lines).

**Keys (58):** `talent:BODY_SLAM IMPACT_WAVE DOUBLE_JUMP EXPANDING_WAVE STRIKING_WAVE SHOCK_FORCE SUSTAINED_RETRIBUTION SHRUG_IT_OFF EVEN_THE_ODDS BLAST_RADIUS ELEMENTAL_POWER REACTIVE_BARRIER WILD_POWER FIRE_EVERYTHING CONSERVED_MAGIC TELEFRAG REMOTE_BEACON LONGRANGE_WARP HASTY_RETREAT BODY_REPLACEMENT SHADOW_STEP FEAR_THE_REAPER DEATHLY_DURABILITY DOUBLE_MARK SHADOW_BLADE CLONED_ARMOR PERFECT_COPY FAN_OF_BLADES PROJECTING_BLADES SPIRIT_BLADES GROWING_POWER NATURES_WRATH WILD_MOMENTUM EAGLE_EYE GO_FOR_THE_EYES SWIFT_SPIRIT CLOSE_THE_GAP INVIGORATING_VICTORY ELIMINATION_MATCH ELEMENTAL_REACH STRIKING_FORCE DIRECTED_POWER FEIGNED_RETREAT EXPOSE_WEAKNESS COUNTER_ABILITY DIVINE_INTERVENTION JUDGEMENT FLASH BODY_FORM MIND_FORM SPIRIT_FORM BEAMING_RAY LIFE_LINK STASIS HEROIC_ENERGY RATSISTANCE RATLOMACY RATFORCEMENTS`.

**Probes:** level 21, `kit.subclass` irrelevant, `kit.ability` set to the talent's ability (Ratmogrify for the three RAT talents, any for HEROIC_ENERGY); trigger = use the ability (`ClassArmor.charge` 100) and compare to the capture before selection (HEROIC_ENERGY: armour charge cost lower).

- [ ] Standard cycle with `--only "talent4.*"`.
- **Mutation:** in `Shockwave`, ignore EXPANDING_WAVE: expect `talent4.EXPANDING_WAVE` red. Revert.
- **Commit:** `test(scenario): cover every tier-4 talent`.

### Task 35: Close the ratchet and prove full coverage

**Files:** Delete `superintelligence/src/main/resources/coverage/pending.txt`. Modify `scenario/Coverage.java` (remove `pending()`), `diag/CoverageCheck.java` (remove checks 2 and the pending half of 3 and 4; any denominator key not declared is `uncovered`), testing-guide (coveragecheck row: no ratchet), CHANGELOG, TODO (move the worklist line to done), `superintelligence/README.md` (one sentence: every mechanic in the denominator is scenario-covered).

- [ ] **Step 1:** `timeout 300 ./gradlew :superintelligence:coveragecheck --args="--print-missing"`. Expected: no key printed. Any key printed: go back to its owning task; do not exclude it here.
- [ ] **Step 2:** remove the ratchet; `timeout 300 ./gradlew :superintelligence:coveragecheck`. Expected: `[OK]     coverage: 969 of 969 covered, 18 excluded` (the gate's own denominator).
- [ ] **Step 3:** `timeout 300 ./gradlew :superintelligence:scenariocheck`. Expected: every scenario `OK`, reach `POLICY`, `0 expected reach gaps, 0 known failures`; record the printed total ms and confirm it is under the 45 s trigger.
- [ ] **Step 4:** `timeout 900 ./gradlew :desktop:scenarioview` (full, ~5 min; nothing else running). Expected: `[OK]     rendered playback: <all> recordings played clean in the real viewer`. Save to `out/evidence/mechanic-coverage/scenarioview-full.log`.
- [ ] **Step 5: Mutation:** remove `.covers( AlarmTrap.class )` again: expect `uncovered: levels.traps.AlarmTrap`. Restore.
- [ ] **Step 6:** `timeout 600 ./gradlew test`; docs; commit: `feat(gates): require every game mechanic to be scenario-covered`.

---

## Totals

- Denominator: 969 keys (808 scanned + 160 talents + 12 subclasses + 6 hero classes + 1 extra - 18 excluded).
- Baseline after Task 1: 145 covered (141 classes, 4 hero classes), 824 pending; the key counts of Tasks 3-34 sum to exactly 824.
- Scenarios: 171 today, 710 added by Tasks 3-34, about 881 total.
- Expected `scenariocheck` wall: about 28 s (from 4.2 s); pre-commit hook about 45-50 s longer.
