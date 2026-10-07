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

/**
 * Checks that the two advantage implementations agree, and that sampling behaves as documented.
 *
 * <pre>gradle :superintelligence:gaecheck</pre>
 *
 * {@link Policy} now computes GAE twice: once over an {@code ArrayList<Transition>} and once over an
 * episode's scalar arrays. They exist for different callers - the first for a single-process trainer,
 * the second for a worker holding a whole episode's numbers without its observations - and only one
 * of them can be correct about a given episode. Nothing forces them to agree, which is exactly the
 * failure this guards against: two implementations that both produce finite advantages and silently
 * differ by a few percent is a bug that shows up as a policy that learns slightly worse, forever.
 *
* Everything here is arithmetic over synthetic episodes. No game state is involved, so the check is
 * fast, deterministic, and unaffected by anything the game does.
 *
 * <b>What this does not cover.</b> The sampling checks restate the collector's decisions - a draw now,
 * a rolling tail at the end - rather than driving {@link EpisodeCollector}, because doing the latter
 * needs a live environment and a whole episode per case. A mutation inside the collector's own
 * retention logic would pass here. What is shared is the policy being asserted ({@code TAIL_STEPS}
 * and {@code EpisodeRecord}'s behaviour) and the arithmetic; the loop that applies the policy is
 * duplicated and therefore trusted rather than tested. Verified instead by observation: a 2-worker
 * training run reports a sampled count consistent with the tail dominating short episodes.
 */
public class GaeCheck {

	/** Deliberately not round: an off-by-one in gamma or lambda should not be masked by a tidy value. */
	private static final float GAMMA = 0.987f;
	private static final float LAMBDA = 0.913f;

	/**
	 * Number of checks, so the pass line and the failure line cannot drift apart.
	 *
	 * A hardcoded count in one message and not the other is how a check suite ends up claiming to have
	 * run more than it did.
	 */
	private static final int CHECKS = 12;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		checkScalarMatchesTransitionList();
		checkTerminalZerosTheTail();
		checkTruncatedBootstraps();
		checkTerminalAndTruncatedDifferOnlyAtTheEnd();
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

	/**
	 * The core property: both implementations must produce identical numbers for identical input.
	 *
	 * A single shared fixture drives both, including a terminal step and a truncated one, because
	 * those are the two places the recursion has a branch at all.
	 */
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
		//a terminal step in the middle, so the recursion has to reset rather than carry through
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

	/**
	 * A terminal step must zero everything before it.
	 *
	 * The `nonTerminal` multiplier is what stops a death penalty from being discounted back through
	 * the corpse, and a recursion that forgot it produces advantages that are merely plausible.
	 */
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

		//step 6 is terminal, so its own bootstrap is dropped and step 5 must not see its reward
		if (Math.abs( advantages[ 6 ] - (rewards[ 6 ] - values[ 6 ]) ) > 1e-5f){
			fail( "terminal step bootstrapped: advantage " + advantages[ 6 ]
					+ ", expected reward minus value " + (rewards[ 6 ] - values[ 6 ]) );
		}

		//steps 0..5 sit before the terminal and must be unaffected by anything after it
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

	/**
	 * A truncated episode must bootstrap from its last value estimate.
	 *
	 * Early in training almost every episode hits the turn cap rather than dying, so if truncation
	 * were treated as termination the critic would learn that every unfinished floor was worth
	 * nothing - and that is a large fraction of the data.
	 */
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

	/**
	 * The same last step, bootstrapping or not, depending on why the episode ended.
	 *
	 * <p>The arithmetic above tests that a truncation bootstraps. This tests that <em>the reason decides
	 * it</em> — the distinction a stall depends on entirely.
	 *
	 * <p>A `STALLED` episode used to be both penalised and bootstrapped: {@code terminate} marked it
	 * truncated while also charging a -5.0 terminal reward, and 100% of a 20-generation run's episodes
	 * ended that way. The critic could not settle on a value for it: one signal said the episode was
	 * over, the other said it carried on. {@code rewardcheck} covers the reward half; this covers the
	 * advantage half, because a stalled episode's advantages must be indistinguishable from a truncated
	 * one's — the difference between the two is only whether the environment had a real outcome, and a
	 * stall does not.
	 */
	private static void checkTerminalAndTruncatedDifferOnlyAtTheEnd(){
		int n = 6;
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminalFlags = new float[ n ];
		float[] asStall = new float[ n ];
		float[] asDeath = new float[ n ];
		float[] scratch = new float[ n ];

		//a dying blow at the last step, so the two cases have the same reward and only the reason differs
		rewards[ n - 1 ] = -100f;

		//STALLED: truncated, so the final value estimate carries in
		Policy.computeEpisodeAdvantages( rewards, values, terminalFlags, n, 10f, GAMMA, LAMBDA,
				asStall, scratch );

		//DEATH: terminal, so it does not
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

	/**
	 * A terminal step must cut the recursion in both directions.
	 *
	 * The reward on the terminal step itself is legitimate and does propagate backwards - that is the
	 * death penalty teaching the policy what preceded the death. What must not propagate is anything
	 * from *after* it, and in a real episode there is nothing after it, so a recursion that fails to
	 * reset would look correct on genuine data and only diverge on the fixture below.
	 */
	private static void checkNoDiscountAcrossTerminal(){
		int n = 6;
		float[] rewards = new float[ n ];
		float[] values = new float[ n ];
		float[] terminal = new float[ n ];
		float[] advantages = new float[ n ];
		float[] returns = new float[ n ];

		//terminal at 1, so only step 0 is upstream of it; the 100 sits at step 3, downstream
		terminal[ 1 ] = 1f;
		rewards[ 3 ] = 100f;
		Policy.computeEpisodeAdvantages( rewards, values, terminal, n, 0f, GAMMA, LAMBDA,
				advantages, returns );

		//step 0 must see only its own delta: values are zero and rewards are zero, so its advantage
		//should be exactly zero. Anything else is the downstream reward leaking backwards.
		if (Math.abs( advantages[ 0 ] ) > 1e-6f){
			fail( "a reward after a terminal step leaked back into step 0: advantage "
					+ advantages[ 0 ] + ", expected 0" );
			return;
		}

		//and the terminal step must not see it either, which is the same assertion one step later
		if (Math.abs( advantages[ 1 ] ) > 1e-6f){
			fail( "the terminal step absorbed a downstream reward: advantage " + advantages[ 1 ]
					+ ", expected 0" );
		}
	}

	/**
	 * The same seed must give the same sample set, and different seeds must generally differ.
	 *
	 * Reproducibility is the point of seeding the sampling RNG separately from the policy RNG: a run
	 * that cannot be repeated is a run whose bugs cannot be found.
	 */
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
		//a Bernoulli sample's count has standard deviation sqrt(n p (1-p)) ≈ 19 here
		if (Math.abs( kept - n * rate ) > 4 * Math.sqrt( n * rate * (1 - rate ) )){
			fail( "expected about " + (int) (n * rate) + " samples, kept " + kept );
		}
	}

	/**
	 * The last steps of an episode must be retained however the draw went.
	 *
	 * This is the reason tail retention exists at all. The death penalty is -100 and lands on one
	 * step; under uniform sampling alone a terminal step survives with probability equal to the
	 * sample rate, so at 5% most episodes contribute no terminal signal at all.
	 */
	private static void checkTailIsAlwaysRetained(){
		int n = 300;
		EpisodeRecord record = new EpisodeRecord();
		java.util.ArrayDeque< Tail > tail = new java.util.ArrayDeque<>();

		//a rate low enough that most steps are rejected, which is the case tail retention exists for
		Random samplingRng = new Random( 7L );
		float rate = 0.01f;

		//the same two decisions the collector makes: a draw now, and the tail at the end
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

	/**
	 * A step kept by both the draw and the tail must be retained once.
	 *
	 * Two copies would weight that step twice in the gradient. That is a different estimator rather
	 * than a noisier one, and it would be invisible in the loss.
	 */
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

	/**
	 * The retained subset's advantages must equal the full episode's.
	 *
	 * This is the assumption the whole design rests on: sampling happens during collection, so it
	 * happens *before* the backward pass, and it must not perturb it. If it did, every advantage
	 * would be a function of which observations happened to be kept.
	 */
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

		//an "empty sample" - nothing retained - must still leave the recorded scalars intact
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

/**
	 * A short episode must still retain its tail, and the sampled count must show that it did.
	 *
	 * Worth its own check because it is why a requested 5% arrives as close to 100% early in training:
	 * on a 15-step episode a 20-step tail is all of it, and on a 40,000-step one it is 0.05%. Both are
	 * correct. The sampled count in the report is what distinguishes them, which is why the trainer
	 * clamps the rate it sends rather than letting the two mechanisms silently disagree about it.
	 */
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

		//and the tail steps are the ones present, not merely some steps
		for (int i = n - tailSteps; i < n; i++){
			if (!retainsStep( record, i )){
				fail( "step " + i + " is in a " + n + "-step episode's tail but was not retained" );
				return;
			}
		}
	}

	/**
	 * The wire round trip must be exact.
	 *
	 * The worker computes advantages and the trainer receives them; nothing recomputes them on the far
	 * side. That makes this frame the only place those numbers can be lost, and a float field dropped or
	 * transposed would not fail here - it would arrive as a plausible-looking advantage and train a
	 * policy on noise.
	 */
	private static void checkTransitionCodecRoundTrip(){
		EnvConfig config = new EnvConfig();
		int gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;

		//the LSTM is 128 wide, and both halves of its state travel: [hidden | cell]
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

		//values chosen to be exactly representable, so a mismatch is a dropped or transposed field
		//rather than a rounding difference
		sent.liveHead = Policy.HEAD_SLOT;
		sent.actionIndex = 11;
		sent.slotIndex = 4;
		sent.oldLogProbability = -1.25f;
		sent.advantage = 0.5f;
		sent.returnValue = -2.5f;
		sent.terminal = true;

		//distinctly non-zero, so a dropped or zero-filled state field is visible rather than equal
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

	/**
	 * The return value must report the surrogate's clip, not the gradient it happened to produce.
	 *
	 * {@link Policy#accumulatePolicyGradient} computed {@code clipBinding} and threw it away, so
	 * {@code PPO} counted {@code |advantage| > clipEpsilon} instead. After normalisation advantages have
	 * unit variance, so that count is {@code P(|N(0,1)| > 0.2)} = 0.8415 regardless of the policy — a
	 * constant wearing the label of a measurement. It is the single number that says the policy is
	 * moving too far per update, and it could not be read.
	 */
	private static void checkClipFractionIsNotAdvantageMagnitude(){
		float epsilon = 0.2f;

		//ratio 1.0, the policy has not moved this sample at all: must not report binding
		if (clipBindingForRatio( 1f, epsilon )) fail( "ratio 1.0 reported as clipped" );

		//ratio 1.1 sits inside [0.8, 1.2], so the min() selects the unclipped branch
		if (clipBindingForRatio( 1.1f, epsilon )) fail( "ratio 1.1 reported as clipped" );

		//just outside each bound, which is where the clip must start to bind
		if (!clipBindingForRatio( 1.25f, epsilon )) fail( "ratio 1.25 not reported as clipped" );
		if (!clipBindingForRatio( 0.75f, epsilon )) fail( "ratio 0.75 not reported as clipped" );

		//the old expression's value on the same distribution, to pin the magnitude of the regression
		Random rng = new Random( 4242L );
		int n = 20000;
		int advantageCount = 0;
		for (int i = 0; i < n; i++) if (Math.abs( rng.nextGaussian() ) > epsilon ) advantageCount++;
		double constant = (double) advantageCount / n;

		//the real quantity varies with how far the policy moved, and must be able to be near 0
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

	/**
	 * Drives {@link Policy#accumulatePolicyGradient} for a requested probability ratio and reports
	 * whether the clip bound it.
	 *
	 * The ratio is set by making the behaviour policy's recorded log-probability disagree with the
	 * current one by {@code log(r)}, so the call under test is the real one rather than a restatement
	 * of it.
	 */
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

	// --------------------------------------------------------------------------- helpers

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

	/** One step held in a rolling tail: the index it belongs to, and its transition. */
	private static class Tail {
		final int step;
		final Transition transition;

		Tail( int step, Transition transition ){
			this.step = step;
			this.transition = transition;
		}
	}
}