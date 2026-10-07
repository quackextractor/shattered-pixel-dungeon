package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fails if a recording made by the policy-driven collector will not reproduce.
 *
 * <pre>gradle :superintelligence:collectcheck</pre>
 *
 * A replay is verified by comparing where the hero ended up after each recorded step against where it
 * ends up again on playback. That check is only as good as the recording, and the recording can be
 * wrong in a way nothing else notices: an off-by-one-step position produces a file that still plays,
 * still looks like a plausible dungeon run, and diverges at step 0.
 *
 * <b>This exists because that happened.</b> {@code EpisodeCollector} read {@code heroPosition}
 * before stepping rather than after, so every recording the trainer wrote was wrong and every one
 * failed to verify — caught only by hand, on a recording that had been sitting in the catalog looking
 * fine. `ScriptedPolicy`'s path reads it after the step and always has, which is why
 * {@code replay-viewer.bat --record} was unaffected and the trainer's output was not. The difference
 * between the two call sites was the entire bug.
 *
 * So the assertion is end to end and behavioural: collect an episode with the real collector, record
 * it exactly as the worker does, then re-execute the recording and require an exact match. Not a unit
 * test of the recorder, because the recorder was never wrong on its own — the caller passed it the
 * wrong moment.
 */
public class CollectCheck {

	private static final String SEED = "COLLECT-CHECK";

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-collectcheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 120;

		int checks = 0;

		checks++;
		checkRecordedPositionsArePostStep( config );

		checks++;
		checkTwoCollectorsWithTheSameSeedAgree( config );

		checks++;
		checkScriptedPathStillAgrees( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     collection: " + checks + " checks passed" );
		} else {
			System.out.println( "[ERROR]  collection: " + failures.size() + " of "
					+ checks + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * A recording made through the collector must verify exactly.
	 *
	 * Recorded the way `Worker.runEpisode` does it, including the listener, because the listener is
	 * where the ordering bug lived — the collector's own scalars were always right.
	 */
	private static void checkRecordedPositionsArePostStep( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, new Random( 4242L ) );

		EpisodeCollector collector = new EpisodeCollector( config, network,
				new Random( 7L ), new Random( 99L ), 0.99f, 0.95f, 0.05f );

		Trace trace = record( env, collector );

		if (trace.steps.isEmpty()){
			fail( "the collector recorded no steps, so nothing was checked" );
			return;
		}

		//the position has to have actually moved at some point, or "post-step" and "pre-step" would
		//be indistinguishable and this check would pass for the wrong reason
		boolean moved = false;
		for (int i = 1; i < trace.steps.size(); i++){
			if (trace.steps.get( i ).heroPos != trace.steps.get( i - 1 ).heroPos ){
				moved = true;
				break;
			}
		}
		if (!moved){
			fail( "the hero never moved across " + trace.steps.size()
					+ " steps, so a pre-step position could not be distinguished from a post-step one" );
			return;
		}

		replay( env, config, trace );
	}

	/**
	 * Two collectors, same seed and same policy randomness, must produce the same trace.
	 *
	 * Cheap insurance that nothing in the collection path is order-dependent — the rolling tail and
	 * the sampling draw both mutate state that a bug could let leak into the decisions.
	 */
	private static void checkTwoCollectorsWithTheSameSeedAgree( EnvConfig config ){
		Trace a = traceFrom( config, 4242L, 7L, 99L );
		Trace b = traceFrom( config, 4242L, 7L, 99L );

		if (a.steps.size() != b.steps.size()){
			fail( "two identical collections produced " + a.steps.size() + " and "
					+ b.steps.size() + " steps" );
			return;
		}

		for (int i = 0; i < a.steps.size(); i++){
			if (!a.steps.get( i ).equals( b.steps.get( i ))){
				fail( "collection is not reproducible at step " + i + ": "
						+ a.steps.get( i ) + " vs " + b.steps.get( i ) );
				return;
			}
		}
	}

	/**
	 * The scripted path and the collector must agree on where the hero ends up for the same seed.
	 *
	 * Different policies, so the action sequences will not match — what has to match is the
	 * <em>invariant</em>: for the scripted run, replaying its own recording must verify. That is the
	 * property the collector's bug would have broken had it been on the scripted side.
	 */
	private static void checkScriptedPathStillAgrees( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), SEED.hashCode() );

		Trace trace = new Trace();
		env.reset( SEED, HeroClass.WARRIOR );

		int[] slot = new int[ 1 ];
		while (env.running()){
			EnvMode mode = env.mode();
			Action action = policy.choose( env, slot );
			float reward = (float) env.step( action, slot[ 0 ] );
			trace.steps.add( new Step( action.name(), slot[ 0 ], mode.name(),
					env.heroPosition(), reward ));
		}

		replay( env, config, trace );
	}

	// --------------------------------------------------------------------------- helpers

	/** Collects an episode and records every step, exactly as `Worker.runEpisode` does. */
	private static Trace record( SPDEnv env, EpisodeCollector collector ){
		Trace trace = new Trace();

		collector.listener( ( mode, action, secondary, heroPosition, reward ) ->
				trace.steps.add( new Step( action.name(), secondary, mode.name(),
						heroPosition, reward )) );

		collector.run( env, SEED, HeroClass.WARRIOR );
		collector.listener( null );

		return trace;
	}

	private static Trace traceFrom( EnvConfig config, long netSeed, long policySeed, long sampleSeed ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, new Random( netSeed ) );
		EpisodeCollector collector = new EpisodeCollector( config, network,
				new Random( policySeed ), new Random( sampleSeed ), 0.99f, 0.95f, 0.05f );
		return record( env, collector );
	}

	/**
	 * Re-executes a trace and reports the first position that disagrees.
	 *
	 * This is the same comparison `ReplayIO.verify` makes, done inline so the check needs no temp
	 * file. It compares after every step, and a mismatch names the step — a replay that diverges at
	 * step 0 is an ordering bug, and one that diverges hundreds of steps in is a different kind.
	 */
	private static void replay( SPDEnv env, EnvConfig config, Trace trace ){
		env.reset( SEED, HeroClass.WARRIOR );

		for (int i = 0; i < trace.steps.size(); i++){
			if (!env.running()) break;

			Step recorded = trace.steps.get( i );
			env.step( Action.valueOf( recorded.action ), recorded.slot );

			if (env.heroPosition() != recorded.heroPos){
				fail( "the recording diverges at step " + i + " of " + trace.steps.size()
						+ ": expected the hero at " + recorded.heroPos + ", it is at "
						+ env.heroPosition()
						+ ". A position recorded before the step rather than after looks exactly like "
						+ "this, and still plays back as a plausible run." );
				return;
			}
		}
	}

	private static class Trace {
		final List< Step > steps = new ArrayList<>();
	}

	private static class Step {
		final String action;
		final int slot;
		final String mode;
		final int heroPos;
		final float reward;

		Step( String action, int slot, String mode, int heroPos, float reward ){
			this.action = action;
			this.slot = slot;
			this.mode = mode;
			this.heroPos = heroPos;
			this.reward = reward;
		}

		@Override
		public boolean equals( Object other ){
			if (!(other instanceof Step )) return false;
			Step o = ( Step ) other;
			return action.equals( o.action ) && slot == o.slot && mode.equals( o.mode )
					&& heroPos == o.heroPos && reward == o.reward;
		}

		@Override
		public int hashCode(){
			return (action.hashCode() * 31 + slot) * 31 + mode.hashCode();
		}

		@Override
		public String toString(){
			return action + " " + slot + " " + mode + " @" + heroPos;
		}
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
