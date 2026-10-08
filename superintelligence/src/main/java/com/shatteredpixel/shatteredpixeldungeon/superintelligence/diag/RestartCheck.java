package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.File;

/**
 * Checks that a restart rebuilds the run the recording was made from.
 *
 * The desktop viewer's restart re-enters {@code InterlevelScene}, which with a null hero calls
 * {@code Dungeon.init()} and builds floor 1 again. The whole feature depends on that producing the
 * same level the recording starts from; if it does not, the very first step diverges and playback
 * stops dead, which is what "R restarts on floor 2 and dies immediately" looked like from the outside.
 *
 * The worry is the random-number plumbing. {@code Dungeon.init()} pushes a generator seeded from the
 * run seed and reseeds the base, so a *first* init is reproducible by construction. A restart happens
 * long after the first one, with the generator stack and the base generator already churned by every
 * mob turn, combat roll and item drop since. Nothing in the code guarantees the stack is back where
 * it started, so this compares the two directly rather than reasoning about it.
 *
 * The comparison deliberately churns the generators with real turns before restarting, because an
 * untouched generator stack would prove nothing.
 *
 * Run with {@code gradle :superintelligence:restartcheck}.
 */
public class RestartCheck {

	private static final String SEED = "RESTART-CHECK";

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-restartcheck" ) );
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 2000;

		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEED, HeroClass.WARRIOR );

		String first = signature();

		//Churn the generators with real play, so the restart is not comparing two pristine states.
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), SEED.hashCode() );
		int[] slot = new int[ 1 ];
		int played = 0;

		for (int i = 0; i < 400 && env.running(); i++){
			Action action = policy.choose( env, slot );
			if (action == null || env.actionMask()[ action.index ] <= 0.5f ) break;
			env.step( action, slot[ 0 ] );
			played++;
		}

		restart( env.pipeline() );
		String second = signature();

		if (first.equals( second )){
			System.out.println( "[OK]     restart rebuilt the identical floor 1 after " + played
					+ " turns of play" );
			System.out.println( "         " + first );
			return;
		}

		System.out.println( "[FAIL]   restart did NOT rebuild the same floor 1" );
		System.out.println( "         before: " + first );
		System.out.println( "         after:  " + second );
		System.out.println( "         the replay viewer's R would diverge on its first step" );
		System.exit( 1 );
	}

	/**
	 * The restart path, mirroring {@code InterlevelScene.descend()} with a null hero.
	 *
	 * That branch is the one the viewer relies on: it calls {@code Dungeon.init()}, which resets
	 * depth to 1, and then builds the level. Leaving a hero in place instead takes the transition
	 * branch, which lands on the next floor.
	 */
	private static void restart( LevelPipeline pipeline ){
		Dungeon.hero = null;
		Dungeon.init();

		//Mirrors LevelPipeline.startRun: sprites are attached before the floor is built, not after.
		//Without this the hero had no sprite while the level was generated, and the emitter draws a
		//level makes - Emitter.start takes a Random.Float for its delay - did not happen, so the same
		//seed produced a different floor and this check failed on a heap count.
		pipeline.attachSprites();

		Level level = Dungeon.newLevel();
		Dungeon.switchLevel( level, -1 );
	}

	/** A cheap fingerprint of everything a replay's first step depends on. */
	private static String signature(){
		Level level = Dungeon.level;

		int tiles = 0;
		int[] map = level.map;
		for (int i = 0; i < map.length; i++) tiles = tiles * 31 + map[ i ];

		return "depth=" + Dungeon.depth + " branch=" + Dungeon.branch
				+ " len=" + level.length()
				+ " mapHash=" + tiles
				+ " heaps=" + level.heaps.size
				+ " heroPos=" + (Dungeon.hero == null ? -1 : Dungeon.hero.pos)
				+ " heroHp=" + (Dungeon.hero == null ? -1 : Dungeon.hero.HP)
				+ " items=" + (Dungeon.hero == null ? -1 : Dungeon.hero.belongings.backpack.items.size());
	}
}
