package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Quickslots;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.ReplayKeeper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CollectTrace.SEED;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CollectTrace.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CollectTrace.replay;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CollectTrace.record;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CollectTrace.traceFrom;

public class CollectCheck {

	private static final int ATTEMPTS = 12;

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "collectcheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 120;

		checkRecordedPositionsArePostStep( config );
		checkTwoCollectorsWithTheSameSeedAgree( config );
		checkScriptedPathStillAgrees( config );
		checkRandomSeedEpisodeIsReplayable( config );
		CollectWireCheck.checkWorkerWireCarriesEveryStepField( config );
		CollectWireCheck.checkTheEpisodeFrameCarriesEveryReportedField();
		CollectWireCheck.checkTheBaselineIsInTheUnitsOfTheReturns( config );
		checkDropRecordsTheBindingsItsSlotIndexWasChosenAgainst( config );
		checkTheCollectorAnnouncesADecisionBeforeItIsApplied( config );
		checkTheKeeperKeepsTheEpisodesWorthWatching( config );
		checkAnEpisodeDependsOnItsJobNotOnWhatTheWorkerRanBefore( config );

		System.exit( GateReport.report( "collection", 11 ));
	}

	private static void checkAnEpisodeDependsOnItsJobNotOnWhatTheWorkerRanBefore( EnvConfig config ){
		EpisodeCollector collector = new EpisodeCollector( config, new Network( config, new Random( 9L )),
				new Random( 1 ), new Random( 2 ), 0.99f, 0.95f, 0.25f );
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		String first = fingerprint( collector.run( env, "JOB-A", HeroClass.WARRIOR, 11L ));
		collector.run( env, "JOB-B", HeroClass.MAGE, 22L );
		String again = fingerprint( collector.run( env, "JOB-A", HeroClass.WARRIOR, 11L ));
		if (!first.equals( again )){
			fail( "one job run before and after another on the same worker collected " + first + " then " + again
					+ "; a shared job queue would make a run depend on which worker took which job" );
			return;
		}
		System.out.println( "  a job collects the same episode whatever its worker ran before (" + first + ")" );
	}

	private static String fingerprint( com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord record ){
		StringBuilder sb = new StringBuilder().append( record.stepCount() ).append( " steps, " )
				.append( record.sampledCount() ).append( " sampled" );
		int hash = 0;
		for (int i = 0; i < record.sampledCount(); i++){
			hash = hash * 31 + record.sampledStepAt( i );
			hash = hash * 31 + record.sampledTransitions().get( i ).actionIndex;
		}
		return sb.append( ", hash " ).append( Integer.toHexString( hash )).toString();
	}


	private static void checkTheKeeperKeepsTheEpisodesWorthWatching( EnvConfig config ){
		ReplayKeeper keeper = new ReplayKeeper();

		Replay ordinary = fixture( "KEEPER-A", "WARRIOR", 1, -3_100.0 );
		Replay floorTwo = fixture( "KEEPER-A", "ROGUE", 2, -1_276.0 );
		Replay alsoOrdinary = fixture( "KEEPER-B", "MAGE", 1, -3_400.0 );
		Replay dreadful = fixture( "KEEPER-B", "CLERIC", 1, -3_700.0 );

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

		ReplayKeeper capped = new ReplayKeeper( 2 );
		capped.consider( fixture( "CAP-A", "WARRIOR", 1, -100.0 ));
		capped.consider( fixture( "CAP-B", "WARRIOR", 1, -300.0 ));
		capped.consider( fixture( "CAP-C", "WARRIOR", 1, -200.0 ));
		capped.consider( fixture( "CAP-D", "WARRIOR", 1, -900.0 ));
		List< String > cappedSeeds = seedsOf( new java.util.ArrayList<>( capped.bestPerSeed() ));
		if (!cappedSeeds.equals( java.util.Arrays.asList( "CAP-A@-100", "CAP-C@-200" ))){
			fail( "a keeper capped at 2 seeds held " + cappedSeeds + " after four seeds, where the two"
					+ " best, CAP-A and CAP-C, are what a run of unbounded random seeds must keep" );
		}
	}

	private static Replay fixture( String seed, String hero, int depth, double score ){
		Replay recording = new Replay();
		recording.seedText = seed;
		recording.heroClass = hero;
		recording.depth = depth;
		recording.score = score;
		return recording;
	}

	private static List< String > seedsOf( List< Replay > replays ){
		List< String > names = new ArrayList<>();
		for (Replay recording : replays) names.add( recording.seedText + "@" + (long) recording.score );
		return names;
	}


	private static void checkRecordedPositionsArePostStep( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, new Random( 4242L ) );

		EpisodeCollector collector = new EpisodeCollector( config, network,
				new Random( 7L ), new Random( 99L ), 0.99f, 0.95f, 0.05f );

		CollectTrace.Trace trace = record( env, collector );

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


	private static void checkTwoCollectorsWithTheSameSeedAgree( EnvConfig config ){
		CollectTrace.Trace a = traceFrom( config, 4242L, 7L, 99L );
		CollectTrace.Trace b = traceFrom( config, 4242L, 7L, 99L );

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


	private static void checkScriptedPathStillAgrees( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), SEED.hashCode() );

		CollectTrace.Trace trace = new CollectTrace.Trace();
		env.reset( SEED, HeroClass.WARRIOR );

		int[] slot = new int[ 1 ];
		while (env.running()){
			EnvMode mode = env.mode();
			Action action = policy.choose( env, slot );
			float reward = (float) env.step( action, slot[ 0 ] );
			trace.steps.add( new CollectTrace.Step( action.name(), slot[ 0 ], mode.name(),
					env.heroPosition(), reward ));
		}

		replay( env, config, trace );
	}


	private static void checkRandomSeedEpisodeIsReplayable( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, new Random( 31337L ) );
		EpisodeCollector collector = new EpisodeCollector( config, network,
				new Random( 5L ), new Random( 6L ), 0.99f, 0.95f, 0.05f );

		CollectTrace.Trace trace = new CollectTrace.Trace();
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
				trace.steps.add( new CollectTrace.Step( action, secondary, mode.name(),
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

		ReplayIO.Verification result = verifyRecording( config, run.replay );
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

		ReplayIO.Verification caught = verifyRecording( config, late.replay );
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


	private static class Recorded {
		Replay replay;
		String droppedItem = "";
		String captureAfter = "";
		int droppedSlot = -1;
		int dropAt = -1;
	}


	private static Recorded recordADrop( EnvConfig config, boolean captureAfterStep ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( "DROP-IN-SLOT", HeroClass.DUELIST );

		Recorded out = new Recorded();
		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( env.seedText(), HeroClass.DUELIST.name(), 0, config.turnLimitPerFloor, config );
		out.replay = recorder.replay();

		int slot = -1;
		env.mapper().refreshSlots();
		for (int i = 0; i < env.mapper().slots().size(); i++){
			Item item = env.mapper().slot( i );
			if (item == null) continue;
			int bound = Dungeon.quickslot.getSlot( item );
			if (bound >= 0 && !item.isEquipped( Dungeon.hero )){ slot = i; break; }
		}
		if ( slot < 0 ) return out;

		Item chosen = env.mapper().slot( slot );
		out.droppedItem = chosen.getClass().getSimpleName();
		out.droppedSlot = Dungeon.quickslot.getSlot( chosen );

		recorder.record( Action.USE, 0, EnvMode.WORLD );
		env.step( Action.USE, 0 );
		recorder.afterStep( env.heroPosition(), 0f );

		out.dropAt = recorder.replay().length();
		if ( captureAfterStep ){
			env.step( Action.DROP, slot );
			out.captureAfter = Quickslots.capture();
			recorder.record( Action.DROP, slot, EnvMode.SLOT );
			recorder.afterStep( env.heroPosition(), 0f );
		} else {
			recorder.record( Action.DROP, slot, EnvMode.SLOT );
			env.step( Action.DROP, slot );
			recorder.afterStep( env.heroPosition(), 0f );
			out.captureAfter = Quickslots.capture();
		}

		int[] nothing = new int[ 1 ];
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), 3 );
		while (env.running() && recorder.replay().length() < 24){
			Action action = policy.choose( env, nothing );
			recorder.record( action, nothing[ 0 ], env.mode() );
			env.step( action, nothing[ 0 ] );
			recorder.afterStep( env.heroPosition(), 0f );
		}

		recorder.end( 0, env.deepestDepth(), env.turnsTotal(), 0, env.endReason().name() );
		return out;
	}


	private static void checkTheCollectorAnnouncesADecisionBeforeItIsApplied( EnvConfig config ){
		String where = "the collector announces a decision before the step is applied";

		EnvConfig shortRun = new EnvConfig();
		shortRun.turnLimitPerFloor = 120;

		for (int attempt = 0; attempt < ATTEMPTS; attempt++){
			Network network = new Network( shortRun, new Random( 1000L + attempt ));
			SPDEnv env = new SPDEnv( shortRun, HeadlessGame.install() );
			EpisodeCollector collector = new EpisodeCollector( shortRun, network,
					new Random( 7L + attempt ), new Random( 99L ), 0.99f, 0.95f, 0.05f );

			final ReplayRecorder recorder = new ReplayRecorder();

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


	private static ReplayIO.Verification verifyRecording( EnvConfig config, Replay replay ){
		try {
			File file = File.createTempFile( "collectcheck-drop", ".replay" );
			ReplayIO.write( replay, file );
			Replay read = ReplayIO.read( file );
			file.delete();
			SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
			return ReplayIO.verify( read, env );
		} catch (java.io.IOException e){
			fail( "a DROP fixture could not be written: " + e.getMessage() );
			return new ReplayIO.Verification();
		}
	}
}