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


public class LevelPipeline {


	public enum Transition {

		DESCEND,

		ASCEND,

		FALL,

		NONE
	}

	private final HeadlessGame game;


	private static final int MAX_SEED_TEXT_LENGTH = 20;


	public LevelPipeline( HeadlessGame game ){
		this.game = game;
	}


	public HeadlessGame game(){
		return game;
	}


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


	public void attachSprites(){
		spriteFor( Dungeon.hero );
		attachBlobEmitters();
		if (Dungeon.level != null){
			for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob : Dungeon.level.mobs){
				spriteFor( mob );
			}
		}
	}


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

	public enum Outcome {		
		READY,

		HERO_DEAD,

		TRANSITION,

		STALLED,

		STEP_LIMIT
	}


	private void attachLateSprites(){
		if (Dungeon.level == null) return;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob : Dungeon.level.mobs){
			if (!(mob.sprite instanceof HeadlessSprite)){
				spriteFor( mob );
			}
		}

		attachBlobEmitters();
	}


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


	private static void fixTimeBeforeSwitching(){
		com.shatteredpixel.shatteredpixeldungeon.actors.Actor.fixTime();
	}

	public void clearSwitchRequest(){
		game.clearSwitchRequest();
		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.curTransition = null;

		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.mode = null;
	}


	public static String currentSeedText(){
		return Dungeon.customSeedText;
	}

	public static long currentSeed(){
		return Dungeon.seed;
	}


	public static String formatSeed( long seed ){
		return DungeonSeed.convertToCode( seed );
	}
}
