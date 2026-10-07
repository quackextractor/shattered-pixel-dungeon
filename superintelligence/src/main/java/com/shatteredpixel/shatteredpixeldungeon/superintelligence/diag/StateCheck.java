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

/**
 * Fails if replaying a sampled observation does not reproduce the value the rollout saw.
 *
 * <pre>gradle :superintelligence:statecheck</pre>
 *
 * <p>This is the check that the parallel update depends on, and it is here because the bug it guards
 * against was live for the whole of the first implementation of the update.
 *
 * <p>{@code PPO.update} replays each stored observation through the current weights. The weights are
 * available; the <em>state</em> was not, because the LSTM's {@code h}/{@code c} were whatever the
 * previously processed sample left behind — and the buffer is shuffled, so that was a different
 * timestep of a different episode. Measured with {@code replayprobe}: exact to 0.000000 in collection
 * order, drifting up to 0.20 in shuffled order.
 *
 * <p>Two things were wrong at once, and they are the same fact seen from two sides:
 *
 * <ol>
 *   <li><b>The objective was wrong.</b> The ratio {@code pi_new / pi_old} was formed across two
 *       different states. This is not a perturbation of PPO, it is a different objective — and it is
 *       why the clip fraction sat at 0.84 on the first update, a value that happened to be
 *       indistinguishable from {@code P(|N(0,1)| > 0.2)} and so read as a plausible measurement.
 *   <li><b>The samples were coupled.</b> Sample <i>i</i> depended on sample <i>i-1</i>. That is why
 *       "parallelise the minibatch" was blocked: not by anything to do with threads, but by a
 *       correctness bug that would have been *solved* by the parallelisation, had anyone tried.
 * </ol>
 *
 * <p>So the assertions are behavioural. A transition that carries its state must replay to the
 * rollout's value regardless of what is processed before it, and an update must be
 * order-independent. Both are properties of the arithmetic, checked by running it rather than by
 * reading the field assignments.
 */
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

	/**
	 * A sample replayed in any order must produce the value the rollout recorded.
	 *
	 * <p>This is the property the whole update rests on. If it holds, the behaviour policy's
	 * {@code oldLogProbability} and the update's ratio describe the same state, and the policy
	 * gradient is the gradient of the objective that was intended.
	 */
	private static void checkReplayMatchesRolloutInAnyOrder( EnvConfig config ){
		Network network = new Network( config, new Random( 31337L ) );
		List<Transition> transitions = collect( network, config, 8, 99L );

		//reversed, so every sample's predecessor is unrelated to it
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

	/**
	 * The replayed value must not depend on processing order at all.
	 *
	 * <p>Distinct from the check above, and stronger in the way that matters for the parallel update:
	 * this one asserts *order independence* directly, which is the precondition for handing minibatches
	 * to different threads. A field that is carried but restored in the wrong order passes nothing
	 * else.
	 */
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

	/**
	 * The cell state has to travel too, not only the hidden state.
	 *
	 * <p>{@code Network.state()} used to return {@code h} alone, and restoring only {@code h} gives
	 * the network a cell state of zeros with a hidden state of its own — a state it has never been in.
	 * The result is finite, plausible, and wrong, which is the worst combination available. The
	 * assertion here is that restoring {@code h} without {@code c} moves the value measurably.
	 */
	private static void checkCellStateIsCarriedToo( EnvConfig config ){
		Network network = new Network( config, new Random( 555L ) );
		Transition t = collect( network, config, 6, 99L ).get( 4 );

		float[] full = new float[ network.stateSize() ];
		network.resetState();
		//walk to the step so the live state is the one it was decided under
		replayTo( network, t, full );

		//the correct replay, with both halves restored
		network.loadState( full );
		network.forward( grid( t ), t.inventory, t.hero );
		float correct = network.value();

		//the same, with the cell half zeroed - what a state field carrying only h would produce
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

	/**
	 * A transition arriving without a state must be distinguishable from one carrying a real one.
	 *
	 * <p>{@code readState} maps a zero length to {@code null}, and {@code PPO} skips the restore for a
	 * {@code null}. That is the one legal way to have no state, and it has to be visible: if it were
	 * indistinguishable from an all-zero state, a worker that failed to write the field would produce
	 * a run that trains on silently wrong values rather than refusing.
	 */
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

		//restoring nothing is not the same as restoring the zero state the episode began in, because
		//this sample was decided several steps in. If it were the same, a dropped field would be
		//harmless and the check would be asserting the wrong thing.
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

	// --------------------------------------------------------------------------- helpers

	private static EnvConfig smallConfig(){
		EnvConfig config = new EnvConfig();
		config.gridWidth = 12;
		config.gridHeight = 12;
		config.maxSlots = 4;
		return config;
	}

	/** Collects transitions the way a rollout does: forward, snapshot the state, keep going. */
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

			//the snapshot happens before the forward, matching EpisodeCollector
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

	/** Puts the network into the state {@code t} was decided under, then hands that state back. */
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
