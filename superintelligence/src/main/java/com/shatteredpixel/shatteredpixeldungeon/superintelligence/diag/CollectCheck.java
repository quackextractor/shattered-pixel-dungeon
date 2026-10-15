package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
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
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.ReplayKeeper;
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

		checks++;
		checkDropRecordsTheBindingsItsSlotIndexWasChosenAgainst( config );

		checks++;
		checkTheCollectorAnnouncesADecisionBeforeItIsApplied( config );

		checks++;
		checkTheKeeperKeepsTheEpisodesWorthWatching( config );

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
	 * A run must keep the episodes worth watching, and must not be deciding that by luck.
	 *
	 * <p>Two faults, one shape. The trainer recorded only every third episode, so "best per seed" meant
	 * best of the one in three that happened to be sent - and a 15-generation run whose single floor-2
	 * episode scored -1,276 against a run-best of -2,778 simply did not have that episode in the
	 * catalog, because it was one of the two thirds nobody sent. The worker builds the whole replay
	 * anyway and only skips the transmission, so the sample paid the entire recording cost and saved
	 * the bytes.
	 *
	 * <p>Asserted here on the keeper rather than on a training run, because a run that reaches the
	 * interesting episode takes 15 generations and the fault is in the selection, not in the play.
	 */
	private static void checkTheKeeperKeepsTheEpisodesWorthWatching( EnvConfig config ){
		ReplayKeeper keeper = new ReplayKeeper();

		Replay ordinary = replay( "KEEPER-A", "WARRIOR", 1, -3_100.0 );
		Replay floorTwo = replay( "KEEPER-A", "ROGUE", 2, -1_276.0 );
		Replay alsoOrdinary = replay( "KEEPER-B", "MAGE", 1, -3_400.0 );
		Replay dreadful = replay( "KEEPER-B", "CLERIC", 1, -3_700.0 );

		keeper.consider( ordinary );
		keeper.consider( floorTwo );
		keeper.consider( alsoOrdinary );
		keeper.consider( dreadful );
		keeper.consider( null );

		List< Replay > kept = keeper.keep();

		if (keeper.considered() != 4){
			fail( "the keeper counted " + keeper.considered() + " episodes, expected 4; a null replay is"
					+ " not an episode and must not be counted as one." );
			return;
		}

		if (kept.size() != 3){
			fail( "four episodes over two seeds kept " + kept.size() + ", expected 3 - best per seed is"
					+ " 2 and the run's worst is one more. Kept: " + seedsOf( kept ) );
			return;
		}

		if (!kept.contains( floorTwo )){
			fail( "the episode that reached floor 2 and scored -1,276 against a run-best of -2,778 was"
					+ " not kept, which is the whole fault: the catalog's job is the run worth watching"
					+ " and it discarded it. Kept: " + seedsOf( kept ) );
			return;
		}

		if (!kept.contains( dreadful )){
			fail( "the run's worst episode was not kept, so the catalog carries one end of the range and"
					+ " not the other. Kept: " + seedsOf( kept ) );
			return;
		}

		if (kept.contains( ordinary )){
			fail( "a worse episode for the same seed was kept alongside a better one, so the per-seed rule"
					+ " is not being applied. Kept: " + seedsOf( kept ) );
			return;
		}

		ReplayKeeper onlyOne = new ReplayKeeper();
		onlyOne.consider( dreadful );
		onlyOne.consider( alsoOrdinary );
		if (onlyOne.keep().size() != 2){
			fail( "a run where the worst episode is also a seed's best kept "
					+ onlyOne.keep().size() + " files, so the worst is written twice." );
		}
	}

	private static Replay replay( String seed, String hero, int depth, double score ){
		Replay replay = new Replay();
		replay.seedText = seed;
		replay.heroClass = hero;
		replay.depth = depth;
		replay.score = score;
		return replay;
	}

	private static List< String > seedsOf( List< Replay > replays ){
		List< String > names = new ArrayList<>();
		for (Replay replay : replays) names.add( replay.seedText + "@" + (long) replay.score );
		return names;
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

		sample.rewardProfile = com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward
				.RewardProfile.DEPTH_ONLY;

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
		if (back.rewardProfile != sample.rewardProfile ){
			fail( "the header lost the reward profile: " + sample.rewardProfile + " -> "
					+ back.rewardProfile + ". A recording written under a profile and read back as the"
					+ " default reports a score and a reward column that never matched the run, and says"
					+ " nothing about the trajectory." );
			return;
		}

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

			@Override public void onDecision( EnvMode mode, Action action, int secondary ){
				recorder.record( action, secondary, mode );
			}

			@Override public void onStep( int heroPosition, float reward ){
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
			private EnvMode mode;
			private String action;
			private int secondary;

			@Override public void onEpisodeStart( String seedText, String hero ){
				trace.seedText = seedText;
			}

			@Override public void onDecision( EnvMode chosenMode, Action chosenAction, int chosen ){
				mode = chosenMode;
				action = chosenAction.name();
				secondary = chosen;
			}

			@Override public void onStep( int heroPosition, float reward ){
				trace.steps.add( new Step( action, secondary, mode.name(),
						heroPosition, reward ));
			}
		} );

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

	/**
	 * A {@code DROP} must be recorded against the quickslot bindings its slot index was chosen with.
	 *
	 * <p><b>This is the case the corpus could not contain.</b> Measured on the 17 committed recordings:
	 * 310 SLOT-mode steps, and the entire SLOT vocabulary is {@code USE/SLOT}. {@code DROP} appears zero
	 * times, because {@code ScriptedPolicy} never chooses it. And {@code DROP} is the one action that
	 * unbinds its own item, so it is the step where the instant a recording pins the bindings stops
	 * being a detail.
	 *
	 * <p>The fault was that {@code Worker}'s listener called {@code ReplayRecorder.record} from
	 * {@code onStep}, which is after the step by definition, so a trainer recording carried the bindings
	 * its own {@code DROP} produced rather than the ones the index was chosen against. The dropped item
	 * left the backpack, stopped being reachable, and its binding vanished from the capture. On
	 * {@code MSU-OTL-OAT} that is step 17: {@code DROP/1} in {@code SLOT} dropped a ThrowingSpike in the
	 * trainer and a Food in the viewer, because the ThrowingSpike's binding to quickslot 1 was already
	 * gone from the file before the viewer read it.
	 *
	 * <p>Driven deliberately rather than waited for, for the reason
	 * {@code checkKillingIsWorthDoing} gives: an untrained policy may never drop anything on a given
	 * seed, and a case that then passes having measured nothing is worse than no case. The positive
	 * control is the capture taken <em>after</em> the step - if it still named the dropped item, the two
	 * instants would be indistinguishable and this could not fail.
	 */
	private static void checkDropRecordsTheBindingsItsSlotIndexWasChosenAgainst( EnvConfig config ){
		String where = "a DROP records the bindings its slot index was chosen against";

		Recorded run = recordADrop( config, false );
		if ( run == null ) return;
		if ( run.dropAt < 0 ){
			fail( where, "the fixture did not reach a DROP in SLOT, so the case measured nothing" );
			return;
		}

		String recorded = run.replay.steps.get( run.dropAt ).quickslots;
		if ( !recorded.contains( run.droppedItem + ":" + run.droppedSlot )){
			fail( where, "step " + run.dropAt + " dropped " + run.droppedItem + " from slot "
					+ run.droppedSlot + " but records bindings [" + recorded + "], which do not name it."
					+ " A binding captured after the step is gone from the file the moment the item"
					+ " leaves the backpack, and playback then resolves the same index to something else." );
			return;
		}

		if ( run.captureAfter.equals( recorded )){
			fail( where, "the bindings captured after the step are identical to the ones captured before"
					+ " it (" + recorded + "), so this case cannot tell the two instants apart and would"
					+ " pass with the recording bug present" );
			return;
		}

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.Verification result =
				verifyRecording( config, run.replay );
		if ( !result.passed() ){
			fail( where, "a recording whose DROP was captured before the step did not verify: "
					+ ( result.diverged ? "diverged at step " + result.divergedAt + " - "
						+ result.divergence : result.unwatchable ) );
			return;
		}

		Recorded late = recordADrop( config, true );
		if ( late == null || late.dropAt < 0 ){
			fail( where, "the late-capture fixture did not reach a DROP, so the converse could not be run" );
			return;
		}

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.Verification caught =
				verifyRecording( config, late.replay );
		if ( caught.passed() ){
			fail( where, "the same run with its bindings captured after the step still verified:"
					+ " replay reproduced in " + caught.stepsVerified + " steps. Verification resolves a"
					+ " slot index against whatever bindings the process happens to hold rather than the"
					+ " recorded ones, so a recording that cannot be watched reports itself perfect." );
			return;
		}

		System.out.println( "  a DROP in SLOT records the bindings its index was chosen against;"
				+ " the same run captured late is caught at step " + caught.divergedAt );
	}

	/** A short run whose only slot step is a {@code DROP}, recorded through {@link ReplayRecorder}. */
	private static class Recorded {
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay replay;
		String droppedItem = "";
		String captureAfter = "";
		int droppedSlot = -1;
		int dropAt = -1;
	}

	/**
	 * Records {@code USE} then {@code DROP} on a chosen slot, with the recorder called either side of
	 * the step so both recordings can be compared.
	 *
	 * @param captureAfterStep true to reproduce the bug: report the step to the recorder only once it
	 *                          has been applied, which is what {@code Worker}'s listener used to do
	 */
	private static Recorded recordADrop( EnvConfig config, boolean captureAfterStep ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( "DROP-IN-SLOT", HeroClass.DUELIST );

		Recorded out = new Recorded();
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder recorder =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder();
		recorder.begin( env.seedText(), HeroClass.DUELIST.name(), 0, config.turnLimitPerFloor, config );
		out.replay = recorder.replay();

		int slot = -1;
		env.mapper().refreshSlots();
		for (int i = 0; i < env.mapper().slots().size(); i++){
			com.shatteredpixel.shatteredpixeldungeon.items.Item item = env.mapper().slot( i );
			if (item == null) continue;
			int bound = com.shatteredpixel.shatteredpixeldungeon.Dungeon.quickslot.getSlot( item );
			if (bound >= 0 && !item.isEquipped( Dungeon.hero )){ slot = i; break; }
		}
		if ( slot < 0 ) return out;

		com.shatteredpixel.shatteredpixeldungeon.items.Item chosen = env.mapper().slot( slot );
		out.droppedItem = chosen.getClass().getSimpleName();
		out.droppedSlot = com.shatteredpixel.shatteredpixeldungeon.Dungeon.quickslot.getSlot( chosen );

		recorder.record( Action.USE, 0, EnvMode.WORLD );
		env.step( Action.USE, 0 );
		recorder.afterStep( env.heroPosition(), 0f );

		out.dropAt = recorder.replay().length();
		if ( captureAfterStep ){
			env.step( Action.DROP, slot );
			out.captureAfter = com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay
					.Quickslots.capture();
			recorder.record( Action.DROP, slot, EnvMode.SLOT );
			recorder.afterStep( env.heroPosition(), 0f );
		} else {
			recorder.record( Action.DROP, slot, EnvMode.SLOT );
			env.step( Action.DROP, slot );
			recorder.afterStep( env.heroPosition(), 0f );
			out.captureAfter = com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay
					.Quickslots.capture();
		}

		int[] nothing = new int[ 1 ];
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy policy =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy(
						env.mapper(), 3 );
		while (env.running() && recorder.replay().length() < 24){
			Action action = policy.choose( env, nothing );
			recorder.record( action, nothing[ 0 ], env.mode() );
			env.step( action, nothing[ 0 ] );
			recorder.afterStep( env.heroPosition(), 0f );
		}

		recorder.end( 0, env.deepestDepth(), env.turnsTotal(), 0, env.endReason().name() );
		return out;
	}

	/**
	 * The collector must hand the recorder a step's bindings before the step, not after it.
	 *
	 * <p>The case above drives the environment directly, so it covers {@link ReplayRecorder} and the
	 * order the two calls are made in - but not the wiring that made the call late.
	 * {@code EpisodeCollector.Listener} is that wiring, and it was the half that was wrong: the recorder
	 * was told from {@code onStep}, which the collector defines as after the step. So this drives a real
	 * collector episode and asserts the same property on whatever drop it happens to make.
	 *
	 * <p><b>Asserted on the clock rather than on a binding.</b> The two obvious alternatives both fail,
	 * and the way they fail is the useful part. Asking {@link Dungeon#quickslot} from inside the
	 * listener quietly depends on the wiring under test: called after the step, the item has already
	 * left the backpack and reads as unbound, so the mutant reports that it could not find a fixture
	 * rather than that it is wrong. Comparing a capture taken at the decision against one taken after
	 * the step fails the same way - both land on the same instant, so they agree. The engine clock does
	 * not: {@link Actor#now()} only moves inside {@code env.step}, so a decision announced before the
	 * step is announced on the clock the previous step left behind, and one announced after it is
	 * announced on the clock the step produced.
	 *
	 * <p>The seed is searched rather than hard-coded, and failing to find one is reported rather than
	 * skipped. A gate that cannot reach its own case is not a passing gate.
	 */
	private static void checkTheCollectorAnnouncesADecisionBeforeItIsApplied( EnvConfig config ){
		String where = "the collector announces a decision before the step is applied";

		EnvConfig shortRun = new EnvConfig();
		shortRun.turnLimitPerFloor = 120;

		for (int attempt = 0; attempt < ATTEMPTS; attempt++){
			Network network = new Network( shortRun, new Random( 1000L + attempt ));
			SPDEnv env = new SPDEnv( shortRun, HeadlessGame.install() );
			EpisodeCollector collector = new EpisodeCollector( shortRun, network,
					new Random( 7L + attempt ), new Random( 99L ), 0.99f, 0.95f, 0.05f );

			final com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder
					recorder = new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay
					.ReplayRecorder();

			final List< Float > clockAtDecision = new ArrayList<>();
			final List< Float > clockAtStep = new ArrayList<>();

			collector.listener( new EpisodeCollector.Listener() {
				@Override public void onEpisodeStart( String seedText, String hero ){
					recorder.begin( seedText, hero, 0, shortRun.turnLimitPerFloor, shortRun );
				}

				@Override public void onDecision( EnvMode mode, Action action, int secondary ){
					recorder.record( action, secondary, mode );
					clockAtDecision.add( Actor.now() );
				}

				@Override public void onStep( int heroPosition, float reward ){
					clockAtStep.add( Actor.now() );
					recorder.afterStep( heroPosition, reward );
				}
			} );

			collector.run( env, "COLLECT-DEC-" + attempt, HeroClass.DUELIST );
			collector.listener( null );
			recorder.end( 0, env.deepestDepth(), env.turnsTotal(), 0, env.endReason().name() );

			for (int i = 0; i < clockAtDecision.size() && i < clockAtStep.size(); i++){
				if (clockAtStep.get( i ) <= clockAtDecision.get( i )) continue;

				String bindings = recorder.replay().steps.get( i ).quickslots;
				System.out.println( "  the collector announces a decision on engine time "
						+ clockAtDecision.get( i ) + " which the step advances to "
						+ clockAtStep.get( i ) + " (attempt " + attempt + ", step " + i
						+ ", bindings [" + bindings + "])" );
				return;
			}
		}

		fail( where + ": across " + ATTEMPTS + " collector episodes no decision was ever announced on"
				+ " an earlier engine clock than the step that followed it, so this case reached nothing."
				+ " A gate that cannot build its fixture is a gate that cannot fail." );
	}

	/** How many collector episodes to try before reporting that the fixture could not be built. */
	private static final int ATTEMPTS = 12;

	/** Writes a recording out and reads it back, so verification sees the file rather than the object. */
	private static com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.Verification
	verifyRecording( EnvConfig config,
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay replay ){
		try {
			File file = File.createTempFile( "collectcheck-drop", ".replay" );
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.write( replay, file );
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay read =
					com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.read( file );
			file.delete();
			SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
			return com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO
					.verify( read, env );
		} catch (java.io.IOException e){
			failures.add( "a DROP fixture could not be written: " + e.getMessage() );
			return new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.Verification();
		}
	}

	/** Collects an episode and records every step, exactly as `Worker.runEpisode` does. */
	private static Trace record( SPDEnv env, EpisodeCollector collector ){
		Trace trace = new Trace();

		collector.listener( new EpisodeCollector.Listener() {
			private EnvMode mode;
			private String action;
			private int secondary;

			@Override public void onEpisodeStart( String seedText, String hero ){
				trace.seedText = seedText;
			}

			@Override public void onDecision( EnvMode chosenMode, Action chosenAction, int chosen ){
				mode = chosenMode;
				action = chosenAction.name();
				secondary = chosen;
			}

			@Override public void onStep( int heroPosition, float reward ){
				trace.steps.add( new Step( action, secondary, mode.name(),
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

	/** Prefixed, so a failure names the property it broke rather than only the symptom. */
	private static void fail( String where, String what ){
		failures.add( where + ": " + what );
	}
}
