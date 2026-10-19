package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class StateCheck {

	private static final int CHECKS = 6;

	private static final List< float[] > liveGrids = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new java.io.File( System.getProperty( "java.io.tmpdir" ), "spd-statecheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 60;
		config.turnLimitTotal = 60;

		Network network = new Network( config, new Random( 31337L ) );
		List< Transition > transitions = collect( network, config );

		if (transitions.size() < 8){
			GateReport.fail( "the collector retained " + transitions.size() + " transitions, too few for"
					+ " any check here to mean anything" );
		} else {
			checkReplayMatchesRolloutInAnyOrder( network, transitions );
			checkReplayIsOrderIndependent( network, transitions );
			checkCellStateIsCarriedToo( network, transitions );
			checkMissingStateIsDetectable( network, transitions );
			checkPackedGridIsTheRolloutGrid( network, transitions );
		}
		checkEveryHeadTrainsTheIndexItSampled();

		System.exit( GateReport.report( "recurrent state", CHECKS ));
	}

	private static void checkEveryHeadTrainsTheIndexItSampled(){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 400;
		config.turnLimitTotal = 400;

		int targets = 0;
		int secondaries = 0;
		for (int attempt = 0; attempt < 8 && targets == 0; attempt++){
			SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
			EpisodeCollector collector = new EpisodeCollector( config,
					new Network( config, new Random( 500L + attempt ) ), new Random( attempt ),
					new Random( 97L ), 0.99f, 0.95f, 1f, 1 << 16 );
			List< Integer > sent = new ArrayList<>();
			collector.listener( new EpisodeCollector.Listener() {
				@Override public void onEpisodeStart( String seedText, String heroClass ){}

				@Override public void onDecision( EnvMode mode, Action action, int secondary ){
					sent.add( secondary );
				}
			} );
			EpisodeRecord record = collector.run( env, "HEADCHECK-" + attempt, HeroClass.HUNTRESS );

			for (Transition t : record.sampledTransitions()){
				if (t.liveHead == Policy.HEAD_ACTION) continue;
				secondaries++;
				if (t.liveHead == Policy.HEAD_TARGET) targets++;
				if (t.slotIndex != sent.get( t.stepIndex )){
					GateReport.fail( "step " + t.stepIndex + " on head " + t.liveHead + " sent index "
							+ sent.get( t.stepIndex ) + " to the environment and recorded " + t.slotIndex
							+ " for the update, so PPO compares a new probability for one index with the"
							+ " behaviour probability of another" );
					record.release();
					return;
				}
			}
			record.release();
		}

		if (targets == 0){
			GateReport.fail( "no collected episode reached TARGETING, so the target head's recorded index"
					+ " was never compared with the one sent" );
			return;
		}
		System.out.println( "  " + secondaries + " slot and target decisions (" + targets + " targets)"
				+ " record the index the environment was sent" );
	}

	private static void checkReplayMatchesRolloutInAnyOrder( Network network, List< Transition > transitions ){
		double worst = 0;
		network.resetState();
		for (int i = transitions.size() - 1; i >= 0; i--){
			Transition t = transitions.get( i );
			network.loadState( t.recurrentState );
			network.forward( grid( t ), t.inventory, t.hero );
			worst = Math.max( worst, Math.abs( network.value() - t.value ) );
		}

		if (worst > 1e-5){
			GateReport.fail( "replaying a collected observation on the grid the rollout saw drifted from the rollout's value by " + worst
					+ " when the samples were reversed. The transition is not carrying the state it was"
					+ " decided under, so the update is optimising a different objective than the"
					+ " one the advantage was computed for." );
			return;
		}
		System.out.println( "  " + transitions.size() + " collected transitions replay to the rollout's value"
				+ " in reverse order, worst drift " + worst );
	}

	private static void checkReplayIsOrderIndependent( Network network, List< Transition > transitions ){
		float[] forward = replayInOrder( network, transitions, false );
		float[] reverse = replayInOrder( network, transitions, true );

		for (int i = 0; i < forward.length; i++){
			if (Math.abs( forward[ i ] - reverse[ i ] ) > 1e-6){
				GateReport.fail( "sample " + i + " replayed to " + forward[ i ] + " forwards and "
						+ reverse[ i ] + " backwards. Samples must be independent of each other, or a"
						+ " minibatch cannot be split across threads." );
				return;
			}
		}
	}

	private static void checkCellStateIsCarriedToo( Network network, List< Transition > transitions ){
		Transition t = transitions.get( transitions.size() / 2 );

		network.loadState( t.recurrentState );
		network.forward( grid( t ), t.inventory, t.hero );
		float correct = network.value();

		float[] hiddenOnly = t.recurrentState.clone();
		java.util.Arrays.fill( hiddenOnly, hiddenOnly.length / 2, hiddenOnly.length, 0f );
		network.loadState( hiddenOnly );
		network.forward( grid( t ), t.inventory, t.hero );

		if (Math.abs( correct - network.value() ) < 1e-6){
			GateReport.fail( "restoring the hidden state without the cell state produced the same value ("
					+ correct + "), so this check cannot tell the two apart. Either the cell state is"
					+ " not being carried, or it does not matter here." );
		}
	}

	private static void checkMissingStateIsDetectable( Network network, List< Transition > transitions ){
		Transition t = transitions.get( transitions.size() / 2 );

		network.loadState( t.recurrentState );
		network.forward( grid( t ), t.inventory, t.hero );
		float correct = network.value();

		network.resetState();
		network.forward( grid( t ), t.inventory, t.hero );

		if (Math.abs( correct - network.value() ) < 1e-6){
			GateReport.fail( "a collected transition replayed identically with and without its recurrent"
					+ " state, so a dropped state field would be invisible here. It would not be invisible"
					+ " in training." );
		}
	}

	private static List< Transition > collect( Network network, EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		EpisodeCollector collector = new EpisodeCollector( config, network, new Random( 99L ),
				new Random( 98L ), 0.99f, 0.95f, 1f, 4096 );
		collector.listener( new EpisodeCollector.Listener() {
			@Override public void onEpisodeStart( String seedText, String heroClass ){}

			@Override public void onDecision( EnvMode mode, Action action, int secondary ){
				liveGrids.add( env.grid().clone() );
			}
		} );
		EpisodeRecord record = collector.run( env, "STATECHECK", HeroClass.WARRIOR );
		return new ArrayList<>( record.sampledTransitions() );
	}

	private static float[] replayInOrder( Network network, List< Transition > transitions, boolean reverse ){
		float[] values = new float[ transitions.size() ];
		network.resetState();

		for (int k = 0; k < transitions.size(); k++){
			int i = reverse ? transitions.size() - 1 - k : k;
			Transition t = transitions.get( i );
			network.loadState( t.recurrentState );
			network.forward( grid( t ), t.inventory, t.hero );
			values[ i ] = network.value();
		}
		return values;
	}

	private static float[] grid( Transition t ){
		return liveGrids.get( t.stepIndex );
	}

	private static void checkPackedGridIsTheRolloutGrid( Network network, List< Transition > transitions ){
		for (Transition t : transitions){
			float[] packed = new float[ t.gridCells ];
			t.unpackGrid( packed );
			float[] live = liveGrids.get( t.stepIndex );
			for (int i = 0; i < packed.length; i++){
				if (packed[ i ] == live[ i ]) continue;
				GateReport.fail( "step " + t.stepIndex + " stored grid cell " + i + " as " + packed[ i ]
						+ " but the rollout saw " + live[ i ] + ", so the update trains on an observation"
						+ " the policy never acted on" );
				return;
			}
			network.loadState( t.recurrentState );
			network.forward( packed, t.inventory, t.hero );
			if (network.value() != t.value){
				GateReport.fail( "step " + t.stepIndex + " replays its stored grid to value "
						+ network.value() + " but the rollout saw " + t.value );
				return;
			}
		}
		System.out.println( "  " + transitions.size() + " stored grids unpack to exactly the rollout's" );
	}
}
