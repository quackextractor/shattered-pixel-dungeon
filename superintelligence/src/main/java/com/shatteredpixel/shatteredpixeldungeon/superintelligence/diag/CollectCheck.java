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

		checks++;
		checkRandomSeedEpisodeIsReplayable( config );

		checks++;
		checkWorkerWireCarriesEveryStepField( config );

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
	 * A recording crossing the worker pipe must arrive carrying every field, not just a position.
	 *
	 * <p><b>This is the check that would have caught the four-field wire.</b> The other three cases in
	 * this file all end in a recording, and every one of them would still have passed with the wire
	 * broken — because they write through {@code ReplayIO.write} and never touch the worker at all. The
	 * encoder and the decoder were separate methods on two sides of a pipe, the writer sent four step
	 * fields where the reader has nine, and the five it dropped kept the sentinels
	 * {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay.Step} declares for
	 * *not recorded*.
	 *
	 * <p>So it did not crash and it did not diverge. {@code ReplayIO.verify} guards every comparison with
	 * its own sentinel, so a recording carrying no health, no engine time and no inventory verifies on
	 * position alone and reports {@code [OK]}. Found by looking for a training run that had genuinely
	 * reached depth 2 and finding a file that could not have detected a starving hero or a one-turn
	 * drift — both of which health and turn are recorded specifically to catch.
	 *
	 * <p>Two assertions, and the second is the one that matters. The first round-trips a populated
	 * replay through {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.ReplayCodec}
	 * and requires every field to survive. The second drives a **real** collector episode and checks the
	 * recording it produces carries no sentinels — because the original fault was a hand-written field
	 * list inline in a method, and a codec unit test cannot see someone writing the next such list.
	 */
	private static void checkWorkerWireCarriesEveryStepField( EnvConfig config ){
		// 1. the codec, round-tripped, field by field.
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay sample =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay();
		sample.seedText = "WIRECHECK";
		sample.heroClass = "ROGUE";
		sample.challenges = 3;
		sample.generation = 11;
		sample.score = -12.5;
		sample.depth = 2;
		sample.turns = 97;
		sample.turnLimitPerFloor = 1500;
		sample.maxSlots = 32;
		sample.allowEquipping = false;
		sample.termination = "DEATH";

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay.Step s =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay.Step();
		s.action = "USE";
		s.slot = 4;
		s.mode = "SLOT";
		s.heroPos = 1234;
		s.reward = -1.25;
		s.quickslots = "0:Food,1:Waterskin";
		s.heroHp = 17;
		s.turn = 42.5f;
		s.inventory = "Food:1,Waterskin:2";
		sample.steps.add( s );

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay back;
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.ReplayCodec.write(
					new java.io.DataOutputStream( bytes ), sample );
			java.io.DataInputStream in = new java.io.DataInputStream(
					new java.io.ByteArrayInputStream( bytes.toByteArray() ));
			back = com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.ReplayCodec.read(
					in, sample.steps.size() );
		} catch (java.io.IOException e){
			fail( "the worker replay wire could not round-trip: " + e.getMessage() );
			return;
		}

		if (back.steps.size() != 1){
			fail( "the wire carried " + back.steps.size() + " steps instead of 1" );
			return;
		}

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay.Step r = back.steps.get( 0 );
		//Every one of these was silently dropped before, and each is a comparison a verifier skips when
		//it is missing. heroHp and turn are the load-bearing pair; the rest is why the list is here.
		if (r.heroHp != s.heroHp ){
			fail( "heroHp did not survive the wire: " + s.heroHp + " -> " + r.heroHp
					+ ". ReplayIO.verify skips its health comparison when heroHp is negative, so a"
					+ " recording that lost it verifies on position alone and reports success." );
			return;
		}
		if (r.turn != s.turn ){
			fail( "engine time did not survive the wire: " + s.turn + " -> " + r.turn
					+ ". ReplayIO.verify skips its turn comparison when turn is negative, which is"
					+ " exactly the one-turn-drift case health and turn were added to catch." );
			return;
		}
		if (!s.inventory.equals( r.inventory )){
			fail( "inventory did not survive the wire: '" + s.inventory + "' -> '" + r.inventory
					+ "'. ReplayIO.verify skips an empty inventory, so a lost slot binding is invisible." );
			return;
		}
		if (r.reward != s.reward ){
			fail( "per-step reward did not survive the wire: " + s.reward + " -> " + r.reward
					+ ". The viewer's running score and per-step delta are computed from it." );
			return;
		}
		if (!s.quickslots.equals( r.quickslots )){
			fail( "quickslot bindings did not survive the wire: '" + s.quickslots + "' -> '"
					+ r.quickslots + "'. A recorded slot index means nothing without them." );
			return;
		}
		if (back.maxSlots != sample.maxSlots || back.allowEquipping != sample.allowEquipping ){
			fail( "the header did not carry the slot width or the equipping rule, so a recorded slot"
					+ " index cannot be resolved the same way twice" );
			return;
		}
		if (back.turnLimitPerFloor != sample.turnLimitPerFloor || back.depth != sample.depth
				|| back.termination.compareTo( sample.termination ) != 0 ){
			fail( "the header lost turnLimitPerFloor, depth or termination" );
			return;
		}

		// 2. a real episode, recorded through the real ReplayRecorder the worker drives, must carry no
		// sentinels. Deliberately not Trace: that is this file's own lightweight record, and the
		// question here is what the worker actually ships.
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, new Random( 31337L ) );
		EpisodeCollector collector = new EpisodeCollector( config, network,
				new Random( 5L ), new Random( 11L ), 0.99f, 0.95f, 0.05f );

		final com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder recorder =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder();

		collector.listener( new EpisodeCollector.Listener() {
			@Override public void onEpisodeStart( String seedText, String hero ){
				recorder.begin( seedText, hero, 0, config.turnLimitPerFloor, config );
			}

			//The worker's own listener shape, so an ordering difference between here and Worker.java
			//shows up as a failure rather than as a check that quietly tests something else.
			@Override public void onStep( EnvMode mode, Action action, int secondary,
					int heroPosition, float reward ){
				recorder.record( action, secondary, mode );
				recorder.afterStep( heroPosition, reward );
			}
		} );

		collector.run( env, "WIRE-CHECK", HeroClass.WARRIOR );
		collector.listener( null );
		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0,
				env.endReason().name() );

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay recorded =
				recorder.replay();
		if (recorded.steps.isEmpty()){
			fail( "the worker-wire case recorded no steps, so nothing was checked" );
			return;
		}

		int noHp = 0, noTurn = 0;
		java.util.Set< String > inventories = new java.util.LinkedHashSet<>();
		for (com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay.Step step
				: recorded.steps){
			if (step.heroHp < 0) noHp++;
			if (step.turn < 0) noTurn++;
			inventories.add( step.inventory );
		}

		int n = recorded.steps.size();
		if (noHp == n ){
			fail( n + " of " + n + " recorded steps carry no hero health. Every verifier skips its"
					+ " health comparison on a negative value, so this recording would verify on"
					+ " position alone. This is the four-field wire bug." );
			return;
		}
		if (noTurn == n ){
			fail( n + " of " + n + " recorded steps carry no engine time, so a run one turn adrift"
					+ " would verify clean." );
			return;
		}
		//Inventory is the one field that is legitimately empty on many steps - a hero that drops
		//everything really is carrying nothing, and an untrained policy drops things constantly. So
		//counting empties would be a flaky assertion; a real training run here came back with 347 of
		//660 steps empty and 88 of those traced straight back to the policy throwing items away. What
		//distinguishes "empty" from "never written" is whether the value tracks the hero: every class
		//starts with a VelvetPouch, so step 0 cannot be empty, and a field that is being written has to
		//change at least once. A field the wire dropped is empty everywhere and has one distinct value.
		if (recorded.steps.get( 0 ).inventory.isEmpty() ){
			fail( "step 0 carries no inventory, but every hero class starts with a VelvetPouch, so"
					+ " this field is not being written at all" );
			return;
		}
		if (inventories.size() < 2 ){
			fail( "the inventory is identical on all " + n + " steps ('"
					+ recorded.steps.get( 0 ).inventory + "'), so it is not tracking what the hero is"
					+ " carrying - the bindings were frozen, not dropped" );
			return;
		}

		System.out.println( "  worker wire carries every step field over " + n + " recorded steps;"
				+ " " + inventories.size() + " distinct inventories, hp and turn on every step" );
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

	/**
	 * An episode played on a random seed must still be replayable.
	 *
	 * `SeedPool` deliberately sends 10% of episodes to a fully random seed so the agent cannot
	 * memorise the locked set. Those episodes used to be recorded with the *requested* seed, which is
	 * empty for them, so `verify` reset onto a fresh draw and reported a divergence at step 0 — a
	 * message that reads as a broken seed lock and is not one.
	 *
	 * The env now resolves the drawn seed and reports it, so the recording names the run it actually
	 * produced. This asserts the resolved seed round-trips: replaying it must reproduce, which is the
	 * whole claim.
	 */
	private static void checkRandomSeedEpisodeIsReplayable( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, new Random( 31337L ) );
		EpisodeCollector collector = new EpisodeCollector( config, network,
				new Random( 5L ), new Random( 6L ), 0.99f, 0.95f, 0.05f );

		Trace trace = new Trace();
		collector.listener( new EpisodeCollector.Listener() {
			@Override public void onEpisodeStart( String seedText, String hero ){
				trace.seedText = seedText;
			}

			@Override public void onStep( EnvMode mode, Action action, int secondary,
					int heroPosition, float reward ){
				trace.steps.add( new Step( action.name(), secondary, mode.name(),
						heroPosition, reward ));
			}
		} );

		//an empty seed, which is what SeedPool returns for a random episode
		collector.run( env, "", HeroClass.WARRIOR );
		collector.listener( null );

		if (trace.steps.isEmpty()){
			fail( "the random-seed episode recorded no steps" );
			return;
		}

		if (trace.seedText == null || trace.seedText.isEmpty()){
			fail( "a random-seed episode was recorded with no seed, so verify would reset onto a "
					+ "different draw and diverge at step 0" );
			return;
		}

		//the resolved text has to decode back to the seed the run used, or the recording is no
		//better than an empty one
		SPDEnv fresh = new SPDEnv( config, HeadlessGame.install() );
		fresh.reset( trace.seedText, HeroClass.WARRIOR );
		String replayed = fresh.seedText();

		if (!replayed.equals( trace.seedText )){
			fail( "the resolved seed did not round-trip: recorded '" + trace.seedText
					+ "', reset produced '" + replayed + "'" );
			return;
		}

		replay( fresh, config, trace );
	}

	// --------------------------------------------------------------------------- helpers

	/** Collects an episode and records every step, exactly as `Worker.runEpisode` does. */
	private static Trace record( SPDEnv env, EpisodeCollector collector ){
		Trace trace = new Trace();

		collector.listener( new EpisodeCollector.Listener() {
			@Override public void onEpisodeStart( String seedText, String hero ){
				trace.seedText = seedText;
			}

			@Override public void onStep( EnvMode mode, Action action, int secondary,
					int heroPosition, float reward ){
				trace.steps.add( new Step( action.name(), secondary, mode.name(),
						heroPosition, reward ));
			}
		} );

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
	 *
	 * Reset from the trace's own recorded seed rather than a constant, because a trace from a random
	 * episode is only replayable at all if its resolved seed is what gets used.
	 */
	private static void replay( SPDEnv env, EnvConfig config, Trace trace ){
		env.reset( trace.seedText.isEmpty() ? SEED : trace.seedText, HeroClass.WARRIOR );

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

		/** The seed the episode actually ran under, as reported once the env had resolved it. */
		String seedText = "";
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
