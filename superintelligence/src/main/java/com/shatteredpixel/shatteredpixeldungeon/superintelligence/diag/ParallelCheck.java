package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class ParallelCheck {

	private static final int CHECKS = 5;

	private static final float TOLERANCE = 1e-4f;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		EnvConfig config = smallConfig();

		checkShardsAgreeWithOneNetwork( config, 2 );
		checkShardsAgreeWithOneNetwork( config, 4 );
		checkShardsAgreeWithOneNetwork( config, 8 );
		checkThreadCountDoesNotChangeTheAnswer( config );
		checkShardsOwnTheirOwnNetwork( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     parallel update: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  parallel update: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

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

	private static class Result {
		float[][] sharded;

		double worst;
		double worstAbs;
		String where = "";
		int samplesAccumulated;
	}

	private static Result reduce( EnvConfig config, int n, int shards ){

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

	private static void checkShardsOwnTheirOwnNetwork( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 20261013L ) );
		ppo.threads = 4;
		ppo.epochs = 1;
		ppo.minibatchSize = 32;
		ppo.buffer().addAll( ParallelFixtures.batch( ppo.network, config, 64, 20261013L ) );

		try {
			ppo.update();

			List< Network > shards = ppo.updateShardNetworks();
			if (shards.size() < 2){
				fail( "an update with --update-threads 4 built " + shards.size() + " shard networks, so the"
						+ " parallel path did not run at all and nothing here was tested." );
				return;
			}

			Set< Network > distinct = Collections.newSetFromMap( new IdentityHashMap<>() );
			distinct.addAll( shards );

			if (distinct.size() != shards.size()){
				fail( shards.size() + " shards hold " + distinct.size() + " distinct networks, so at least"
						+ " two update threads shared one Network - its parameters, its gradient"
						+ " accumulators and its forward scratch. A shard's clearGradients() then wipes"
						+ " the others' partial sums, and addGradientsTo adds the master's own gradient once"
						+ " per shard. (issues.md 25)" );
				return;
			}

			if (distinct.contains( ppo.network )){
				fail( "a shard holds the trainer's own Network, so every update thread was running on the"
						+ " master: shared parameters, shared gradients, shared layer scratch. The"
						+ " per-minibatch parameter copy the design describes is then a self-copy."
						+ " (issues.md 25)" );
				return;
			}

			System.out.println( "  " + shards.size() + " shards, " + distinct.size()
					+ " distinct networks, none the master's; entropy " + ppo.lastEntropy
					+ " (reported, not asserted - see the case's own comment)" );
		} finally {
			ppo.close();
		}
	}

	private static EnvConfig smallConfig(){
		EnvConfig config = new EnvConfig();

		config.gridWidth = 10;
		config.gridHeight = 10;
		config.maxSlots = 4;
		return config;
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
