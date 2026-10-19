package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


final class ShardedUpdate {

	private final List<Shard> shards = new ArrayList<>();

	private ExecutorService pool;

	private int threadCount = 1;


	int resolveThreads( int requested ){
		if (requested <= 1){
			threadCount = 1;
			if (pool != null){
				pool.shutdown();
				pool = null;
			}
			return threadCount;
		}

		int available = Runtime.getRuntime().availableProcessors();
		threadCount = Math.max( 1, Math.min( requested, available ) );

		if (pool == null){
			pool = Executors.newFixedThreadPool( threadCount, r -> {

				Thread t = new Thread( r, "ppo-update" );
				t.setDaemon( true );
				return t;
			} );
		}
		return threadCount;
	}


	void close(){
		if (pool != null){
			pool.shutdown();
			pool = null;
		}
		shards.clear();
	}


	List< Network > shardNetworks(){
		List< Network > out = new ArrayList<>( shards.size() );
		for (Shard shard : shards) out.add( shard.network );
		return out;
	}


	PPO.Minibatch run( PPO ppo, List< List< Transition > > chunks, int from, int to ){
		int count = to - from;

		int n = Math.min( ppo.threads, count );
		if (n <= 1 || pool == null){
			return null;
		}

		ppo.network.zeroGrad();

		while (shards.size() < n) shards.add( new Shard( ppo.config, new Random( 1L + shards.size() ),
				ppo.chunkLength ));

		float[] policy = new float[ n ];
		float[] value = new float[ n ];
		float[] entropy = new float[ n ];
		float[] kl = new float[ n ];
		float[] clipped = new float[ n ];

		CountDownLatch ready = new CountDownLatch( n );
		AtomicReference< Throwable > failure = new AtomicReference<>();
		for (int s = 0; s < n; s++){
			final int shard = s;
			final int lo = from + (int) ((long) s * count / n );
			final int hi = from + (int) ((long) ( s + 1 ) * count / n );

			pool.execute( () -> {
				try {
					Shard w = shards.get( shard );
					w.network.copyParametersFrom( ppo.network );
					w.network.clearGradients();

					float p = 0, v = 0, e = 0, k = 0, c = 0;
					for (int i = lo; i < hi; i++){
						PPO.Stats s2 = ppo.oneChunk( w.network, chunks.get( i ), w.grid,
								w.slotProbs, w.targetProbs, w.actionProbs,
								w.slotGrad, w.targetGrad, w.actionGrad, w.caches, w.maxChunk );
						p += s2.policyLoss;
						v += s2.valueLoss;
						e += s2.entropy;
						k += s2.kl;
						if (s2.clipBinding) c++;
					}

					policy[ shard ] = p;
					value[ shard ] = v;
					entropy[ shard ] = e;
					kl[ shard ] = k;
					clipped[ shard ] = c;
				} catch (Throwable t){
					failure.compareAndSet( null, t );
				} finally {
					ready.countDown();
				}
			} );
		}

		await( ready );
		if (failure.get() != null){
			throw new IllegalStateException( "an update shard failed", failure.get() );
		}

		PPO.Minibatch result = new PPO.Minibatch();
		for (int s = 0; s < n; s++){
			shards.get( s ).network.addGradientsTo( ppo.network );
			result.policyLoss += policy[ s ];
			result.valueLoss += value[ s ];
			result.entropy += entropy[ s ];
			result.kl += kl[ s ];
			result.clipped += clipped[ s ];
		}

		ppo.network.scaleGradients( 1f / count );
		result.gradNorm = ppo.network.clipGradients( ppo.network.gradClip );
		if (result.gradNorm > ppo.network.gradClip) result.gradClipped = 1;

		return result;
	}

	private static void await( CountDownLatch latch ){
		try {
			latch.await();
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			throw new IllegalStateException( "interrupted while waiting for update shards", e );
		}
	}


	private static class Shard {
		final Network network;
		final float[] grid;
		final float[] slotProbs;
		final float[] targetProbs;
		final float[] actionProbs;
		final float[] slotGrad;
		final float[] targetGrad;
		final float[] actionGrad;
		final StepCache[] caches;
		final int maxChunk;

		Shard( EnvConfig config, Random rng, int maxChunk ){
			this.network = new Network( config, rng );
			this.grid = new float[ network.gridLength() ];
			this.slotProbs = new float[ network.slotCount() ];
			this.targetProbs = new float[ network.targetCount() ];
			this.actionProbs = new float[ network.actionCount() ];
			this.slotGrad = new float[ network.slotCount() ];
			this.targetGrad = new float[ network.targetCount() ];
			this.actionGrad = new float[ network.actionCount() ];
			this.maxChunk = Math.max( 1, maxChunk );
			this.caches = new StepCache[ this.maxChunk ];
			for (int i = 0; i < this.maxChunk; i++) this.caches[ i ] = network.takeCache();
		}
	}
}