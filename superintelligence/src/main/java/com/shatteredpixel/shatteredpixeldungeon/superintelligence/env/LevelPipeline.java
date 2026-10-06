package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessSprite;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;
import com.watabou.utils.PathFinder;

/**
 * Starts runs and moves between floors without ever entering the scene system.
 *
 * research.md asks for the level pipeline to be driven headlessly, and identifies the exact
 * obstacle: {@code InterlevelScene} is 100% presentation wrapped around one thread that calls
 * {@code Dungeon.newLevel()} / {@code Dungeon.switchLevel()}. So this class does the same thing
 * directly, and reads the pending-transition signal that {@code Level.activateTransition} writes.
 *
 * Three things make the real pipeline unsuitable for rollout:
 * <ol>
 *   <li>{@code InterlevelScene} runs level generation on its own Thread and hands control to a
 *       new Scene. A rollout must stay single threaded to stay reproducible.</li>
 *   <li>{@code Dungeon.switchLevel} ends in {@code saveAll()}, a full hero serialisation plus two
 *       file writes. {@link HeadlessServices#disableSaving} redirects that.</li>
 *   <li>{@code InterlevelScene} calls {@code loadLevel} when {@code Dungeon.generatedLevels}
 *       contains the depth, which would silently reuse a cached floor. Always regenerating is
 *       what makes a seed reproducible from the base seed alone.</li>
 * </ol>
 */
public class LevelPipeline {

	/** What the game asked to do when it requested a scene switch. */
	public enum Transition {
		/** Walk onto the up-stairs of the floor below. */
		DESCEND,
		/** Walk back up the stairs you came down. */
		ASCEND,
		/** {@code Chasm} dropped us a floor without using stairs. */
		FALL,
		/** The pending switch was not a level transition at all. */
		NONE
	}

	private final HeadlessGame game;

	/**
	 * Falls back to a random seed when no seed is locked. research.md's "Generalizing Across
	 * Seeds" plan starts locked, but the trainer eventually hands out -1 to force generalisation.
	 */
	public LevelPipeline( HeadlessGame game ){
		this.game = game;
	}

	/**
	 * Boots a brand new run.
	 *
	 * @param seedText  any string {@link DungeonSeed#convertFromText} accepts, or null/empty for
	 *                  a random seed.
	 * @param heroClass which hero to play.
	 * @param challenges challenge bitmask, see {@link com.shatteredpixel.shatteredpixeldungeon.Challenges}.
	 */
	public void startRun( String seedText, HeroClass heroClass, int challenges ){

		Dungeon.daily = false;
		Dungeon.dailyReplay = false;

		SPDSettings.challenges( challenges );
		SPDSettings.customSeed( seedText == null ? "" : seedText );

		GamesInProgress.selectedClass = heroClass;
		GamesInProgress.curSlot = 0;

		//Dungeon.initSeed() turns the custom seed text into Dungeon.seed, and Dungeon.init() does
		//the rest of the per-run reset: label/colour initialisation, Generator state, quest state,
		//and hero construction. Between them these two are the complete definition of "new run".
		Dungeon.initSeed();
		Dungeon.init();

		//Dungeon.init() built a Hero with no sprite. Several code paths (Hero.ready, Char.move,
		//CharSprite callbacks) dereference ch.sprite, so every actor gets a headless one.
		attachSprites();

		//Dungeon.newLevel() picks the Level subclass for depth 1 and calls create(). It also
		//records the depth in generatedLevels; we clear it first so nothing is ever loaded from
		//disk and a rerun of the same seed rebuilds identical floors.
		Dungeon.generatedLevels.clear();

		Dungeon.depth = 1;
		Dungeon.branch = 0;

		Level level = Dungeon.newLevel();
		Dungeon.switchLevel( level, level.getTransition( null ).cell() );

		clearSwitchRequest();
	}

	/**
	 * Gives every actor a {@link HeadlessSprite}. Mobs are created during
	 * {@code Level.createMobs()} and the hero during {@code Dungeon.init()}, and actors spawned
	 * later (respawns, summons, projectile Chars) are handled lazily by the scheduler.
	 */
	public void attachSprites(){
		spriteFor( Dungeon.hero );
		if (Dungeon.level != null){
			for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob : Dungeon.level.mobs){
				spriteFor( mob );
			}
		}
	}

	public static void spriteFor( com.shatteredpixel.shatteredpixeldungeon.actors.Char ch ){
		if (ch == null) return;
		if (ch.sprite instanceof HeadlessSprite) return;
		HeadlessSprite sprite = new HeadlessSprite();
		sprite.link( ch );
	}

	/**
	 * Drains the actor scheduler until the hero is ready to accept a new action, the hero died, or
	 * the game asked for a scene switch.
	 *
	 * research.md: "It must yield control to the game engine's internal queue and only return the
	 * new state to the AI when the Hero is explicitly queried for its next action."
	 *
	 * {@code Dungeon.hero.ready} is exactly that signal: {@code Hero.ready()} sets it after
	 * resolving an action, and clears it at the start of the next one.
	 *
	 * @return why the loop stopped.
	 */
	public Outcome runToHeroReady( int maxActorSteps ){
		int steps = 0;

		while (steps++ < maxActorSteps){

			if (game.switchRequested()){
				//the game is handing off, eg. to a level transition
				return Outcome.TRANSITION;
			}

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
				return Outcome.HERO_DEAD;
			}

			attachLateSprites();

			//Give the scheduler a step before testing readiness.
			//
			//Injecting an action sets Hero.curAction and calls Hero.next(), but Hero.ready is only
			//cleared inside Hero.act(). Testing readiness first would always see a stale true, the
			//hero would never act, and the run would sit still for its whole turn budget - a silent
			//stall that looks exactly like an agent that has chosen not to move.
			boolean wantsMore = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.headlessStep();

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
				return Outcome.HERO_DEAD;
			}

			if (Dungeon.hero.ready && Dungeon.hero.paralysed == 0){
				return Outcome.READY;
			}

			//nothing left to act and the hero still cannot be given input: the engine is waiting on
			//something the agent cannot influence. Surfacing that beats a silent hang.
			if (!wantsMore && steps > 8){
				return Outcome.STALLED;
			}
		}

		return Outcome.STEP_LIMIT;
	}

	public enum Outcome {
		/** Hero is waiting for the next action. Normal. */
		READY,
		/** Hero died. */
		HERO_DEAD,
		/** A floor transition is pending; call {@link #handleTransition}. */
		TRANSITION,
		/** Engine is waiting on something the agent cannot influence. Treated as terminal. */
		STALLED,
		/** Guard against pathological mob chains; treated as terminal. */
		STEP_LIMIT
	}

	/**
	 * Actors created after {@link #attachSprites} - respawns, bosses' summons, projectiles, the
	 * Iron Maiden's spikes. Cheap enough to check every scheduler pass: one HashSet allocation
	 * avoided by reusing the level's own mob set, and an instanceof per mob.
	 */
	private void attachLateSprites(){
		if (Dungeon.level == null) return;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob : Dungeon.level.mobs){
			if (!(mob.sprite instanceof HeadlessSprite)){
				spriteFor( mob );
			}
		}
	}

	/**
	 * Services a pending level transition.
	 *
	 * {@code Level.activateTransition} has already stashed the transition in
	 * {@code InterlevelScene.curTransition} and picked a mode. We read them, generate the next
	 * floor and place the hero, then acknowledge the switch so the scheduler resumes.
	 *
	 * @return the transition that was serviced.
	 */
	public Transition handleTransition(){

		LevelTransition transition = com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.curTransition;
		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.Mode mode =
				com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.mode;

		if (transition == null){
			clearSwitchRequest();
			return Transition.NONE;
		}

		//Chasm falls are not driven by a LevelTransition; the hero's cell is resolved by
		//Level.fallCell() after the depth has been bumped.
		if (mode == com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.Mode.FALL){
			clearSwitchRequest();
			return Transition.FALL;
		}

		Transition result = (transition.type == LevelTransition.Type.REGULAR_EXIT
				|| transition.type == LevelTransition.Type.BRANCH_EXIT)
				? Transition.DESCEND
				: Transition.ASCEND;

		Dungeon.depth    = transition.destDepth;
		Dungeon.branch   = transition.destBranch;

		//Always regenerate. loadLevel would restore a floor from disk and quietly break
		//reproducibility, which is the whole basis of the locked-seed comparison in docs.md.
		Dungeon.generatedLevels.clear();
		Level level = Dungeon.newLevel();

		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.curTransition = null;

		LevelTransition dest = level.getTransition( transition.destType );
		int pos = (dest != null) ? dest.cell() : -2;

		Dungeon.switchLevel( level, pos );

		attachSprites();
		PathFinder.setMapSize( level.width(), level.height() );

		clearSwitchRequest();

		return result;
	}

	/**
	 * Services a Chasm fall: one floor down, placed on a survivable cell.
	 * Mirrors {@code InterlevelScene.fall()}.
	 */
	public void handleFall(){
		Dungeon.depth++;
		Dungeon.branch = 0;
		Dungeon.generatedLevels.clear();

		Level level = Dungeon.newLevel();
		Dungeon.switchLevel( level, level.fallCell( true ) );

		attachSprites();
		PathFinder.setMapSize( level.width(), level.height() );

		clearSwitchRequest();
	}

	public void clearSwitchRequest(){
		game.clearSwitchRequest();
		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.curTransition = null;
	}

	/** Convenience for diagnostics: the seed the current run is locked to. */
	public static String currentSeedText(){
		return Dungeon.customSeedText;
	}

	public static long currentSeed(){
		return Dungeon.seed;
	}

	/** Format a seed for a replay file, using the game's own human-readable encoding. */
	public static String formatSeed( long seed ){
		return DungeonSeed.convertToCode( seed );
	}
}
