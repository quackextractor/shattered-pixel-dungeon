package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;

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

	private static final int CHECKS = 5;

	private static final List< String > failures = new ArrayList<>();

	/** Long enough to reach the item-and-inventory dance at step ~290, where the stale recordings broke. */
	private static final int TRACE_STEPS = 320;

	private static final String[] SEEDS = {
		"RESETCHECK-A", "RESETCHECK-B", "RESETCHECK-C", "RESETCHECK-D"
	};

	/**
	 * A seed measured to kill a ROGUE at turn 56, so the remains case has something to inherit.
	 *
	 * <p>Named rather than searched for at run time because the property it needs - that the hero dies -
	 * is a fact about the world, and discovering it by running would make the check's cost and its result
	 * depend on how long one is willing to wait. A survivor leaves no remains, and the case then passes
	 * without testing anything.
	 */
	private static final String DYING_SEED = "PARITY-ALPHA";

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-resetcheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 2000;
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );

		checkResetClearsAnArmedAim( env, config );
		checkResetClearsAnOpenDialog( env );
		checkARunDoesNotInheritRemains();
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

	/**
	 * A dialog the previous episode left open must not decide the next episode's mode either.
	 *
	 * <p>The same shape of fault as the armed aim, on the other pending-request static: {@code settle}
	 * tests {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge#open()}
	 * before it runs a turn, so a leftover dialog puts the new episode straight into MENU and the
	 * recorded position mismatches at step 0.
	 *
	 * <p>Opened through {@code GameScene.show}, which is the real path - a shop, the blacksmith and the
	 * resurrection prompt all reach it - and is what makes it park the window in the static headlessly.
	 * A {@code WndOptions} rather than any other window because {@code WindowBridge.open} is narrower
	 * than "a window is showing": informational windows have no options and are deliberately not treated
	 * as open, so asserting on one of those would pass for a reason that has nothing to do with the
	 * reset.
	 *
	 * <p>Reached by play rather than by construction alone: {@code settle} only reports MENU if it sees
	 * the dialog, so the case plays a turn first and checks that the turn, not the reset, is what
	 * surfaced it. That is the difference between "the static is cleared" and "the mode was reset" -
	 * only one of them is the property.
	 */
	private static void checkResetClearsAnOpenDialog( SPDEnv env ){
		env.reset( SEEDS[ 1 ], HeroClass.WARRIOR );

		GameScene.show( new WndOptions( "RESETCHECK", "dialog left open by the previous episode",
				"Yes", "No" ) );

		//guard: without it the case passes for a reason unrelated to the reset
		if (!WindowBridge.open()){
			fail( "GameScene.show did not park an answerable window, so the case cannot test whether"
					+ " reset clears one." );
			return;
		}

		env.reset( SEEDS[ 2 ], HeroClass.WARRIOR );

		//the static itself, checked as the reference rather than through WindowBridge.open().
		//
		//open() answers a narrower question - "is the parked window an answerable one" - and anything
		//that replaced the dialog during the reset would make it false while a window was still parked.
		//Reset asserts the field is empty, because that is the property the reset actually promises and
		//the only one no other code path can accidentally satisfy.
		if (GameScene.headlessWindow() != null){
			fail( "a dialog left open by the previous episode was still parked after reset ("
					+ GameScene.headlessWindow().getClass().getSimpleName() + "). settle() tests for it"
					+ " before running any turn, so the episode starts by reading the agent's first"
					+ " action as a menu choice." );
			return;
		}

		play( env, 40 );

		if (env.mode() != EnvMode.WORLD){
			fail( "an episode began in " + env.mode() + " instead of WORLD while a dialog from the"
					+ " previous episode was still outstanding, and playing 40 turns did not resolve it."
					+ " A leftover dialog is a step-0 divergence in every recording made after it." );
			return;
		}

		System.out.println( "  an outstanding dialog does not carry into the next episode, which starts"
				+ " in WORLD and stays there" );
	}

	/**
	 * A dead hero's remains must not appear in the next run.
	 *
	 * <p>Remains are process-spanning by design: a hero who dies leaves their belongings and one
	 * class-specific remnant behind, and the next run picks them up where they fell. For a player returning
	 * to a dungeon that is the feature. For an environment playing thousands of independent runs in one
	 * process it makes the world a function of process history as well as of the seed, and it is invisible
	 * until a recording is replayed: the replay drops a heap the recording had, or vice versa.
	 *
	 * <p>Measured by {@code diag.ParityCheck}, which found it: 2 of 32 recorded runs over 8 seeds and 4
	 * hero classes diverged, both on inventory, both on a remnant - one carrying a HUNTRESS's fragment
	 * where the replay had a ROGUE's scrap and one the other way round. Every other gate missed it,
	 * because each either plays one episode per process or never lets the hero die.
	 *
	 * <p><b>Ordering is what this case really tests.</b> Remains are read by {@code RegularLevel.createItems},
	 * which runs inside {@code startRun}. Clearing them after the run has begun clears them one floor too
	 * late, and the floor is already carrying the previous hero's heap - which is exactly how the first
	 * attempt at this fix failed while every other reset case passed. So the first episode is played to a
	 * real death, not simulated, and the assertion is on the new floor's contents rather than on any
	 * internal state.
	 */
	private static void checkARunDoesNotInheritRemains(){
		EnvConfig config = new EnvConfig();

		//A ROGUE on a seed measured to die at turn 56 rather than one of the seeds above, which were
		//chosen for trajectory diversity and mostly survive their floor. Which seeds die is a property of
		//the world, so this names one that was observed to, and the death itself is asserted below.
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode.Result first =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode.record(
						config, DYING_SEED, HeroClass.ROGUE, 0, 1 );

		//The positive control. Without it the case passes for a reason that has nothing to do with the
		//reset: a hero who survives never calls Bones.leave, so there is no remains to inherit and the
		//assertion below is true of a run that was never at risk. That is not hypothetical - it is what
		//this case did for its first few revisions.
		if (first.env.endReason() != com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward
				.RewardModel.TerminateReason.DEATH){
			fail( "the first episode ended " + first.env.endReason() + " after "
					+ first.env.turnsTotal() + " turns rather than dying, so it left no remains and this"
					+ " case cannot test whether a run inherits them. Pick a seed that dies." );
			return;
		}

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode.record(
				config, SEEDS[ 3 ], HeroClass.WARRIOR, 0, 2 );

		List<String> inherited = new ArrayList<>();
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			Heap heap = Dungeon.level.heaps.get( cell );
			if (heap == null || heap.type != Heap.Type.REMAINS) continue;
			for (com.shatteredpixel.shatteredpixeldungeon.items.Item item : heap.items){
				if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.remains.RemainsItem){
					inherited.add( item.getClass().getSimpleName() + " at " + cell );
				}
			}
		}

		if (!inherited.isEmpty()){
			fail( "the second run's first floor carries a REMAINS heap belonging to the ROGUE that died in"
					+ " the first run: " + String.join( ", ", inherited ) + ". Remains are process-spanning"
					+ " game state, so a run in this process is not a function of its seed. Every recording"
					+ " made after a death describes a floor the run that replays it will never generate." );
			return;
		}

		System.out.println( "  a run following a hero's death inherits none of that hero's remains" );
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
