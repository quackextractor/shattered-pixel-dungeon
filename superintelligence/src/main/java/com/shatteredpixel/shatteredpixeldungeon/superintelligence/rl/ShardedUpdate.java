package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One minibatch processed across N threads, with their gradients reduced before the Adam step.
 *
 * <p>The threading and reduction half of {@link PPO#update}. It lives apart because it is a separate
 * responsibility with a separate failure mode - it is the only place in the framework that touches
 * threads the trainer does not own - and because the learner it drives should be readable without
 * first reading a thread pool.
 *
 * <p><b>What it owns.</b> A fixed pool, and one {@link Shard} per thread holding a copy of the parameters
 * and its own scratch. Nothing else in the framework allocates per-thread state.
 *
 * <p><b>What it deliberately does not own.</b> The per-sample forward and backward. Those stay in
 * {@link PPO#oneSample}, and this calls that method rather than a copy of it - the alternative is a
 * serial and a parallel implementation of the same objective, which is how two implementations come to
 * compute different things while both look correct. That duplication already happened once in this
 * file, when the two paths each grew their own copy of the scale-clip-step tail.
 *
 * <p><b>Why a pool rather than threads per minibatch.</b> There are {@code n / minibatchSize}
 * minibatches per epoch and each is a barrier - nothing can proceed until every thread's gradients are
 * reduced and one Adam step has run. Handing minibatches to a fixed pool avoids creating
 * {@code epochs * n / minibatchSize} threads, which at 2 epochs over 2400 samples is 150 threads per
 * update.
 *
 * <p><b>Why per-thread networks rather than shared tensors.</b> A thread's network is a copy of the
 * parameters, so no thread can observe another's writes even if the reduction is wrong. The cost is one
 * 14.3 MB copy per minibatch, about 2 ms against 32 samples of forward and backward at 11.3 ms each -
 * 0.5%. Sharing the tensors would save that and reintroduce exactly the aliasing this avoids.
 *
 * <p><b>Correctness depends on a fix made elsewhere, and it is worth naming.</b> Before each sample came
 * to restore its own recurrent state, sample <i>i</i>'s forward depended on sample <i>i-1</i>'s leftover
 * {@code h}/{@code c}, so the samples were coupled and no split across threads could reproduce the serial
 * result. That was the real reason the parallel update was "not actionable" - not a threading problem.
 */
final class ShardedUpdate {

	private final PPO ppo;

	private final List<Shard> shards = new ArrayList<>();

	private ExecutorService pool;

	private int threadCount = 1;

	ShardedUpdate( PPO ppo ){
		this.ppo = ppo;
	}

	// --------------------------------------------------------------------------- pool

	/**
	 * Resolves the thread count and creates or tears down the pool to match.
	 *
	 * <p>One thread is the untouched serial path, not a one-threaded pool: the parameter copies and the
	 * gradient reduction would both be pure overhead, and the serial path is the reference
	 * {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ParallelCheck} compares
	 * against, so it has to stay exactly what it was.
	 */
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
				//daemon, so a pool left open cannot hold the JVM up after a run
				Thread t = new Thread( r, "ppo-update" );
				t.setDaemon( true );
				return t;
			} );
		}
		return threadCount;
	}

	/** Releases the pool and the per-thread networks. Safe when there was none. */
	void close(){
		if (pool != null){
			pool.shutdown();
			pool = null;
		}
		shards.clear();
	}

	// --------------------------------------------------------------------------- update

	/**
	 * Runs one minibatch across the pool and reduces the results into {@code network}.
	 *
	 * <p>Scales and clips before returning, exactly where the serial path does, so that the number
	 * reported is the norm of the gradient that was actually applied.
	 */
	PPO.Minibatch run( int from, int to ){
		int count = to - from;

		//one shard per thread, contiguous, so each thread's range is a slice and not a stride
		int n = Math.min( ppo.threads, count );
		if (n <= 1 || pool == null){
			return null;
		}

		network().zeroGrad();

		while (shards.size() < n) shards.add( new Shard( ppo.network ) );

		float[] policy = new float[ n ];
		float[] value = new float[ n ];
		float[] entropy = new float[ n ];
		float[] kl = new float[ n ];
		float[] clipped = new float[ n ];

		//The shards are created here, on this thread, before anything is dispatched. Creating them
		//inside the tasks would race on the shared list - two threads both seeing size 1 and both
		//appending at index 1, which is precisely the class of bug parallelcheck exists to catch, and
		//would have been caught by it.
		CountDownLatch ready = new CountDownLatch( n );
		for (int s = 0; s < n; s++){
			final int shard = s;
			final int lo = from + (int) ((long) s * count / n );
			final int hi = from + (int) ((long) ( s + 1 ) * count / n );

			pool.execute( () -> {
				Shard w = shards.get( shard );
				w.network.copyParametersFrom( ppo.network );
				w.network.clearGradients();

				float p = 0, v = 0, e = 0, k = 0, c = 0;
				for (int i = lo; i < hi; i++){
					PPO.Stats s2 = ppo.oneSample( w.network, ppo.buffer().get( i ), w.grid,
							w.slotProbs, w.targetProbs, w.actionProbs,
							w.slotGrad, w.targetGrad, w.actionGrad );
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
				ready.countDown();
			} );
		}

		//wait for every shard's arithmetic. The reduction below is the barrier's other half: it reads
		//gradients the workers are still writing, so the join cannot be skipped.
		await( ready );

		PPO.Minibatch result = new PPO.Minibatch();
		for (int s = 0; s < n; s++){
			shards.get( s ).network.addGradientsTo( network() );
			result.policyLoss += policy[ s ];
			result.valueLoss += value[ s ];
			result.entropy += entropy[ s ];
			result.kl += kl[ s ];
			result.clipped += clipped[ s ];
		}

		network().scaleGradients( 1f / count );
		result.gradNorm = network().clipGradients( network().gradClip );
		if (result.gradNorm > network().gradClip) result.gradClipped = 1;

		return result;
	}

	private Network network(){
		return ppo.network;
	}

	private static void await( CountDownLatch latch ){
		try {
			latch.await();
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			throw new IllegalStateException( "interrupted while waiting for update shards", e );
		}
	}

	/**
	 * Per-epoch scratch a parallel shard owns.
	 *
	 * <p>Sized from the network rather than from a config, because they are the same numbers by
	 * construction and a second derivation of "how wide is the action head" would be a second thing to
	 * keep in step.
	 */
	private static class Shard {
		final Network network;
		final float[] grid;
		final float[] slotProbs;
		final float[] targetProbs;
		final float[] actionProbs;
		final float[] slotGrad;
		final float[] targetGrad;
		final float[] actionGrad;

		Shard( Network network ){
			this.network = network;
			this.grid = new float[ network.gridLength() ];
			this.slotProbs = new float[ network.slotCount() ];
			this.targetProbs = new float[ network.targetCount() ];
			this.actionProbs = new float[ network.actionCount() ];
			this.slotGrad = new float[ network.slotCount() ];
			this.targetGrad = new float[ network.targetCount() ];
			this.actionGrad = new float[ network.actionCount() ];
		}
	}
}