package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.ChunkReservoir;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerPool.WorkerHandle;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TrainerWorkers {

	private final EnvConfig config;
	private final PPO ppo;
	private final WorkerPool pool = new WorkerPool();

	private WorkerPool.ParamWriter paramWriter = out -> {};

	public TrainerWorkers( EnvConfig config, PPO ppo ){
		this.config = config;
		this.ppo = ppo;
	}

	public static class Job {
		final WorkerHandle worker;
		final String seed;
		final HeroClass heroClass;
		final boolean wantReplay;

		Job( WorkerHandle worker, String seed, HeroClass heroClass, boolean wantReplay ){
			this.worker = worker;
			this.seed = seed;
			this.heroClass = heroClass;
			this.wantReplay = wantReplay;
		}
	}

	public void launch( int count, String javaHome, String classpath, WorkerPool.ParamWriter params )
			throws IOException {
		this.paramWriter = params;
		pool.launch( count, javaHome, classpath, params );
	}

	public void shutdown(){
		pool.shutdown();
	}

	public int size(){
		return pool.size();
	}

	public List<WorkerHandle> workers(){
		return pool.workers();
	}

	public void stallTimeoutMs( long ms ){
		pool.stallTimeoutMs( ms );
	}

	public long stallTimeoutMs(){
		return pool.stallTimeoutMs();
	}

	public void startWatchdog(){
		pool.startWatchdog();
	}

	public Job job( WorkerHandle worker, String seed, HeroClass heroClass, boolean wantReplay ){
		return new Job( worker, seed, heroClass, wantReplay );
	}

	public List<Episode> dispatch( List<Job> jobs ) throws IOException {
		Map<WorkerHandle, List<Job>> byWorker = new LinkedHashMap<>();
		for (Job job : jobs){
			byWorker.computeIfAbsent( job.worker, k -> new ArrayList<>() ).add( job );
		}

		List<Thread> threads = new ArrayList<>();
		List<List<Episode>> results = new ArrayList<>();
		List<IOException> failures = Collections.synchronizedList( new ArrayList<>() );

		for (Map.Entry<WorkerHandle, List<Job>> entry : byWorker.entrySet()){
			final WorkerHandle worker = entry.getKey();
			final List<Job> mine = entry.getValue();
			final List<Episode> collected = new ArrayList<>();

			Thread thread = new Thread( () -> {
				try {
					collected.addAll( runOn( worker, mine ) );
				} catch (IOException e){
					failures.add( e );
				}
			}, "dispatch-" + worker.name );

			threads.add( thread );
			results.add( collected );
			thread.start();
		}

		InterruptedException interrupted = null;
		for (Thread thread : threads){
			try {
				thread.join();
			} catch (InterruptedException e){
				interrupted = e;
			}
		}
		if (interrupted != null) Thread.currentThread().interrupt();

		if (!failures.isEmpty()) throw failures.get( 0 );

		List<Episode> episodes = new ArrayList<>();
		for (List<Episode> collected : results) episodes.addAll( collected );
		return episodes;
	}

	private List<Episode> runOn( WorkerHandle worker, List<Job> jobs ) throws IOException {
		List<Episode> episodes = new ArrayList<>();

		DataOutputStream out = worker.dataOut;
		DataInputStream in = worker.dataIn;

		for (Job job : jobs){
			out.writeInt( Protocol.MSG_EPISODE );
			out.writeUTF( job.seed );
			out.writeUTF( job.heroClass.name() );
			out.writeBoolean( job.wantReplay );
			out.flush();

			int reply = in.readInt();
			if (reply != Protocol.MSG_EPISODE){
				throw new IOException( worker.name + " replied to an episode request with message "
						+ reply );
			}

			Episode episode = Episode.read( in );
			episode.seed = job.seed;
			episode.heroClass = job.heroClass.name();
			episode.workerName = worker.name;

			int steps = in.readInt();

			if (job.wantReplay) episode.replay = ReplayCodec.read( in, steps );

			episode.transitions = Episode.readTransitions( in, config, ppo.network.stateSize() );

			pool.progress();

			admit( episode );

			episodes.add( episode );
		}

		return episodes;
	}


	private ChunkReservoir reservoir;


	public void reservoir( ChunkReservoir reservoir ){
		this.reservoir = reservoir;
	}


	public int reservoirOverflow(){
		return reservoir == null ? 0 : (int) Math.min( Integer.MAX_VALUE, reservoir.dropped() );
	}


	private void admit( Episode episode ){
		if (reservoir == null || episode.transitions == null) return;

		reservoir.offer( episode.transitions );
		episode.transitions = null;
		if (episode.replay != null) return;
	}


	public void flushReservoir(){
		if (reservoir == null) return;
		reservoir.reset();
	}

	public void pushWeights() throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		DataOutputStream tmp = new DataOutputStream( buffer );
		paramWriter.write( tmp );
		tmp.flush();
		final byte[] payload = buffer.toByteArray();

		List<Thread> threads = new ArrayList<>();
		List<IOException> failures = Collections.synchronizedList( new ArrayList<>() );

		for (final WorkerHandle worker : pool.workers()){
			Thread thread = new Thread( () -> {
				try {
					writeFully( worker.dataOut, worker.name, payload );
					worker.dataOut.flush();
					int reply = worker.dataIn.readInt();
					if (reply != Protocol.MSG_PARAMS){
						throw new IOException( worker.name + " rejected a policy push, replied with "
								+ "message " + reply );
					}
				} catch (IOException e){
					failures.add( e );
				}
			}, "push-" + worker.name );
			threads.add( thread );
			thread.start();
		}

		InterruptedException interrupted = null;
		for (Thread thread : threads){
			try {
				thread.join();
			} catch (InterruptedException e){
				interrupted = e;
			}
		}
		if (interrupted != null) Thread.currentThread().interrupt();
		if (!failures.isEmpty()) throw failures.get( 0 );

		pool.progress();
	}


	public static int pushChunkBytes = 32 * 1024;

	public static int pushRetries = 8;

	private static final long PUSH_BACKOFF_MAX_MS = 1000L;

	public static void writeFully( DataOutputStream out, String name, byte[] payload ) throws IOException {
		int sent = 0;
		while (sent < payload.length){
			int end = Math.min( payload.length, sent + pushChunkBytes );
			boolean written = false;
			IOException last = null;
			for (int attempt = 0; attempt < pushRetries && !written; attempt++){
				if (attempt > 0){

					long wait = Math.min( PUSH_BACKOFF_MAX_MS, 50L * attempt * attempt );
					try {
						Thread.sleep( wait );
					} catch (InterruptedException e){
						Thread.currentThread().interrupt();
						throw new IOException( name + " was interrupted pushing weights", e );
					}
				}
				try {
					out.write( payload, sent, end - sent );
					written = true;
				} catch (IOException e){
					last = e;
				}
			}
			if (!written){
throw new IOException( name + " refused " + (end - sent )
					+ " bytes of a policy push at offset " + sent + " after " + pushRetries
					+ " attempts", last );
			}
			sent = end;
		}
	}
}