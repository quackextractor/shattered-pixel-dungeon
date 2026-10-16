package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class StateCheck {

	private static final int CHECKS = 4;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		EnvConfig config = smallConfig();

		checkReplayMatchesRolloutInAnyOrder( config );
		checkReplayIsOrderIndependent( config );
		checkCellStateIsCarriedToo( config );
		checkMissingStateIsDetectable( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     recurrent state: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  recurrent state: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkReplayMatchesRolloutInAnyOrder( EnvConfig config ){
		Network network = new Network( config, new Random( 31337L ) );
		List<Transition> transitions = collect( network, config, 8, 99L );

		double worst = 0;
		network.resetState();
		for (int i = transitions.size() - 1; i >= 0; i--){
			Transition t = transitions.get( i );
			network.loadState( t.recurrentState );
			network.forward( grid( t ), t.inventory, t.hero );
			worst = Math.max( worst, Math.abs( network.value() - t.value ) );
		}

		if (worst > 1e-5){
			fail( "replaying a sampled observation drifted from the rollout's value by " + worst
					+ " when the samples were reversed. The transition is not carrying the state it was"
					+ " decided under, so the update is optimising a different objective than the"
					+ " one the advantage was computed for." );
		}
	}

	private static void checkReplayIsOrderIndependent( EnvConfig config ){
		Network network = new Network( config, new Random( 31337L ) );
		List<Transition> transitions = collect( network, config, 10, 99L );

		float[] forward = replayInOrder( network, transitions, false );
		float[] reverse = replayInOrder( network, transitions, true );

		for (int i = 0; i < forward.length; i++){
			if (Math.abs( forward[ i ] - reverse[ i ] ) > 1e-6){
				fail( "sample " + i + " replayed to " + forward[ i ] + " forwards and "
						+ reverse[ i ] + " backwards. Samples must be independent of each other, or a"
						+ " minibatch cannot be split across threads." );
				return;
			}
		}
	}

	private static void checkCellStateIsCarriedToo( EnvConfig config ){
		Network network = new Network( config, new Random( 555L ) );
		Transition t = collect( network, config, 6, 99L ).get( 4 );

		float[] full = new float[ network.stateSize() ];
		network.resetState();

		replayTo( network, t, full );

		network.loadState( full );
		network.forward( grid( t ), t.inventory, t.hero );
		float correct = network.value();

		float[] hiddenOnly = full.clone();
		int half = hiddenOnly.length / 2;
		java.util.Arrays.fill( hiddenOnly, half, hiddenOnly.length, 0f );
		network.loadState( hiddenOnly );
		network.forward( grid( t ), t.inventory, t.hero );
		float withoutCell = network.value();

		if (Math.abs( correct - withoutCell ) < 1e-6){
			fail( "restoring the hidden state without the cell state produced the same value ("
					+ correct + "), so this check cannot tell the two apart. Either the cell state is"
					+ " not being carried, or it does not matter here." );
		}
	}

	private static void checkMissingStateIsDetectable( EnvConfig config ){
		Network network = new Network( config, new Random( 31337L ) );
		Transition t = collect( network, config, 6, 99L ).get( 4 );

		Transition bare = new Transition();
		bare.grid = t.grid;
		bare.inventory = t.inventory;
		bare.hero = t.hero;
		bare.actionMask = t.actionMask;
		bare.slotMask = t.slotMask;
		bare.targetMask = t.targetMask;
		bare.recurrentState = null;
		bare.value = t.value;

		network.loadState( t.recurrentState );
		network.forward( grid( t ), t.inventory, t.hero );
		float correct = network.value();

		network.resetState();
		network.forward( grid( t ), t.inventory, t.hero );
		float dropped = network.value();

		if (Math.abs( correct - dropped ) < 1e-6){
			fail( "a transition with no recurrent state replayed identically to one carrying it, so"
					+ " a dropped state field would be invisible here. It would not be invisible in"
					+ " training." );
		}

		if (bare.recurrentState != null){
			fail( "a transition built without a state has one anyway" );
		}
	}

	private static EnvConfig smallConfig(){
		EnvConfig config = new EnvConfig();
		config.gridWidth = 12;
		config.gridHeight = 12;
		config.maxSlots = 4;
		return config;
	}

	private static List<Transition> collect( Network network, EnvConfig config, int n, long seed ){
		Random rng = new Random( seed );
		List<Transition> out = new ArrayList<>();
		network.resetState();

		for (int i = 0; i < n; i++){
			Transition t = new Transition();
			t.grid = new byte[ config.spatialChannels() * config.gridWidth * config.gridWidth ];
			t.inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
			t.hero = new float[ HeroEncoder.FEATURES ];
			t.actionMask = new float[ Action.size() ];
			t.slotMask = new float[ config.maxSlots ];
			t.targetMask = new float[ ActionMapper.TARGET_COUNT ];
			t.recurrentState = new float[ network.stateSize() ];
			t.liveHead = Policy.HEAD_ACTION;
			t.actionIndex = 3;

			for (int g = 0; g < t.grid.length; g++) t.grid[ g ] = (byte) (rng.nextFloat() > 0.6f ? 1 : 0);
			for (float[] a : new float[][]{ t.inventory, t.hero, t.actionMask, t.slotMask, t.targetMask }){
				for (int k = 0; k < a.length; k++) a[ k ] = rng.nextFloat();
			}

			network.saveState( t.recurrentState );
			network.forward( grid( t ), t.inventory, t.hero );
			t.value = network.value();

			out.add( t );
		}
		return out;
	}

	private static float[] replayInOrder( Network network, List<Transition> transitions, boolean reverse ){
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

	private static void replayTo( Network network, Transition t, float[] out ){
		network.loadState( t.recurrentState );
		System.arraycopy( t.recurrentState, 0, out, 0, out.length );
	}

	private static float[] grid( Transition t ){
		float[] dst = new float[ t.grid.length ];
		t.unpackGrid( dst );
		return dst;
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
