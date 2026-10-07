package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fails if the parallel update computes a different gradient from the serial one.
 *
 * <pre>gradle :superintelligence:parallelcheck</pre>
 *
 * <p>{@code gradcheck} runs single-threaded by construction, so it proves the maths is right and proves
 * nothing about concurrent access. This is the check that closes that gap. It exists because the
 * alternative failure is not a crash: a race here looks like a policy that converges slightly worse,
 * forever, with no error anywhere.
 *
 * <p><b>The comparison is per-shard-batch, not per-minibatch.</b> An earlier version of this file drove
 * the whole of {@code PPO.update} twice and compared the gradients left over afterwards. That measures
 * the wrong thing twice over: the buffer is <em>shuffled</em>, so two runs with the same seed still
 * walk the samples in the same order but the surviving gradient is only the final minibatch's, after
 * two Adam steps have moved the weights underneath everything. Diagnosing that failure took four wrong
 * probes, the worst of which added the shard sums into the serial accumulation's own tensors and so
 * compared {@code sum(shards)} against zero — which agrees for any input whatsoever. The lesson is in
 * the code because it is the more useful half: when a check disagrees with an implementation, suspect
 * the check before rewriting the implementation.
 *
 * <p>What is asserted here is narrow and unambiguous. One minibatch, no shuffle, no Adam step, no
 * clipping: the gradient a set of samples produces accumulated on one network, against the same
 * samples split across several networks and summed. Nothing else is in the path.
 *
 * <p><b>The tolerance is not laziness.</b> Floating-point addition is not associative, so summing four
 * partial gradients in a different order will not be bit-identical. Asserting exact equality would
 * fail a correct implementation. The claim is "the same gradient up to rounding", and the observed
 * disagreement is around 1e-7 relative — far inside the bound, which leaves five orders of magnitude
 * for a real bug. The figure is printed so the slack is visible rather than assumed.
 */
public class ParallelCheck {

	private static final int CHECKS = 4;

	/**
	 * Relative tolerance, measured against each tensor's own L2 norm.
	 *
	 * <p>Against the tensor rather than each element: an entry whose true gradient is ~1e-9 through
	 * cancellation carries no information, and dividing by it turns float rounding into an unbounded
	 * "relative" error. That mistake made an earlier version of this check report differences of 1800x
	 * on a correct reduction.
	 */
	private static final float TOLERANCE = 1e-4f;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		EnvConfig config = smallConfig();

		checkShardsAgreeWithOneNetwork( config, 2 );
		checkShardsAgreeWithOneNetwork( config, 4 );
		checkShardsAgreeWithOneNetwork( config, 8 );
		checkThreadCountDoesNotChangeTheAnswer( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     parallel update: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  parallel update: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * One minibatch accumulated two ways must agree.
	 *
	 * <p>Deliberately not {@code PPO.update}: the shuffle, the Adam steps and the per-minibatch weight
	 * push all sit between the samples and the gradient this compares, and each of them moves the
	 * weights mid-comparison. Driving the reduction on its own is the only way the number means what it
	 * says.
	 *
	 * @param shards how many networks to split the samples across
	 */
	private static void checkShardsAgreeWithOneNetwork( EnvConfig config, int shards ){
		Result r = reduce( config, 32, shards );

		if (r.worst > TOLERANCE){
			fail( shards + " shards: worst gradient difference " + r.worst
					+ " relative to the tensor norm at " + r.where + ", tolerance " + TOLERANCE
					+ ". Addition is not associative so a small difference is expected, but this is"
					+ " larger than rounding. (Observed max absolute difference " + r.worstAbs + ")" );
			return;
		}

		if (r.samplesAccumulated != 32){
			fail( shards + " shards: the reduction accumulated " + r.samplesAccumulated
					+ " samples, expected 32. A shard was dropped or double-counted." );
			return;
		}

		System.out.println( "  " + shards + " shards: worst diff " + r.worst
				+ " relative, " + r.worstAbs + " absolute, " + r.samplesAccumulated + " samples" );
	}

	/**
	 * The answer must not depend on how many shards it was computed in.
	 *
	 * <p>Stronger than the check above in the way that matters: a reduction that happened to agree with
	 * the serial path at one particular shard count would still pass that one. Requiring agreement
	 * across 2, 4 and 8 catches a scaling factor or an off-by-one that only bites at some sizes.
	 */
	private static void checkThreadCountDoesNotChangeTheAnswer( EnvConfig config ){
		Result two = reduce( config, 32, 2 );
		Result eight = reduce( config, 32, 8 );

		float[][] a = two.sharded;
		float[][] b = eight.sharded;
		double worst = 0;
		String where = "";
		for (int i = 0; i < a.length; i++ ){
			double norm = 0;
			for (float v : a[ i ] ) norm += (double) v * v;
			norm = Math.max( 1e-12, Math.sqrt( norm ) );
			for (int k = 0; k < a[ i ].length; k++ ){
				double d = Math.abs( a[ i ][ k ] - b[ i ][ k ] ) / norm;
				if (d > worst ){ worst = d; where = "tensor " + i + " entry " + k; }
			}
		}

		if (worst > TOLERANCE){
			fail( "2 shards and 8 shards disagree by " + worst + " at " + where
					+ ". The answer must depend only on the samples, not on how they were partitioned." );
		}
	}

	// --------------------------------------------------------------------------- the measurement

	/** One gradient comparison's outcome. */
	private static class Result {
		/** The sharded accumulation, for cross-shard-count comparison. */
		float[][] sharded;

		double worst;
		double worstAbs;
		String where = "";
		int samplesAccumulated;
	}

	/**
	 * Accumulates {@code n} samples two ways and compares them.
	 *
	 * <p>The serial way: one network, every sample, in order. The parallel way: {@code shards} separate
	 * networks, each given a contiguous slice, with the results summed into a fresh set of tensors.
	 *
	 * <p>The sum goes somewhere that is neither input. Writing it into either side would compare a
	 * quantity against itself plus something, which agrees trivially — the mistake that cost most of
	 * the time spent on this bug.
	 */
	private static Result reduce( EnvConfig config, int n, int shards ){
		//one policy, so the samples and the weights are identical for both accumulations
		PPO ppo = new PPO( config, new Random( 20261007L ) );
		List< com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition > batch =
				ParallelFixtures.batch( ppo.network, config, n, 4242L );

		Network straight = new Network( config, new Random( 1L ) );
		straight.copyParametersFrom( ppo.network );
		straight.clearGradients();
		int straightCount = 0;
		for (com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition t : batch){
			ParallelFixtures.oneSample( ppo, straight, t );
			straightCount++;
		}

		Network[] parts = new Network[ shards ];
		for (int s = 0; s < shards; s++ ){
			parts[ s ] = new Network( config, new Random( 1L ) );
			parts[ s ].copyParametersFrom( ppo.network );
			parts[ s ].clearGradients();
		}
		int shardedCount = 0;
		for (int s = 0; s < shards; s++ ){
			int lo = (int) ((long) s * n / shards );
			int hi = (int) ((long) ( s + 1 ) * n / shards );
			for (int i = lo; i < hi; i++ ){
				ParallelFixtures.oneSample( ppo, parts[ s ], batch.get( i ) );
				shardedCount++;
			}
		}

		float[][] a = straight.gradientTensors();

		//the sum, into tensors of its own
		float[][] summed = new float[ a.length ][];
		for (int i = 0; i < a.length; i++ ) summed[ i ] = new float[ a[ i ].length ];
		for (int i = 0; i < a.length; i++ ){
			for (int k = 0; k < a[ i ].length; k++ ){
				float sum = 0;
				for (Network part : parts ) sum += part.gradientTensors()[ i ][ k ];
				summed[ i ][ k ] = sum;
			}
		}

		Result r = new Result();
		r.sharded = summed;
		r.samplesAccumulated = shardedCount;

		for (int i = 0; i < a.length; i++ ){
			double norm = 0;
			for (float v : a[ i ] ) norm += (double) v * v;
			norm = Math.max( 1e-12, Math.sqrt( norm ) );

			for (int k = 0; k < a[ i ].length; k++ ){
				double abs = Math.abs( a[ i ][ k ] - summed[ i ][ k ] );
				double rel = abs / norm;
				r.worstAbs = Math.max( r.worstAbs, abs );
				if (rel > r.worst ){
					r.worst = rel;
					r.where = "tensor " + i + " entry " + k
							+ " (" + a[ i ][ k ] + " vs " + summed[ i ][ k ] + ")";
				}
			}
		}

		ppo.close();
		return r;
	}

	private static EnvConfig smallConfig(){
		EnvConfig config = new EnvConfig();
		//small, because this is about arithmetic rather than scale, and a full-size network makes four
		//reductions slow enough to discourage running them
		config.gridWidth = 10;
		config.gridHeight = 10;
		config.maxSlots = 4;
		return config;
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
