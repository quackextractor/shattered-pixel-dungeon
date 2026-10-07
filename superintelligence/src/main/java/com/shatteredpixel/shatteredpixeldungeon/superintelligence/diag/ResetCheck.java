package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A reset must make the environment independent of everything that ran before it.
 *
 * <pre>gradle :superintelligence:resetcheck</pre>
 *
 * <p><b>Why this is a gate.</b> {@code restartcheck} compares the level a restart rebuilds, and it
 * passes. It did not catch this, because the fault does not touch the level: it touched the
 * <i>mode</i>. Same seed, same tiles, same hero position, same HP - and the episode still diverged,
 * because the first action was read as picking a target instead of being applied.
 *
 * <p><b>The fault.</b> {@code GameScene.pendingCellListener} is static and belongs to the process, not
 * to a run. {@code SPDEnv.step} cleared it on entry, so it was tidy between steps; {@code reset} did
 * not, so whatever armed it last - an item left mid-aim, which is exactly what {@code USE} of a
 * targeting item does - stayed armed into the next episode's first {@code settle()}. {@code settle}
 * reports TARGETING, the agent's first action becomes a target choice, the hero never moves, and the
 * recorded position mismatches on step 0.
 *
 * <p><b>Why nothing else saw it.</b> It is invisible to a single episode: one process, one reset,
 * nothing to leak from. So every check that verifies one recording in one process passed, and the
 * trainer's own collector round-tripped. It needs a second episode in the same process to appear -
 * which is what the replay viewer does on every file after the first, and what a trainer worker does
 * thousands of times.
 *
 * <p>Measured before the fix: 4 of 10 recorded runs diverged when verified in sequence, and one file
 * verified clean on its own and diverged when reached second.
 */
public class ResetCheck {

	private static final int CHECKS = 3;

	private static final List< String > failures = new ArrayList<>();

	/** Long enough to reach the item-and-inventory dance at step ~290, where the stale recordings broke. */
	private static final int TRACE_STEPS = 320;

	private static final String[] SEEDS = {
		"RESETCHECK-A", "RESETCHECK-B", "RESETCHECK-C", "RESETCHECK-D"
	};

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-resetcheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 2000;
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );

		checkResetClearsAnArmedAim( env, config );
		checkEveryEpisodeStartsInWorld( env, config );
		checkTrajectoriesDoNotDependOnOrder( env, config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     reset isolation: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  reset isolation: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * An armed aim request must not decide the mode of the next episode.
	 *
	 * <p>Armed through {@code GameScene.selectCell}, which is the real path: it is what a wand, a
	 * throwable or an armour ability calls, and with no renderer it parks the request in the static
	 * this is about. Simulating the arming directly rather than hunting for a targeting item keeps the
	 * case deterministic - item drops depend on the seed, and a check that only fires on the seeds that
	 * happen to produce a scroll is not a check.
	 *
	 * <p><b>The assertion is on the mode, not on the listener.</b> It would be easy to assert that
	 * {@code pendingCellListener()} comes back null, and that assertion is simply false:
	 * {@code Hero.ready()} calls {@code GameScene.ready()}, which calls
	 * {@code selectCell(defaultCellListener)}, so the listener is armed again on every turn where the
	 * hero is waiting for input. That is the normal state, not leakage. What must not happen is the
	 * listener from the *previous* episode being visible to {@code settle}'s very first check, before
	 * the pipeline has run a single turn - that is what put the new episode straight into TARGETING.
	 */
	private static void checkResetClearsAnArmedAim( SPDEnv env, EnvConfig config ){
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );

		GameScene.selectCell( new CellSelector.Listener(){
			@Override public void onSelect( Integer cell ){}
			@Override public String prompt(){ return "test"; }
		} );

		//guard: the arming has to have worked or the case passes for the wrong reason
		if (GameScene.pendingCellListener() == null){
			fail( "GameScene.selectCell did not arm a pending listener, so the case cannot test whether"
					+ " reset disarms one." );
			return;
		}

		env.reset( SEEDS[ 1 ], HeroClass.WARRIOR );

		if (env.mode() != EnvMode.WORLD){
			fail( "an episode began in " + env.mode() + " instead of WORLD while an aim request from the"
					+ " previous episode was still outstanding. settle() checks the pending listener before"
					+ " running any turn, so the episode starts by reading the agent's first action as a"
					+ " target choice; the hero never moves and every recording made in that state"
					+ " diverges at step 0. Measured before the fix: 4 of 10 recorded runs diverged when"
					+ " verified in sequence, and one verified clean alone and diverged second." );
			return;
		}

		System.out.println( "  an outstanding aim request does not carry into the next episode, which"
				+ " starts in WORLD" );
	}

	/** Every episode begins in WORLD, whatever the previous one was doing when it ended. */
	private static void checkEveryEpisodeStartsInWorld( SPDEnv env, EnvConfig config ){
		//four episodes back to back, with real play between them, which is what a worker does
		for (int i = 0; i < 4; i++){
			env.reset( SEEDS[ i % SEEDS.length ], HeroClass.WARRIOR );

			if (env.mode() != EnvMode.WORLD){
				fail( "episode " + i + " of four consecutive resets started in " + env.mode()
						+ " instead of WORLD. A worker runs thousands of episodes in one process, so"
						+ " whatever the previous episode left behind is reaching this one." );
				return;
			}

			play( env, 60 );
		}

		System.out.println( "  four consecutive episodes all started in WORLD" );
	}

	/**
	 * The same seed and the same policy must produce the same trajectory, whatever ran before.
	 *
	 * <p>This is the property the replay viewer depends on and the one every single-episode check was
	 * blind to. The seeds are rotated between passes so that "reproduces" cannot quietly mean
	 * "reproduces because it ran first".
	 */
	private static void checkTrajectoriesDoNotDependOnOrder( SPDEnv env, EnvConfig config ){
		for (String seed : SEEDS){
			String first = trace( env, config, seed );

			//churn with the other seeds, then trace the same one again
			for (String other : SEEDS){
				if (!other.equals( seed )) trace( env, config, other );
			}
			String second = trace( env, config, seed );

			if (!first.equals( second )){
				fail( "seed " + seed + " produced a different trajectory the second time in the same"
						+ " process. The environment is not a function of its reset arguments, so any"
						+ " recording made here is unreproducible and every locked-seed comparison built"
						+ " on it is meaningless." + firstDifference( first, second ) );
				return;
			}
		}

		System.out.println( "  " + SEEDS.length + " seeds reproduce identically after running the"
				+ " others in between (" + TRACE_STEPS + " steps each)" );
	}

	// --------------------------------------------------------------------------- tracing

	/** A seeded random policy, so the only thing that can differ between two traces is the environment. */
	private static void play( SPDEnv env, int steps ){
		Random rng = new Random( 4242L );
		Action[] all = Action.values();

		for (int i = 0; i < steps && env.running(); i++ ){
			Action a = env.mode() == EnvMode.WORLD ? all[ rng.nextInt( all.length ) ] : Action.CANCEL;
			env.step( a, rng.nextInt( 8 ) );
		}
	}

	/**
	 * The trajectory of a fixed policy, as one comparable string.
	 *
	 * <p>Every step's position and mode go in, not just the start, because a leak can leave the start
	 * identical and only diverge later - which is exactly what the two stale recordings did, at step
	 * 290 and 402 of runs that began correctly.
	 */
	private static String trace( SPDEnv env, EnvConfig config, String seed ){
		env.config().turnLimitPerFloor = config.turnLimitPerFloor;
		env.reset( seed, HeroClass.WARRIOR );

		StringBuilder sb = new StringBuilder();
		sb.append( "start mode=" ).append( env.mode() ).append( " pos=" ).append( env.heroPosition() );

		Random rng = new Random( 4242L );
		Action[] all = Action.values();

		for (int i = 0; i < TRACE_STEPS && env.running(); i++ ){
			Action a = env.mode() == EnvMode.WORLD ? all[ rng.nextInt( all.length ) ] : Action.CANCEL;
			int slot = rng.nextInt( 8 );
			env.step( a, slot );

			sb.append( '\n' ).append( i ).append( ' ' ).append( a ).append( '/' ).append( slot )
					.append( " pos=" ).append( env.heroPosition() )
					.append( " mode=" ).append( env.mode() )
					.append( " depth=" ).append( env.depth() );
		}

		sb.append( "\nend=" ).append( env.endReason() )
				.append( " turns=" ).append( env.turnsTotal() )
				.append( " score=" ).append( env.ledger().total() );

		return sb.toString();
	}

	private static String firstDifference( String a, String b ){
		String[] x = a.split( "\n" ), y = b.split( "\n" );
		for (int i = 0; i < Math.max( x.length, y.length ); i++ ){
			String p = i < x.length ? x[ i ] : "<none>";
			String q = i < y.length ? y[ i ] : "<none>";
			if (!p.equals( q )) return " First difference: \"" + p + "\" vs \"" + q + "\".";
		}
		return "";
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
