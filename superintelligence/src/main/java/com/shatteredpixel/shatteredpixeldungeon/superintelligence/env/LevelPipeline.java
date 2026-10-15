package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.Chasm;
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

	/** Mirrors the cap {@code SPDSettings.customSeed()} reads with. See requireSeedSticks. */
	private static final int MAX_SEED_TEXT_LENGTH = 20;

	/**
	 * Falls back to a random seed when no seed is locked. research.md's "Generalizing Across
	 * Seeds" plan starts locked, but the trainer eventually hands out -1 to force generalisation.
	 */
	public LevelPipeline( HeadlessGame game ){
		this.game = game;
	}

	/**
	 * The game this pipeline drives.
	 *
	 * <p>Exposed so a harness can ask whether a scene switch is pending - {@code :desktop:playbackcheck}
	 * has no render loop, so nothing raises the acknowledgement {@link HeadlessGame#switchRequested}
	 * reads, and a recording that descends cannot be played without something standing in for it.
	 */
	public HeadlessGame game(){
		return game;
	}

	/**
	 * Services a pending level transition.
	 *
	 * @param heroClass which hero to play.
	 * @param challenges challenge bitmask, see {@link com.shatteredpixel.shatteredpixeldungeon.Challenges}.
	 */
	public void startRun( String seedText, HeroClass heroClass, int challenges ){

		Dungeon.daily = false;
		Dungeon.dailyReplay = false;

		SPDSettings.challenges( challenges );
		SPDSettings.customSeed( seedText == null ? "" : seedText );












		SPDSettings.intro( false );







		requireSeedSticks( seedText );

		GamesInProgress.selectedClass = heroClass;
		GamesInProgress.curSlot = 0;




		Dungeon.initSeed();
		Dungeon.init();



		attachSprites();




		Dungeon.generatedLevels.clear();

		Dungeon.depth = 1;
		Dungeon.branch = 0;

		Level level = Dungeon.newLevel();
		Dungeon.switchLevel( level, level.getTransition( null ).cell() );

		clearSwitchRequest();
	}

	/**
	 * Fails if the requested seed text did not survive the round trip through preferences.
	 *
	 * An empty requested seed is fine and means "random". Anything else has to come back
	 * byte-for-byte, otherwise the run is silently not the run that was asked for.
	 */
	private static void requireSeedSticks( String seedText ){
		String requested = (seedText == null) ? "" : seedText;
		String stored = SPDSettings.customSeed();

		if (!requested.equals( stored )){
			throw new IllegalArgumentException( "seed text was not applied: asked for \""
					+ requested + "\", preferences hold \"" + stored + "\""
					+ (requested.length() > MAX_SEED_TEXT_LENGTH
						? ". The game caps custom seed text at " + MAX_SEED_TEXT_LENGTH
						  + " characters and silently discards anything longer."
						: "") );
		}
	}

	/**
	 * Gives every actor a {@link HeadlessSprite}. Mobs are created during
	 * {@code Level.createMobs()} and the hero during {@code Dungeon.init()}, and actors spawned
	 * later (respawns, summons, projectile Chars) are handled lazily by the scheduler.
	 */
	public void attachSprites(){
		spriteFor( Dungeon.hero );
		attachBlobEmitters();
		if (Dungeon.level != null){
			for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob : Dungeon.level.mobs){
				spriteFor( mob );
			}
		}
	}

	/**
	 * Gives every blob a {@link com.shatteredpixel.shatteredpixeldungeon.effects.BlobEmitter}.
	 *
	 * In the game these are created by {@code GameScene.addBlobSprite}, which is the same class of
	 * problem as a missing sprite. Fifteen blob types call {@code emitter.pour(...)} from
	 * {@code evolve()} with no null check, so a headless run that reached any of them - a Web from a
	 * wand, ToxicGas from a creature, SacrificialFire from a flame - died with an NPE. The emitter
	 * never draws anything without a scene, so attaching one costs the allocation and nothing else.
	 */
	public static void attachBlobEmitters(){
		if (Dungeon.level == null) return;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob : Dungeon.level.blobs.values()){
			if (blob.emitter == null){
				new com.shatteredpixel.shatteredpixeldungeon.effects.BlobEmitter( blob );
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

				return Outcome.TRANSITION;
			}

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
				return Outcome.HERO_DEAD;
			}

			attachLateSprites();



























			if (Dungeon.level != null
					&& Dungeon.hero.pos >= 0
					&& Dungeon.hero.pos < Dungeon.level.length()
					&& Dungeon.level.pit[ Dungeon.hero.pos ]
					&& !Dungeon.hero.flying
					&& Dungeon.hero.buff(
							com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfFeatherFall.FeatherBuff.class ) == null ){
				Chasm.heroFall( Dungeon.hero.pos );
				return Outcome.TRANSITION;
			}







			boolean wantsMore = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.headlessStep();

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
				return Outcome.HERO_DEAD;
			}











			if (Dungeon.hero.curAction == null && Dungeon.hero.ready && Dungeon.hero.paralysed == 0){
				return Outcome.READY;
			}












			if (!wantsMore && steps > 8 && Dungeon.hero.resting){
				return Outcome.STALLED;
			}
		}

		return Outcome.STEP_LIMIT;
	}

	public enum Outcome {		/** Hero is waiting for the next action. Normal. */
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

		attachBlobEmitters();
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
		fixTimeBeforeSwitching();

		LevelTransition transition = com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.curTransition;
		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.Mode mode =
				com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.mode;

		if (mode == com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.Mode.FALL){
			clearSwitchRequest();
			return Transition.FALL;
		}

		if (transition == null){
			clearSwitchRequest();
			return Transition.NONE;
		}








		Transition result = (transition.type == LevelTransition.Type.REGULAR_EXIT
				|| transition.type == LevelTransition.Type.BRANCH_EXIT)
				? Transition.DESCEND
				: Transition.ASCEND;

		Dungeon.depth    = transition.destDepth;
		Dungeon.branch   = transition.destBranch;



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
	 *
	 * <p><b>One thing is deliberately not mirrored.</b> {@code InterlevelScene.fall()} opens with
	 * {@code Mob.holdAllies(Dungeon.level)}, which parks the floor's allies for a summon to pick up on
	 * the far side. That method still uses {@code Collections.shuffle}, so it draws from an unseeded
	 * generator - {@code ENGINE-CHANGES.md} §7 has it open. Copying it here would make every fall in the
	 * trainer consume an unseeded value and put reproducibility back exactly where it was, for the sake
	 * of a behaviour no policy in this project can reach. When the shuffle is seeded, this gets the
	 * call with it.
	 */
	public void handleFall(){
		fixTimeBeforeSwitching();





		Buff.affect( Dungeon.hero, com.shatteredpixel.shatteredpixeldungeon.levels.features.Chasm.Falling.class );



		Dungeon.depth++;
		Dungeon.generatedLevels.clear();

		Level level = Dungeon.newLevel();





		Dungeon.switchLevel( level, level.fallCell(
				com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.fallIntoPit ));

		attachSprites();
		PathFinder.setMapSize( level.width(), level.height() );

		clearSwitchRequest();
	}

	/**
	 * The step the real game takes on its way out of a floor, and the one this pipeline was missing.
	 *
	 * <p>{@code InterlevelScene}'s descend thread opens with {@code Actor.fixTime()}, which pulls every
	 * actor's time back by the smallest of them and moves {@code Actor.now} down with it. It runs
	 * <i>before</i> {@code Dungeon.newLevel()}, and {@code newLevel} then calls {@code Actor.clear()},
	 * which zeroes the clock. Net effect on the way to a new floor: every actor arrives at time 0,
	 * including the hero, and the elapsed time goes into {@code Statistics.duration}.
	 *
	 * <p>Without it the hero kept the time it had on the floor above - 128 on a typical mid-run descent -
	 * and every other actor on the new floor started at 0. So the scheduler had to work the new floor's
	 * sleeping mobs up to the hero before the hero could be picked again, and the run's engine clock
	 * jumped by the whole previous floor's length on arrival. Measured on {@code duelist-mid}: the trainer
	 * recorded {@code engine time 129.0} on the descent step where the viewer read {@code 0.0}, and
	 * {@code 130.0} on the step after where the viewer read {@code 1.0} -
	 * {@code DIVERGED at step 158 - engine time is 0.0, recording says 129.0}.
	 *
	 * <p>It is the fifth instance of the class {@code PLAN-viewer-fidelity.md} §6 records: something the
	 * game does that only one side of this project was doing, found the same way as the last four - by
	 * replaying a recording in the real game and reading what it said.
	 */
	private static void fixTimeBeforeSwitching(){
		com.shatteredpixel.shatteredpixeldungeon.actors.Actor.fixTime();
	}

	public void clearSwitchRequest(){
		game.clearSwitchRequest();
		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.curTransition = null;






		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.mode = null;
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
