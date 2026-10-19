package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TransitionCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
public class GaeCheck {
	private static final float GAMMA = 0.987f;
	private static final float LAMBDA = 0.913f;
	private static final int CHECKS = 15;
	private static final List< String > failures = new ArrayList<>();
	public static void main( String[] args ){
		checkRecurrenceMatchesTheDiscountedSum();
checkTerminalZerosTheTail();
		checkTruncatedBootstraps();
		checkTerminalAndTruncatedDifferOnlyAtTheEnd();
		checkAPenalisedTruncationStillCarriesItsPenalty();
		checkNoDiscountAcrossTerminal();
		checkRetainIsIdempotent();
		checkAdvantagesAreIndependentOfSampling();
		checkTransitionCodecRoundTrip();
		checkThePackedGridSurvivesARoundTrip();
		checkThePackedGridIsCompact();
		checkAFrameOfManyTransitionsRoundTrips();
		checkClipFractionIsNotAdvantageMagnitude();
		PolicyUpdateCheck.run( GaeCheck::fail );
		if (failures.isEmpty()){
			System.out.println( "[OK]     GAE: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  GAE: " + failures.size() + " of " + CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}
	private static void checkThePackedGridSurvivesARoundTrip(){
		EnvConfig config = new EnvConfig();
		int cells = config.spatialChannels() * config.gridWidth * config.gridWidth;
		Transition t = new Transition();
		t.gridCells = cells;
		t.grid = new byte[ Transition.packedGridBytes( cells ) ];
		java.util.Random rng = new java.util.Random( 987654321L );
		float[] src = new float[ cells ];
		randomGrid( rng, src );
		t.packGrid( src );
		float[] back = new float[ cells ];
		t.unpackGrid( back );
		for (int i = 0; i < cells; i++){
			if (back[ i ] == src[ i ] ) continue;
			fail( "the packed grid lost cell " + i + ": packed " + src[ i ] + ", unpacked "
					+ back[ i ] + ". The packing has to be lossless for every value the encoder emits or"
					+ " the update trains on an observation the agent never saw." );
			return;
		}
		System.out.println( "  the packed grid round-trips exactly, fractional planes included ("
				+ cells + " cells)" );
	}
	private static void randomGrid( java.util.Random rng, float[] grid ){
		boolean[] fractional = GridChannel.fractionalPlanes();
		int area = grid.length / fractional.length;
		for (int i = 0; i < grid.length; i++){
			grid[ i ] = fractional[ i / area ]
					? GridChannel.quantise( rng.nextInt( 9 ) / 8f )
					: rng.nextBoolean() ? 1f : 0f;
		}
	}
	private static void checkThePackedGridIsCompact(){
		EnvConfig config = new EnvConfig();
		int cells = config.spatialChannels() * config.gridWidth * config.gridWidth;
		int packed = Transition.packedGridBytes( cells );
		if (packed * 4 >= cells){
			fail( "the packed grid is " + packed + " bytes for " + cells + " cells, which is not the"
					+ " bit-per-binary-cell reduction the packing exists for." );
			return;
		}
		System.out.println( "  the grid travels as " + packed + " bytes for " + cells
				+ " cells - " + fmt( (double) packed / cells ) + " of it" );
	}
	private static String fmt( double v ){
		return String.format( "%.3f", v );
	}
	private static void checkAFrameOfManyTransitionsRoundTrips(){
		EnvConfig config = new EnvConfig();
		int cells = config.spatialChannels() * config.gridWidth * config.gridWidth;
		int stateFloats = 2 * 128;
		Network net = new Network( config, new java.util.Random( 5150L ));
		int count = 5;
		List<Transition> sent = new ArrayList<>();
		for (int i = 0; i < count; i++){
			Transition t = new Transition();
			t.gridCells = cells;
			t.grid = new byte[ Transition.packedGridBytes( cells ) ];
			t.inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
			t.hero = new float[ HeroEncoder.FEATURES ];
			t.actionMask = new float[ Action.size() ];
			t.slotMask = new float[ config.maxSlots ];
			t.targetMask = new float[ ActionMapper.TARGET_COUNT ];
			t.recurrentState = new float[ stateFloats ];
			java.util.Random rng = new java.util.Random( 1000L + i );
			for (float[] a : new float[][]{ t.inventory, t.hero, t.actionMask, t.slotMask,
					t.targetMask, t.recurrentState }){
				for (int k = 0; k < a.length; k++) a[ k ] = rng.nextFloat();
			}
			float[] grid = new float[ cells ];
			randomGrid( rng, grid );
			t.packGrid( grid );
			t.liveHead = i % 3;
			t.episodeId = 7;
			t.stepIndex = 100 + i;
			t.actionIndex = i;
			t.slotIndex = i * 2;
			t.oldLogProbability = -0.5f * i;
			t.advantage = 0.25f * i;
			t.returnValue = -1.5f * i;
			t.terminal = (i % 2 == 0);
			sent.add( t );
		}
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			TransitionCodec.writeBody( new DataOutputStream( bytes ), sent );
			List<Transition> back = TransitionCodec.readBody(
					new DataInputStream( new java.io.ByteArrayInputStream( bytes.toByteArray() )),
					config, net.stateSize() );
			if (back.size() != count){
				fail( "a frame of " + count + " transitions read back " + back.size() + ". A field"
						+ " whose size depends on the previous transition is read correctly once and"
						+ " wrong from then on, which is what a packing computed inside the read loop"
						+ " looks like." );
				return;
			}
			for (int i = 0; i < count; i++){
				Transition a = sent.get( i );
				Transition b = back.get( i );
				if (!java.util.Arrays.equals( a.grid, b.grid ) || b.stepIndex != a.stepIndex
						|| b.episodeId != a.episodeId || b.actionIndex != a.actionIndex ){
					fail( "transition " + i + " came back as transition " + b.stepIndex
							+ " with action " + b.actionIndex + ", so the frame desynchronised at"
							+ " or before it." );
					return;
				}
			}
		} catch (java.io.IOException e){
			fail( "a frame of " + count + " transitions threw: " + e.getMessage() );
			return;
		}
		System.out.println( "  a frame of " + count + " transitions round-trips in order" );
	}
	private static void checkRecurrenceMatchesTheDiscountedSum(){
		int n = 64;
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminal = new float[ n ];
		float finalValue = 0.37f;
		Random rng = new Random( 991L );
		for (int i = 0; i < n; i++){
			rewards[ i ] = rng.nextFloat() * 4f - 2f;
			values[ i ] = rng.nextFloat() * 3f - 1.5f;
		}
		terminal[ n / 2 ] = 1f;
		float[] advantages = new float[ n ];
		float[] returns = new float[ n ];
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, finalValue,
				GAMMA, LAMBDA, advantages, returns );
		for (int i = 0; i < n; i++){
			double expected = 0;
			double weight = 1;
			for (int j = i; j < n; j++){
				double next = j == n - 1 ? finalValue : values[ j + 1 ];
				boolean ends = terminal[ j ] != 0f;
				expected += weight * (rewards[ j ] + (ends ? 0 : GAMMA * next) - values[ j ]);
				if (ends) break;
				weight *= GAMMA * LAMBDA;
			}
			double tolerance = 1e-4 * Math.max( 1, Math.abs( expected ));
			if (Math.abs( advantages[ i ] - expected ) > tolerance
					|| Math.abs( returns[ i ] - (expected + values[ i ]) ) > tolerance){
				fail( "step " + i + ": recurrence gives advantage " + advantages[ i ] + " return "
						+ returns[ i ] + ", the discounted sum of TD errors gives " + expected
						+ " and " + (expected + values[ i ]) );
				return;
			}
		}
	}
	private static void checkTerminalZerosTheTail(){
		int n = 8;
		float[] rewards = { 0, 0, 0, 5f, 0, 0, 0, 0 };
		float[] values = new float[ n ];
		float[] terminal = new float[ n ];
		float[] advantages = new float[ n ];
		float[] returns = new float[ n ];
		terminal[ 6 ] = 1f;
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, 99f, GAMMA, LAMBDA,
				advantages, returns );
		if (Math.abs( advantages[ 6 ] - (rewards[ 6 ] - values[ 6 ]) ) > 1e-5f){
			fail( "terminal step bootstrapped: advantage " + advantages[ 6 ]
					+ ", expected reward minus value " + (rewards[ 6 ] - values[ 6 ]) );
		}
		float[] single = new float[ n ];
		float[] onlyTerminal = new float[ n ];
		Policy.computeEpisodeAdvantages( rewards, values, terminal, 6, 0f, GAMMA, LAMBDA, single, onlyTerminal );
		for (int i = 0; i < 6; i++){
			if (Math.abs( single[ i ] - advantages[ i ] ) > 1e-6f){
				fail( "step " + i + " before the terminal was affected by it: "
						+ advantages[ i ] + " vs " + single[ i ] );
				return;
			}
		}
	}
	private static void checkTruncatedBootstraps(){
		int n = 4;
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminal = new float[ n ];
		float[] noBootstrap = new float[ n ];
		float[] withBootstrap = new float[ n ];
		float[] scratch = new float[ n ];
		rewards[ n - 1 ] = 1f;
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, 0f, GAMMA, LAMBDA,
				noBootstrap, scratch );
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, 10f, GAMMA, LAMBDA,
				withBootstrap, scratch );
		if (Math.abs( noBootstrap[ n - 1 ] - withBootstrap[ n - 1 ] ) < 1e-6f){
			fail( "the final value estimate had no effect, so a truncated episode cannot bootstrap" );
		}
		float expected = 1f + GAMMA * 10f;
		if (Math.abs( withBootstrap[ n - 1 ] - expected ) > 1e-4f){
			fail( "bootstrap is " + withBootstrap[ n - 1 ] + ", expected reward + gamma*V(s_T) = "
					+ expected );
		}
	}
	private static void checkTerminalAndTruncatedDifferOnlyAtTheEnd(){
		int n = 6;
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminalFlags = new float[ n ];
		float[] asStall = new float[ n ];
		float[] asDeath = new float[ n ];
		float[] scratch = new float[ n ];
		rewards[ n - 1 ] = -100f;
		Policy.computeEpisodeAdvantages( rewards, values, terminalFlags, n, 10f, GAMMA, LAMBDA,
				asStall, scratch );
		float[] died = terminalFlags.clone();
		died[ n - 1 ] = 1f;
		Policy.computeEpisodeAdvantages( rewards, values, died, n, 10f, GAMMA, LAMBDA,
				asDeath, scratch );
		float boot = rewards[ n - 1 ] + GAMMA * 10f;
		if (Math.abs( asStall[ n - 1 ] - boot ) > 1e-4f ){
			fail( "a truncated final step gave advantage " + asStall[ n - 1 ] + ", expected reward +"
					+ " gamma*V(s_T) = " + boot + ". A stalled episode is a truncation, and this is"
					+ " where that has to show up." );
			return;
		}
		float diedDelta = rewards[ n - 1 ] - values[ n - 1 ];
		if (Math.abs( asDeath[ n - 1 ] - diedDelta ) > 1e-4f ){
			fail( "a terminal final step gave advantage " + asDeath[ n - 1 ] + ", expected reward -"
					+ " value = " + diedDelta + ". A death must not bootstrap." );
			return;
		}
		if (Math.abs( asStall[ n - 1 ] - asDeath[ n - 1 ] ) < 1e-4f ){
			fail( "a truncated and a terminal final step produced the same advantage ("
					+ asStall[ n - 1 ] + "). The reason an episode ended has to decide whether the value"
					+ " bootstraps, or a stall is indistinguishable from a death." );
		}
	}
	private static void checkNoDiscountAcrossTerminal(){
		int n = 6;
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminal = new float[ n ];
		float[] advantages = new float[ n ];
		float[] returns = new float[ n ];
		terminal[ 1 ] = 1f;
		rewards[ 3 ] = 100f;
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, 0f, GAMMA, LAMBDA,
				advantages, returns );
		if (Math.abs( advantages[ 0 ] ) > 1e-6f){
			fail( "a reward after a terminal step leaked back into step 0: advantage "
					+ advantages[ 0 ] + ", expected 0" );
			return;
		}
		if (Math.abs( advantages[ 1 ] ) > 1e-6f){
			fail( "the terminal step absorbed a downstream reward: advantage " + advantages[ 1 ]
					+ ", expected 0" );
		}
	}
	private static void checkRetainIsIdempotent(){
		EpisodeRecord record = new EpisodeRecord();
		Transition t = new Transition();
		record.retain( 5, t );
		record.retain( 5, t );
		if (record.sampledCount() != 1){
			fail( "retaining the same step twice gave " + record.sampledCount()
					+ " entries, so a tail step kept by the draw is weighted twice" );
		}
	}
	private static void checkAdvantagesAreIndependentOfSampling(){
		int n = 128;
		Random rng = new Random( 31337L );
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminal = new float[ n ];
		for (int i = 0; i < n; i++){
			rewards[ i ] = rng.nextFloat() * 6f - 3f;
			values[ i ] = rng.nextFloat() * 2f - 1f;
		}
		float[] allSteps = new float[ n ];
		float[] allReturns = new float[ n ];
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, 0.5f, GAMMA, LAMBDA,
				allSteps, allReturns );
		EpisodeRecord record = new EpisodeRecord();
for (int i = 0; i < n; i++) record.add( rewards[ i ], values[ i ], false,
				Policy.HEAD_ACTION, 0, 0, -1f );
		record.finalValue( 0.5f );
		record.computeAdvantages( GAMMA, LAMBDA );
		for (int i = 0; i < n; i++){
			if (record.advantageAt( i ) != allSteps[ i ]){
				fail( "advantage at step " + i + " changed when nothing was sampled: "
						+ record.advantageAt( i ) + " vs " + allSteps[ i ] );
				return;
			}
		}
	}
	private static void checkTransitionCodecRoundTrip(){
		EnvConfig config = new EnvConfig();
		int gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;
		int stateFloats = 2 * 128;
		Transition sent = new Transition();
		sent.gridCells = gridSize;
		sent.grid = new byte[ Transition.packedGridBytes( gridSize ) ];
		sent.inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
		sent.hero = new float[ HeroEncoder.FEATURES ];
		sent.actionMask = new float[ Action.size() ];
		sent.slotMask = new float[ config.maxSlots ];
		sent.targetMask = new float[ ActionMapper.TARGET_COUNT ];
		float[] grid = new float[ sent.gridCells ];
		java.util.Random rng = new java.util.Random( 20261016L );
		randomGrid( rng, grid );
		sent.packGrid( grid );
		for (float[] a : new float[][]{ sent.inventory, sent.hero, sent.actionMask,
				sent.slotMask, sent.targetMask }){
			for (int i = 0; i < a.length; i++) a[ i ] = rng.nextFloat();
		}
		sent.liveHead = Policy.HEAD_SLOT;
		sent.actionIndex = 11;
		sent.slotIndex = 4;
		sent.oldLogProbability = -1.25f;
		sent.advantage = 0.5f;
		sent.returnValue = -2.5f;
		sent.terminal = true;
		sent.recurrentState = new float[ stateFloats ];
		for (int i = 0; i < stateFloats; i++) sent.recurrentState[ i ] = (i % 7) * 0.125f - 0.25f;
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			java.io.DataOutputStream out = new java.io.DataOutputStream( bytes );
			TransitionCodec.writeBody( out, java.util.Collections.singletonList( sent ) );
			out.flush();
			java.io.DataInputStream in = new java.io.DataInputStream(
					new java.io.ByteArrayInputStream( bytes.toByteArray() ) );
			List<Transition> got = TransitionCodec.readBody( in, config, stateFloats );
			if (got.size() != 1){
				fail( "the round trip produced " + got.size() + " transitions, expected 1" );
				return;
			}
			Transition t = got.get( 0 );
			if (t.advantage != sent.advantage){
				fail( "the advantage did not survive the round trip: " + t.advantage
						+ " vs " + sent.advantage );
				return;
			}
			if (t.returnValue != sent.returnValue || t.oldLogProbability != sent.oldLogProbability){
				fail( "the scalars did not survive the round trip: return " + t.returnValue
						+ " logp " + t.oldLogProbability );
				return;
			}
			if (t.liveHead != sent.liveHead || t.actionIndex != sent.actionIndex
					|| t.slotIndex != sent.slotIndex || t.terminal != sent.terminal){
				fail( "the decision fields did not survive the round trip" );
				return;
			}
			if (!java.util.Arrays.equals( t.grid, sent.grid )){
				fail( "the packed grid did not survive the round trip" );
				return;
			}
			if (!java.util.Arrays.equals( t.recurrentState, sent.recurrentState )){
				fail( "the recurrent state did not survive the round trip. A transition arriving"
						+ " without it replays under whatever the previous sample left behind, so this"
						+ " one is the whole difference between a correct replay and a plausible one." );
				return;
			}
			if (t.inventory.length != sent.inventory.length
					|| t.hero.length != sent.hero.length
					|| t.actionMask.length != sent.actionMask.length
					|| t.slotMask.length != sent.slotMask.length
					|| t.targetMask.length != sent.targetMask.length){
				fail( "a buffer came back the wrong width, so the frame is misaligned" );
			}
		} catch (IOException e){
			fail( "the round trip threw: " + e.getMessage() );
		}
	}
private static void checkAPenalisedTruncationStillCarriesItsPenalty(){
		int n = 4;
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminal = new float[ n ];
		float[] truncated = new float[ n ];
		float[] scratch = new float[ n ];
		rewards[ n - 1 ] = -3000f;
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, 10f, GAMMA, LAMBDA, truncated, scratch );
		float[] asTerminal = terminal.clone();
		asTerminal[ n - 1 ] = 1f;
		Policy.computeEpisodeAdvantages( rewards, values, asTerminal, n, 10f, GAMMA, LAMBDA, terminal, scratch );
		float truncatedStep = truncated[ n - 1 ];
		float terminalStep = terminal[ n - 1 ];
		if (truncatedStep > -2900f){
			fail( "a truncated step charged " + rewards[ n - 1 ] + " produced advantage "
					+ truncatedStep + ", which is not carrying the penalty. Truncation must not be able to"
					+ " erase a charged reward." );
			return;
		}
		if (terminalStep > -2900f){
			fail( "a terminal step charged " + rewards[ n - 1 ] + " produced advantage "
					+ terminalStep + ", which is not carrying the penalty." );
			return;
		}
		if (Math.abs( truncatedStep - terminalStep ) < 1e-4f){
			fail( "truncated and terminal gave the same advantage (" + truncatedStep + ") for a penalised"
					+ " final step. The bootstrap correction is supposed to be the only difference." );
		}
	}
	private static void checkClipFractionIsNotAdvantageMagnitude(){
		float epsilon = 0.2f;
		if (clipBindingForRatio( 1f, epsilon )) fail( "ratio 1.0 reported as clipped" );
		if (clipBindingForRatio( 1.1f, epsilon )) fail( "ratio 1.1 reported as clipped" );
		if (!clipBindingForRatio( 1.25f, epsilon )) fail( "ratio 1.25 not reported as clipped" );
		if (!clipBindingForRatio( 0.75f, epsilon )) fail( "ratio 0.75 not reported as clipped" );
		Random rng = new Random( 4242L );
		int n = 20000;
		int advantageCount = 0;
		for (int i = 0; i < n; i++) if (Math.abs( rng.nextGaussian() ) > epsilon ) advantageCount++;
		double constant = (double) advantageCount / n;
		float[] ratio = { 1.0f, 1.0f, 1.0f };
		int bound = 0;
		for (float r : ratio) if (clipBindingForRatio( r, epsilon )) bound++;
		if (bound != 0){
			fail( "a batch of unmoved ratios reported " + bound + " clipped" );
			return;
		}
		if (Math.abs( constant - 0.8415 ) > 0.02){
			fail( "the |advantage| expression is no longer near 0.8415 (" + constant
					+ "), so this check no longer documents what it replaced" );
		}
	}
	private static boolean clipBindingForRatio( float ratio, float epsilon ){
		int chosen = 0;
		float[] probabilities = new float[ 4 ];
		probabilities[ chosen ] = 0.5f;
		probabilities[ 1 ] = 0.3f;
		probabilities[ 2 ] = 0.15f;
		probabilities[ 3 ] = 0.05f;
		float[] mask = { 1f, 1f, 1f, 1f };
		float oldLogProbability = (float) Math.log( probabilities[ chosen ] ) - (float) Math.log( ratio );
		float[] gradient = new float[ probabilities.length ];
		return Policy.accumulatePolicyGradient( probabilities, mask, chosen,
				oldLogProbability, 1f, epsilon, 0f, gradient );
	}
	private static void fail( String message ){
		failures.add( message );
	}
}
