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

	private static final int CHECKS = 4;

	private static final float TOLERANCE = 1e-3f;

	private static final long SEED = 20261007L;

	private static final String[] METRICS = { "policy loss", "value loss", "entropy", "KL", "clip fraction" };

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		EnvConfig config = smallConfig();

		checkThreadCountDoesNotChangeTheUpdate( config, 2 );
		checkThreadCountDoesNotChangeTheUpdate( config, 4 );
		checkShardsOwnTheirOwnNetwork( config );
		checkAFailingShardFailsTheUpdate( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     parallel update: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  parallel update: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkThreadCountDoesNotChangeTheUpdate( EnvConfig config, int threads ){
		Network.Layer[] before = new PPO( config, new Random( SEED ) ).network.layers();
		Run serial = update( config, 1 );
		Run parallel = update( config, threads );

		if (parallel.shards < 2){
			fail( threads + " threads built " + parallel.shards + " shard networks, so ShardedUpdate.run never"
					+ " ran and the comparison is serial against serial" );
			return;
		}

		double worst = 0;
		String where = "";
		for (int i = 0; i < before.length; i++){
			float[][] b = { before[ i ].weights, before[ i ].bias };
			float[][] x = { serial.layers[ i ].weights, serial.layers[ i ].bias };
			float[][] y = { parallel.layers[ i ].weights, parallel.layers[ i ].bias };
			for (int part = 0; part < 2; part++){
				double norm = 0;
				for (int k = 0; k < b[ part ].length; k++){
					double d = x[ part ][ k ] - b[ part ][ k ];
					norm += d * d;
				}
				norm = Math.max( 1e-12, Math.sqrt( norm ) );
				for (int k = 0; k < b[ part ].length; k++){
					double rel = Math.abs( x[ part ][ k ] - y[ part ][ k ] ) / norm;
					if (rel > worst){
						worst = rel;
						where = before[ i ].name + ( part == 0 ? " weight " : " bias " ) + k;
					}
				}
			}
		}

		if (worst > TOLERANCE){
			fail( "an update with " + threads + " threads moved the parameters differently from one thread:"
					+ " worst difference " + worst + " of the serial step's norm at " + where
					+ ", tolerance " + TOLERANCE + ". The answer must depend only on the samples." );
			return;
		}

		for (int m = 0; m < METRICS.length; m++){
			float a = serial.metrics[ m ];
			float b = parallel.metrics[ m ];
			if (Math.abs( a - b ) > TOLERANCE * Math.max( 1f, Math.abs( b ))){
				fail( threads + " threads report " + METRICS[ m ] + " " + b + " and 1 thread reports " + a
						+ " for the same update. The two paths have to average the same way." );
				return;
			}
		}
		if (Math.abs( serial.metrics[ 0 ] ) < 1e-4f){
			fail( "the reported policy loss is " + serial.metrics[ 0 ] + " over a buffer whose ratios are not"
					+ " 1, so it is not the clipped surrogate: -advantage averages to zero after"
					+ " normalisation." );
			return;
		}

		System.out.println( "  " + threads + " threads (" + parallel.shards + " shards) and 1 thread move the"
				+ " parameters alike: worst difference " + worst + " of the step's norm; policy loss "
				+ serial.metrics[ 0 ] + ", entropy " + serial.metrics[ 2 ] + " on both" );
	}

	private static class Run {
		Network.Layer[] layers;
		int shards;
		float[] metrics;
	}

	private static Run update( EnvConfig config, int threads ){
		PPO ppo = new PPO( config, new Random( SEED ) );
		ppo.threads = threads;
		ppo.epochs = 2;
		ppo.minibatchSize = 16;
		ppo.buffer().addAll( ParallelFixtures.batch( ppo.network, config, 64, 4242L ) );
		try {
			ppo.update();
			Run r = new Run();
			r.layers = ppo.network.layers();
			r.shards = ppo.updateShardNetworks().size();
			r.metrics = new float[]{ ppo.lastPolicyLoss, ppo.lastValueLoss, ppo.lastEntropy,
					ppo.lastKLDivergence, ppo.lastClipFraction };
			return r;
		} finally {
			ppo.close();
		}
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

	private static void checkAFailingShardFailsTheUpdate( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( SEED ) );
		ppo.threads = 4;
		ppo.epochs = 1;
		ppo.minibatchSize = 32;
		ppo.buffer().addAll( ParallelFixtures.batch( ppo.network, config, 32, 4242L ) );
		ppo.buffer().get( 5 ).inventory = null;

		java.util.concurrent.ExecutorService runner = java.util.concurrent.Executors.newSingleThreadExecutor( r -> {
			Thread t = new Thread( r, "parallelcheck" );
			t.setDaemon( true );
			return t;
		} );
		try {
			runner.submit( ppo::update ).get( 30, java.util.concurrent.TimeUnit.SECONDS );
			fail( "an update whose shard threw returned normally, so the failure was swallowed" );
		} catch (java.util.concurrent.ExecutionException e){
			System.out.println( "  a shard that throws fails the update: " + e.getCause() );
		} catch (java.util.concurrent.TimeoutException e){
			fail( "an update whose shard threw was still waiting after 30s: the shard never counted down,"
					+ " so the trainer hangs forever on one bad transition" );
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			fail( "interrupted while waiting for the failing update" );
		} finally {
			runner.shutdownNow();
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
