package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.StepCache;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The recurrent gradient has to cross a timestep boundary, and the critic has to be asked for a number
 * it can represent.
 *
 * <pre>gradle :superintelligence:bpttcheck</pre>
 *
 * <p>Two faults that no loss curve names. The update backpropagated each stored sample in isolation with
 * {@code dPrev} and {@code dCell} cleared between steps, so nothing crossed a timestep and
 * {@code LSTM.W_hh} received no gradient at all for holding information forward - which in a partially
 * observed grid game is the entire reason the recurrent layer is there. And the critic was asked to fit
 * raw returns in the thousands while sharing its trunk with the actor, so {@code valueLoss} ran to
 * 12,432-31,323 and the shared gradient it produced was then squashed by global clipping, taking the
 * policy's update with it.
 *
 * <p>The first case here is the decisive one and it is constructed rather than compared: the loss is
 * <b>zero at the first step and non-zero at the second</b>, so the sum of the isolated steps is exactly
 * the isolated gradient of the second. Under a length-one truncation the chunk produces precisely that
 * sum. Under a through-time gradient it produces strictly more, because the second step's gradient flows
 * back into the first step's LSTM. Reverting the accumulation makes the difference zero and the check
 * red - which a first attempt at this assertion, comparing two gradient <em>norms</em>, was measured to
 * let through.
 */
public class BpttCheck {

	private static final int CHECKS = 7;

	/** Index of {@code memory.gW} in {@link Network#gradientTensors()}. */
	private static final int LSTM_GRADIENT = 4;

	private static final List<String> failures = new ArrayList<>();

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		checkTheChunkIsNotTheSumOfItsSteps( config );
		checkALossOnTheFirstStepReachesTheRecurrentWeights( config );
		checkASingleStepChunkIsTheOldPath( config );
		checkOnlyContiguousStepsAreChained( config );
		checkSyntheticStepsAreNeverChained( config );
		checkValueTargetsAreNormalised( config );
		checkNormalisationIsOffWhenAsked( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     recurrent gradient: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  recurrent gradient: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * The chunk's gradient is not the sum of its steps' gradients.
	 *
	 * <p>Constructed so the answer is forced: the loss sits on step 1 only, and step 0's local
	 * contribution is zeroed in every part of the objective - advantage, entropy and critic residual - so
	 * the sum of the isolated steps is exactly step 1's isolated gradient.
	 */
	private static void checkTheChunkIsNotTheSumOfItsSteps( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 4242L ));
		ppo.entropyCoeff = 0f;
		ppo.chunkLength = 4;

		List< Transition > chunk = chunkOf( config, ppo, 2 );
		chunk.get( 0 ).returnValue = 0.25f;
		chunk.get( 1 ).returnValue = 1f;

		float[] throughTime = recurrentGradient( ppo, chunk );

		float[] sum = new float[ throughTime.length ];
		for (int i = 0; i < chunk.size(); i++){
			ppo.network.clearGradients();
			oneSample( ppo, ppo.network, chunk.get( i ) );
			float[] alone = ppo.network.gradientTensors()[ LSTM_GRADIENT ];
			for (int k = 0; k < sum.length; k++) sum[ k ] += alone[ k ];
		}

		double difference = relativeDifference( throughTime, sum );

		if (difference < 1e-6){
			failures.add( "a two-step chunk gives the LSTM weights exactly the sum of its two steps'"
					+ " own gradients (relative difference " + fmt( difference ) + ", L2 "
					+ fmt( norm( throughTime )) + " against " + fmt( norm( sum )) + "), so no gradient"
					+ " crossed the timestep boundary. That is a length-one truncation, and it is the"
					+ " reason the recurrent layer could learn nothing the trunk could not." );
			return;
		}

		System.out.println( "  a two-step chunk differs from the sum of its steps by "
				+ fmt( difference * 100 ) + "% of its norm on the LSTM weights" );
	}

	/**
	 * A loss on the first step alone still reaches the recurrent weights.
	 *
	 * <p>The complement of the case above, and what makes that comparison a measurement rather than a
	 * tautology: the recurrent gradient is non-zero from either end, so a difference cannot be explained
	 * by one of the two steps contributing nothing at all.
	 */
	private static void checkALossOnTheFirstStepReachesTheRecurrentWeights( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 4242L ));
		ppo.entropyCoeff = 0f;
		ppo.chunkLength = 4;

		List< Transition > chunk = chunkOf( config, ppo, 2 );
		chunk.get( 0 ).returnValue = 1f;
		chunk.get( 1 ).returnValue = 0f;

		double firstStep = norm( recurrentGradient( ppo, chunk ));

		if (firstStep <= 0){
			failures.add( "a loss on the first step of a two-step chunk gave the LSTM weights no gradient"
					+ " at all, so the recurrent layer is not trained even by a step's own loss." );
			return;
		}

		System.out.println( "  a loss on the first step alone gives the LSTM weights gradient "
				+ fmt( firstStep ));
	}

	/**
	 * A one-step chunk must produce what the old per-step path produced.
	 *
	 * <p>The equivalence the whole change rests on: a single sample is not a special case, it is a chunk
	 * of length one. If this drifts, every number measured against the old path is measuring a different
	 * update.
	 */
	private static void checkASingleStepChunkIsTheOldPath( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 99L ));

		List< Transition > batch = batchOf( config, ppo, 8 );
		for (Transition t : batch){
			t.episodeId = -1;
			t.stepIndex = -1;
		}

		float[] viaChunks = updateAndKeep( ppo, batch );
		float[] viaSamples = updateAndKeep( new PPO( config, new Random( 99L )), batch );

		double difference = relativeDifference( viaChunks, viaSamples );

		if (difference > 1e-4){
			failures.add( "updating the same eight unsequenced transitions through the chunked path and"
					+ " through single-sample chunks differ by " + fmt( difference )
					+ ", so a single sample is not being treated as a chunk of length one. Tolerance is"
					+ " 1e-4 relative to the tensor's own L2 norm." );
			return;
		}

		System.out.println( "  a single-step chunk and the old per-step path agree to " + fmt( difference ));
	}

	/**
	 * Only strictly consecutive steps of one episode may be chained.
	 *
	 * <p>A gap is not a detail. Backpropagating across a step the agent never saw would carry a gradient
	 * through a state that never happened, and uniform sampling drops steps routinely, so most chunks in a
	 * real batch would be wrong if a gap did not split.
	 */
	private static void checkOnlyContiguousStepsAreChained( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 7L ));

		ppo.buffer().addAll( Arrays.asList(
				transition( config, ppo, 0, 0 ),
				transition( config, ppo, 0, 1 ),
				transition( config, ppo, 0, 2 ),
				transition( config, ppo, 0, 9 ),
				transition( config, ppo, 1, 0 ) ));

		List< List< Transition > > chunks = chunks( ppo );

		if (chunks.size() != 3){
			failures.add( "five steps - three contiguous, then one nine steps later, then one from another"
					+ " episode - chunked into " + chunks.size() + " run(s) rather than 3, so a gap is"
					+ " being chained across." );
			return;
		}

		if (chunks.get( 0 ).size() != 3){
			failures.add( "the run shapes are " + shapes( chunks ) + " rather than [3, 1, 1]: a gap or a"
					+ " change of episode has to start a new run." );
			return;
		}

		System.out.println( "  a gap or a new episode starts a new run: " + shapes( chunks ));
	}

	/**
	 * A transition with no episode must never be chained to anything.
	 *
	 * <p>What a check's own synthetic transitions carry, and the reason they behave exactly as they did
	 * before rather than being sorted into a sequence nobody built.
	 */
	private static void checkSyntheticStepsAreNeverChained( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 11L ));

		ppo.buffer().addAll( Arrays.asList(
				transition( config, ppo, -1, -1 ),
				transition( config, ppo, -1, -1 ),
				transition( config, ppo, -1, -1 ) ));

		List< List< Transition > > chunks = chunks( ppo );

		if (chunks.size() != 3){
			failures.add( "three synthetic transitions chunked into " + chunks.size() + " run(s); they"
					+ " carry no episode, so each must stand alone." );
			return;
		}

		System.out.println( "  unsequenced steps each stand alone: " + shapes( chunks ));
	}

	/** The critic is asked for an O(1) target even when returns are in the thousands. */
	private static void checkValueTargetsAreNormalised( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 5L ));
		ppo.normaliseValues = true;

		List< Transition > batch = batchOf( config, ppo, 32 );
		for (int i = 0; i < batch.size(); i++){
			batch.get( i ).returnValue = (i % 2 == 0) ? -3000f : 1800f;
		}

		ppo.valueNorm.update( batch );

		double worst = 0;
		for (Transition t : batch) worst = Math.max( worst, Math.abs( ppo.valueTarget( t )));

		if (worst < 0.25 || worst > 2f){
			failures.add( "a batch whose returns are -3000 and +1800 normalises to at most " + worst
					+ ", outside [0.25, 2]. The critic's target has to be of order one." );
			return;
		}

		System.out.println( "  returns of -3000 and +1800 normalise to at most " + fmt( worst ));
	}

	/** And normalisation is genuinely off when asked, which is what makes the case above a comparison. */
	private static void checkNormalisationIsOffWhenAsked( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 5L ));
		ppo.normaliseValues = false;

		List< Transition > batch = batchOf( config, ppo, 8 );
		for (Transition t : batch) t.returnValue = -3000f;

		ppo.valueNorm.update( batch );

		if (Math.abs( ppo.valueTarget( batch.get( 0 )) + 3000f ) > 1f){
			failures.add( "with normaliseValues=false the target is " + ppo.valueTarget( batch.get( 0 ))
					+ ", so the switch is not reachable and the normalisation case proves nothing about"
					+ " which path an update takes." );
			return;
		}

		System.out.println( "  normaliseValues=false asks the critic for the raw return" );
	}

	/** Runs an update over {@code batch} and returns the LSTM weight gradient it produced. */
	private static float[] updateAndKeep( PPO ppo, List< Transition > batch ){
		ppo.threads = 1;
		ppo.epochs = 1;
		ppo.chunkLength = 16;
		ppo.addAll( batch );
		ppo.update();
		return ppo.network.gradientTensors()[ LSTM_GRADIENT ].clone();
	}

	/** The recurrent gradient for a chunk, every objective term zeroed except its returns. */
	private static float[] recurrentGradient( PPO ppo, List< Transition > chunk ){
		for (Transition t : chunk){
			t.oldLogProbability = 0f;
			t.advantage = 0f;
			t.terminal = false;
		}

		StepCache[] caches = new StepCache[ chunk.size() ];
		for (int i = 0; i < caches.length; i++) caches[ i ] = ppo.network.takeCache();

		oneChunk( ppo, ppo.network, chunk, caches );

		return ppo.network.gradientTensors()[ LSTM_GRADIENT ].clone();
	}

	/** {@code n} transitions from one episode, consecutive, each carrying the state it was decided in. */
	private static List< Transition > chunkOf( EnvConfig config, PPO ppo, int n ){
		List< Transition > out = new ArrayList<>();
		Random rng = new Random( 20261016L );

		ppo.network.resetState();

		for (int i = 0; i < n; i++){
Transition t = transition( config, ppo, 3, i );
			randomise( t, rng );
			ppo.network.saveState( t.recurrentState );
			ppo.network.forward( gridOf( t ), t.inventory, t.hero );
			t.oldLogProbability = 0f;
			t.advantage = 0f;
			t.returnValue = 0f;
			out.add( t );
		}

		return out;
	}

	/** {@code n} unsequenced transitions with random observations. */
	private static List< Transition > batchOf( EnvConfig config, PPO ppo, int n ){
		List< Transition > out = new ArrayList<>();
		Random rng = new Random( 3141592L );
		for (int i = 0; i < n; i++){
			Transition t = transition( config, ppo, 9, i );
			randomise( t, rng );
			out.add( t );
		}
		return out;
	}

	private static Transition transition( EnvConfig config, PPO ppo, int episode, int step ){
		int gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;
		int inventorySize = config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;

		Transition t = Transition.take( gridSize, inventorySize, HeroEncoder.FEATURES,
				Action.size(), config.maxSlots, ActionMapper.TARGET_COUNT, ppo.network.stateSize() );

		t.episodeId = episode;
		t.stepIndex = step;
		t.liveHead = Policy.HEAD_ACTION;
		t.actionIndex = 0;
		t.slotIndex = 0;
		for (int i = 0; i < t.actionMask.length; i++) t.actionMask[ i ] = 1f;
		for (int i = 0; i < t.slotMask.length; i++) t.slotMask[ i ] = 1f;
		for (int i = 0; i < t.targetMask.length; i++) t.targetMask[ i ] = 1f;
		return t;
	}

	private static void randomise( Transition t, Random rng ){
		for (int i = 0; i < t.inventory.length; i++) t.inventory[ i ] = rng.nextFloat();
		for (int i = 0; i < t.hero.length; i++) t.hero[ i ] = rng.nextFloat();
		for (int i = 0; i < t.grid.length; i++) t.grid[ i ] = (byte) (rng.nextInt( 3 ) == 0 ? 1 : 0);
		t.recurrentState[ 0 ] = 0.01f * (rng.nextInt( 20 ) - 10 );
	}

	private static float[] gridOf( Transition t ){
		float[] dst = new float[ t.grid.length ];
		t.unpackGrid( dst );
		return dst;
	}

	/** Calls {@code PPO.oneChunk} by its real signature, so a rename fails here rather than silently. */
	private static void oneChunk( PPO ppo, Network net, List< Transition > chunk, StepCache[] caches ){
		Method m = method( "oneChunk", Network.class, List.class, float[].class, float[].class,
				float[].class, float[].class, float[].class, float[].class, float[].class,
				StepCache[].class, int.class );

		invoke( m, "oneChunk", ppo, new Object[] { net, chunk, new float[ net.gridLength() ],
				new float[ net.slotCount() ], new float[ net.targetCount() ],
				new float[ net.actionCount() ], new float[ net.slotCount() ],
				new float[ net.targetCount() ], new float[ net.actionCount() ],
				caches, caches.length } );
	}

	/** Calls {@code PPO.oneSample} by its real signature. */
	private static void oneSample( PPO ppo, Network net, Transition t ){
Method m = method( "oneSample", Network.class, Transition.class, float[].class, float[].class,
				float[].class, float[].class, float[].class, float[].class, float[].class );

		invoke( m, "oneSample", ppo, new Object[] { net, t, new float[ net.gridLength() ],
				new float[ net.slotCount() ], new float[ net.targetCount() ],
				new float[ net.actionCount() ], new float[ net.slotCount() ],
				new float[ net.targetCount() ], new float[ net.actionCount() ] } );
	}

	private static Method method( String name, Class<?>... signature ){
		try {
			Method m = PPO.class.getDeclaredMethod( name, signature );
			m.setAccessible( true );
			return m;
		} catch (NoSuchMethodException e){
			throw new IllegalStateException( "PPO." + name + " is gone or renamed; this fixture is the"
					+ " only thing that knows its shape, so it fails here rather than silently testing"
					+ " something else", e );
		}
	}

	private static void invoke( Method m, String name, Object receiver, Object[] args ){
		try {
			m.invoke( receiver, args );
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( "cannot drive PPO." + name, e );
		}
	}

	/** Calls {@code PPO.chunks()} by its real signature. */
	private static List< List< Transition > > chunks( PPO ppo ){
		try {
			@SuppressWarnings("unchecked")
			List< List< Transition > > out =
					(List< List< Transition > >) method( "chunks" ).invoke( ppo );
			return out;
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( "cannot read PPO.chunks()", e );
		}
	}

	private static double norm( float[] v ){
		double sum = 0;
		for (float f : v) sum += (double) f * f;
		return Math.sqrt( sum );
	}

	private static double relativeDifference( float[] a, float[] b ){
		double num = 0, den = 0;
		for (int i = 0; i < a.length; i++){
			num += (a[ i ] - b[ i ]) * (double) (a[ i ] - b[ i ]);
			den += (double) b[ i ] * b[ i ];
		}
		if (den <= 0) return num <= 0 ? 0 : Double.MAX_VALUE;
		return Math.sqrt( num / den );
	}

	private static String shapes( List< List< Transition > > chunks ){
		StringBuilder sb = new StringBuilder( "[" );
		for (List< Transition > c : chunks ) sb.append( c.size() ).append( ", " );
		return sb.append( "]" ).toString();
	}

	private static String fmt( double v ){
		return String.format( "%.6g", v );
	}
}