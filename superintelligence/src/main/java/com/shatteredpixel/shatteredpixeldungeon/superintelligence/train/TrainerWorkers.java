package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerPool.WorkerHandle;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class TrainerWorkers implements JobRunner {

	private final EnvConfig config;
	private final PPO ppo;
	private final WorkerPool pool = new WorkerPool();

	private WorkerPool.ParamWriter paramWriter = out -> {};

	private final Map<String, Double> lastCpuByWorker = new java.util.HashMap<>();

	private com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.BatchedInference inference;

	public TrainerWorkers( EnvConfig config, PPO ppo ){
		this.config = config;
		this.ppo = ppo;
	}

	public String batchInference(){
		try {
			inference = new com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.BatchedInference( ppo.network );
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared.InferenceBroker.install( inference );
			return "batched on " + inference.deviceName() + " (OpenCL)";
		} catch (RuntimeException | LinkageError e){
			return "per worker on the cpu (no OpenCL GPU: " + e.getMessage() + ")";
		}
	}

	public static class Job {
		public final String seed;
		public final HeroClass heroClass;
		public final boolean wantReplay;
		public final long policySeed;

		public Job( String seed, HeroClass heroClass, boolean wantReplay, long policySeed ){
			this.seed = seed;
			this.heroClass = heroClass;
			this.wantReplay = wantReplay;
			this.policySeed = policySeed;
		}
	}

	public void launch( int count, String javaHome, String classpath, WorkerPool.ParamWriter params )
			throws IOException {
		this.paramWriter = params;
		if (inference != null) inference.load( ppo.network );
		pool.launch( count, javaHome, classpath, params );
	}

	public void shutdown(){
		pool.shutdown();
	}

	@Override
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

	public void beforeHalt( Runnable action ){
		pool.beforeHalt( action );
	}

	public void startWatchdog(){
		pool.startWatchdog();
	}

	public void whileBusy( Runnable work ){
		pool.trainerBusy( true );
		try {
			work.run();
		} finally {
			pool.trainerBusy( false );
		}
	}

	public Job job( String seed, HeroClass heroClass, boolean wantReplay, long policySeed ){
		return new Job( seed, heroClass, wantReplay, policySeed );
	}

	public List<Episode> dispatch( List<Job> jobs ) throws IOException {
		Episode[] results = new Episode[ jobs.size() ];
		AtomicInteger next = new AtomicInteger();

		List<Thread> threads = new ArrayList<>();
		List<IOException> failures = Collections.synchronizedList( new ArrayList<>() );

		for (final WorkerHandle worker : pool.workers()){
			Thread thread = new Thread( () -> {
				try {
					for (int i = next.getAndIncrement(); i < results.length && failures.isEmpty(); i = next.getAndIncrement()){
						results[ i ] = runOne( worker, jobs.get( i ) );
					}
				} catch (IOException e){
					failures.add( e );
				}
			}, "dispatch-" + worker.name );

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

		return new ArrayList<>( java.util.Arrays.asList( results ));
	}

	Episode runOne( WorkerHandle worker, Job job ) throws IOException {
		DataOutputStream out = worker.dataOut;
		DataInputStream in = worker.dataIn;

		out.writeInt( Protocol.MSG_EPISODE );
		out.writeUTF( job.seed );
		out.writeUTF( job.heroClass.name() );
		out.writeBoolean( job.wantReplay );
		out.writeLong( job.policySeed );
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

		return episode;
	}


	double cpuSecondsSinceLast( List<Episode> episodes ){
		double total = 0;
		for (Episode e : episodes){
			if (e.workerCpuTotal < 0 || e.workerName == null) continue;
			Double previous = lastCpuByWorker.get( e.workerName );
			if (previous != null){
				double delta = e.workerCpuTotal - previous;
				if (delta > 0) total += delta;
			}
			lastCpuByWorker.put( e.workerName, e.workerCpuTotal );
		}
		return total;
	}

	@Override
	public Episode run( int worker, Job job ) throws IOException {
		return runOne( pool.workers().get( worker ), job );
	}

	@Override
	public void push( int worker, byte[] payload ) throws IOException {
		pushTo( pool.workers().get( worker ), payload );
	}

	@Override
	public List<byte[]> publish() throws IOException {
		if (inference != null) inference.load( ppo.network );
		List<byte[]> payloads = new ArrayList<>();
		for (int i = 0; i < pool.size(); i++){
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			DataOutputStream tmp = new DataOutputStream( buffer );
			paramWriter.write( tmp );
			tmp.flush();
			payloads.add( buffer.toByteArray() );
		}
		return payloads;
	}

	void pushTo( WorkerHandle worker, byte[] payload ) throws IOException {
		writeFully( worker.dataOut, worker.name, payload );
		worker.dataOut.flush();
		int reply = worker.dataIn.readInt();
		if (reply != Protocol.MSG_PARAMS){
			throw new IOException( worker.name + " rejected a policy push, replied with message " + reply );
		}
	}

	public void pushWeights() throws IOException {
		List<byte[]> payloads = publish();
		List<Thread> threads = new ArrayList<>();
		List<IOException> failures = Collections.synchronizedList( new ArrayList<>() );

		for (int i = 0; i < pool.size(); i++){
			final WorkerHandle worker = pool.workers().get( i );
			final byte[] payload = payloads.get( i );
			Thread thread = new Thread( () -> {
				try {
					pushTo( worker, payload );
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

	public static void writeFully( DataOutputStream out, String name, byte[] payload ) throws IOException {
		int sent = 0;
		while (sent < payload.length){
			int end = Math.min( payload.length, sent + pushChunkBytes );
			try {
				out.write( payload, sent, end - sent );
			} catch (IOException e){
				throw new IOException( name + " refused " + (end - sent) + " bytes of a policy push at offset "
						+ sent + ". The stream may hold part of them, so the push is not retried.", e );
			}
			sent = end;
		}
	}
}
