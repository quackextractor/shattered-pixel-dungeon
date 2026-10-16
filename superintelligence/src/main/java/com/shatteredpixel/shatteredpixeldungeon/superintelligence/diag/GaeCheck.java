package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TransitionCodec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class GaeCheck {


	private static final float GAMMA = 0.987f;
	private static final float LAMBDA = 0.913f;


	private static final int CHECKS = 13;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		checkScalarMatchesTransitionList();
checkTerminalZerosTheTail();
		checkTruncatedBootstraps();
		checkTerminalAndTruncatedDifferOnlyAtTheEnd();
		checkAPenalisedTruncationStillCarriesItsPenalty();
		checkNoDiscountAcrossTerminal();
		checkSamplingIsUniformAndSeeded();
		checkTailIsAlwaysRetained();
		checkRetainIsIdempotent();
		checkAdvantagesAreIndependentOfSampling();
		checkTransitionCodecRoundTrip();
		checkShortEpisodeRetainsItsTail();
		checkClipFractionIsNotAdvantageMagnitude();

		if (failures.isEmpty()){
			System.out.println( "[OK]     GAE: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  GAE: " + failures.size() + " of " + CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}


	private static void checkScalarMatchesTransitionList(){
		int n = 64;
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminal = new float[ n ];
		float[] finalValue = { 0.37f };

		Random rng = new Random( 991L );
		for (int i = 0; i < n; i++){
			rewards[ i ] = rng.nextFloat() * 4f - 2f;
			values[ i ] = rng.nextFloat() * 3f - 1.5f;
			terminal[ i ] = 0f;
		}

		terminal[ n / 2 ] = 1f;

		float[] scalarAdvantages = new float[ n ];
		float[] scalarReturns = new float[ n ];
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, finalValue[ 0 ],
				GAMMA, LAMBDA, scalarAdvantages, scalarReturns );

		ArrayList<Transition> list = new ArrayList<>();
		for (int i = 0; i < n; i++){
			Transition t = new Transition();
			t.reward = rewards[ i ];
			t.value = values[ i ];
			t.terminal = terminal[ i ] != 0f;
			t.nextValue = finalValue[ 0 ];
			list.add( t );
		}
		Policy.computeReturnsAndAdvantages( list, 0, n, GAMMA, LAMBDA );

		for (int i = 0; i < n; i++){
			Transition t = list.get( i );
			if (t.advantage != scalarAdvantages[ i ]){
				fail( "advantage mismatch at step " + i + ": transitions=" + t.advantage
						+ " scalars=" + scalarAdvantages[ i ] );
				return;
			}
			if (t.returnValue != scalarReturns[ i ]){
				fail( "return mismatch at step " + i + ": transitions=" + t.returnValue
						+ " scalars=" + scalarReturns[ i ] );
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


	private static void checkSamplingIsUniformAndSeeded(){
		int n = 2000;
		float rate = 0.25f;

		boolean[] a = drawMask( n, rate, 1234L );
		boolean[] b = drawMask( n, rate, 1234L );
		boolean[] c = drawMask( n, rate, 9999L );

		for (int i = 0; i < n; i++){
			if (a[ i ] != b[ i ]){
				fail( "the same seed produced different samples at step " + i );
				return;
			}
		}

		int differing = 0;
		for (int i = 0; i < n; i++) if (a[ i ] != c[ i ]) differing++;
		if (differing < n / 20){
			fail( "two seeds produced nearly identical samples (" + differing + " of " + n
					+ " differ), so the seed is not reaching the sampler" );
			return;
		}

		int kept = 0;
		for (boolean s : a) if (s) kept++;

		if (Math.abs( kept - n * rate ) > 4 * Math.sqrt( n * rate * (1 - rate ) )){
			fail( "expected about " + (int) (n * rate) + " samples, kept " + kept );
		}
	}


	private static void checkTailIsAlwaysRetained(){
		int n = 300;
		EpisodeRecord record = new EpisodeRecord();
		java.util.ArrayDeque< Tail > tail = new java.util.ArrayDeque<>();

		Random samplingRng = new Random( 7L );
		float rate = 0.01f;

		for (int i = 0; i < n; i++){
			Transition t = new Transition();
			if (samplingRng.nextFloat() < rate) record.retain( i, t );

			tail.addLast( new Tail( i, t ) );
			while (tail.size() > EpisodeCollector.TAIL_STEPS) tail.removeFirst();
		}

		for (Tail held : tail){
			if (!record.isRetained( held.transition )) record.retain( held.step, held.transition );
		}

		for (int i = n - EpisodeCollector.TAIL_STEPS; i < n; i++){
			boolean found = false;
			for (int s = 0; s < record.sampledCount(); s++){
				if (record.sampledStepAt( s ) == i ) found = true;
			}
			if (!found){
				fail( "step " + i + " is in the tail but was not retained" );
				return;
			}
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


	private static void checkShortEpisodeRetainsItsTail(){
		int n = 15;
		int tailSteps = Math.min( n, EpisodeCollector.TAIL_STEPS );

		Random samplingRng = new Random( 5L );
		EpisodeRecord record = new EpisodeRecord();
		java.util.ArrayDeque< Tail > tail = new java.util.ArrayDeque<>();

		for (int i = 0; i < n; i++){
			Transition t = new Transition();
			if (samplingRng.nextFloat() < 0.05f) record.retain( i, t );

			tail.addLast( new Tail( i, t ) );
			while (tail.size() > EpisodeCollector.TAIL_STEPS) tail.removeFirst();
		}
		for (Tail held : tail){
			if (!record.isRetained( held.transition )) record.retain( held.step, held.transition );
		}

		if (record.sampledCount() < tailSteps){
			fail( "a " + n + "-step episode retained " + record.sampledCount()
					+ " steps, fewer than the " + tailSteps + " tail steps it must keep" );
			return;
		}

		for (int i = n - tailSteps; i < n; i++){
			if (!retainsStep( record, i )){
				fail( "step " + i + " is in a " + n + "-step episode's tail but was not retained" );
				return;
			}
		}
	}


	private static void checkTransitionCodecRoundTrip(){
		EnvConfig config = new EnvConfig();
		int gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;

		int stateFloats = 2 * 128;

		Transition sent = new Transition();
		sent.grid = new byte[ gridSize ];
		sent.inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
		sent.hero = new float[ HeroEncoder.FEATURES ];
		sent.actionMask = new float[ Action.size() ];
		sent.slotMask = new float[ config.maxSlots ];
		sent.targetMask = new float[ ActionMapper.TARGET_COUNT ];

		Random rng = new Random( 24680L );
		for (int i = 0; i < sent.grid.length; i++) sent.grid[ i ] = (byte) (rng.nextBoolean() ? 1 : 0 );
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
					+ truncatedStep + ", which is not carrying the penalty. Truncation must not be able to" );
			fail( "erase a charged reward." );
			return;
		}
		if (terminalStep > -2900f){
			fail( "a terminal step charged " + rewards[ n - 1 ] + " produced advantage "
					+ terminalStep + ", which is not carrying the penalty." );
			return;
		}
		if (Math.abs( truncatedStep - terminalStep ) < 1e-4f){
			fail( "truncated and terminal gave the same advantage (" + truncatedStep + ") for a penalised" );
			fail( "final step. The bootstrap correction is supposed to be the only difference." );
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

	private static boolean retainsStep( EpisodeRecord record, int step ){
		for (int i = 0; i < record.sampledCount(); i++){
			if (record.sampledStepAt( i ) == step ) return true;
		}
		return false;
	}

	private static boolean[] drawMask( int n, float rate, long seed ){
		Random rng = new Random( seed );
		boolean[] mask = new boolean[ n ];
		for (int i = 0; i < n; i++) mask[ i ] = rng.nextFloat() < rate;
		return mask;
	}

	private static void fail( String message ){
		failures.add( message );
	}


	private static class Tail {
		final int step;
		final Transition transition;

		Tail( int step, Transition transition ){
			this.step = step;
			this.transition = transition;
		}
	}
}